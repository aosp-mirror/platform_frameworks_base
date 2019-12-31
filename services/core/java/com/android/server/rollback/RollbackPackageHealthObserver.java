/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.rollback;

import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@link PackageHealthObserver} for {@link RollbackManagerService}.
 * This class monitors crashes and triggers RollbackManager rollback accordingly.
 * It also monitors native crashes for some short while after boot.
 *
 * @hide
 */
public final class RollbackPackageHealthObserver implements PackageHealthObserver {
    private static final String TAG = "RollbackPackageHealthObserver";
    private static final String NAME = "rollback-observer";
    private static final int INVALID_ROLLBACK_ID = -1;

    private final Context mContext;
    private final Handler mHandler;
    private final File mLastStagedRollbackIdFile;
    // Staged rollback ids that have been committed but their session is not yet ready
    @GuardedBy("mPendingStagedRollbackIds")
    private final Set<Integer> mPendingStagedRollbackIds = new ArraySet<>();

    RollbackPackageHealthObserver(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        File dataDir = new File(Environment.getDataDirectory(), "rollback-observer");
        dataDir.mkdirs();
        mLastStagedRollbackIdFile = new File(dataDir, "last-staged-rollback-id");
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
    }

    @Override
    public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
            @FailureReasons int failureReason) {
        // For native crashes, we will roll back any available rollbacks
        if (failureReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH
                && !mContext.getSystemService(RollbackManager.class)
                .getAvailableRollbacks().isEmpty()) {
            return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }
        if (getAvailableRollback(failedPackage) == null) {
            // Don't handle the notification, no rollbacks available for the package
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        } else {
            // Rollback is available, we may get a callback into #execute
            return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }
    }

    @Override
    public boolean execute(@Nullable VersionedPackage failedPackage,
            @FailureReasons int rollbackReason) {
        if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            rollbackAll();
            return true;
        }

        RollbackInfo rollback = getAvailableRollback(failedPackage);
        if (rollback == null) {
            Slog.w(TAG, "Expected rollback but no valid rollback found for package: [ "
                    + failedPackage.getPackageName() + "] with versionCode: ["
                    + failedPackage.getVersionCode() + "]");
            return false;
        }
        rollbackPackage(rollback, failedPackage, rollbackReason);
        // Assume rollback executed successfully
        return true;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Start observing health of {@code packages} for {@code durationMs}.
     * This may cause {@code packages} to be rolled back if they crash too freqeuntly.
     */
    public void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs);
    }

    /** Verifies the rollback state after a reboot and schedules polling for sometime after reboot
     * to check for native crashes and mitigate them if needed.
     */
    public void onBootCompletedAsync() {
        mHandler.post(()->onBootCompleted());
    }

    private void onBootCompleted() {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
        String moduleMetadataPackageName = getModuleMetadataPackageName();

        if (!rollbackManager.getAvailableRollbacks().isEmpty()) {
            // TODO(gavincorkery): Call into Package Watchdog from outside the observer
            PackageWatchdog.getInstance(mContext).scheduleCheckAndMitigateNativeCrashes();
        }

        int rollbackId = popLastStagedRollbackId();
        if (rollbackId == INVALID_ROLLBACK_ID) {
            // No staged rollback before reboot
            return;
        }

        RollbackInfo rollback = null;
        for (RollbackInfo info : rollbackManager.getRecentlyCommittedRollbacks()) {
            if (rollbackId == info.getRollbackId()) {
                rollback = info;
                break;
            }
        }

        if (rollback == null) {
            Slog.e(TAG, "rollback info not found for last staged rollback: " + rollbackId);
            return;
        }

        // Use the version of the metadata package that was installed before
        // we rolled back for logging purposes.
        VersionedPackage oldModuleMetadataPackage = null;
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            if (packageRollback.getPackageName().equals(moduleMetadataPackageName)) {
                oldModuleMetadataPackage = packageRollback.getVersionRolledBackFrom();
                break;
            }
        }

        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
            return;
        }
        if (sessionInfo.isStagedSessionApplied()) {
            logEvent(oldModuleMetadataPackage,
                    StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
        } else if (sessionInfo.isStagedSessionReady()) {
            // TODO: What do for staged session ready but not applied
        } else {
            logEvent(oldModuleMetadataPackage,
                    StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                    WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
        }
    }

    private RollbackInfo getAvailableRollback(VersionedPackage failedPackage) {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageRollback.getVersionRolledBackFrom().equals(failedPackage)) {
                    return rollback;
                }
            }
        }
        return null;
    }

    @Nullable
    private String getModuleMetadataPackageName() {
        String packageName = mContext.getResources().getString(
                R.string.config_defaultModuleMetadataProvider);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        return packageName;
    }

    @Nullable
    private VersionedPackage getModuleMetadataPackage() {
        String packageName = getModuleMetadataPackageName();
        if (packageName == null) {
            return null;
        }

        try {
            return new VersionedPackage(packageName, mContext.getPackageManager().getPackageInfo(
                            packageName, 0 /* flags */).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Module metadata provider not found");
            return null;
        }
    }

    private BroadcastReceiver listenForStagedSessionReady(RollbackManager rollbackManager,
            int rollbackId, @Nullable VersionedPackage moduleMetadataPackage) {
        BroadcastReceiver sessionUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleStagedSessionChange(rollbackManager,
                        rollbackId, this /* BroadcastReceiver */, moduleMetadataPackage);
            }
        };
        IntentFilter sessionUpdatedFilter =
                new IntentFilter(PackageInstaller.ACTION_SESSION_UPDATED);
        mContext.registerReceiver(sessionUpdatedReceiver, sessionUpdatedFilter);
        return sessionUpdatedReceiver;
    }

    private void handleStagedSessionChange(RollbackManager rollbackManager, int rollbackId,
            BroadcastReceiver listener, @Nullable VersionedPackage moduleMetadataPackage) {
        PackageInstaller packageInstaller =
                mContext.getPackageManager().getPackageInstaller();
        List<RollbackInfo> recentRollbacks =
                rollbackManager.getRecentlyCommittedRollbacks();
        for (int i = 0; i < recentRollbacks.size(); i++) {
            RollbackInfo recentRollback = recentRollbacks.get(i);
            int sessionId = recentRollback.getCommittedSessionId();
            if ((rollbackId == recentRollback.getRollbackId())
                    && (sessionId != PackageInstaller.SessionInfo.INVALID_ID)) {
                PackageInstaller.SessionInfo sessionInfo =
                        packageInstaller.getSessionInfo(sessionId);
                if (sessionInfo.isStagedSessionReady() && markStagedSessionHandled(rollbackId)) {
                    mContext.unregisterReceiver(listener);
                    saveLastStagedRollbackId(rollbackId);
                    logEvent(moduleMetadataPackage,
                            StatsLog
                            .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED,
                            WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN,
                            "");
                    mContext.getSystemService(PowerManager.class).reboot("Rollback staged install");
                } else if (sessionInfo.isStagedSessionFailed()
                        && markStagedSessionHandled(rollbackId)) {
                    logEvent(moduleMetadataPackage,
                            StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                            WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN,
                            "");
                    mContext.unregisterReceiver(listener);
                }
            }
        }
    }

    /**
     * Returns {@code true} if staged session associated with {@code rollbackId} was marked
     * as handled, {@code false} if already handled.
     */
    private boolean markStagedSessionHandled(int rollbackId) {
        synchronized (mPendingStagedRollbackIds) {
            return mPendingStagedRollbackIds.remove(rollbackId);
        }
    }

    private void saveLastStagedRollbackId(int stagedRollbackId) {
        try {
            FileOutputStream fos = new FileOutputStream(mLastStagedRollbackIdFile);
            PrintWriter pw = new PrintWriter(fos);
            pw.println(stagedRollbackId);
            pw.flush();
            FileUtils.sync(fos);
            pw.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save last staged rollback id", e);
            mLastStagedRollbackIdFile.delete();
        }
    }

    private int popLastStagedRollbackId() {
        int rollbackId = INVALID_ROLLBACK_ID;
        if (!mLastStagedRollbackIdFile.exists()) {
            return rollbackId;
        }

        try {
            rollbackId = Integer.parseInt(
                    IoUtils.readFileAsString(mLastStagedRollbackIdFile.getAbsolutePath()).trim());
        } catch (IOException | NumberFormatException e) {
            Slog.e(TAG, "Failed to retrieve last staged rollback id", e);
        }
        mLastStagedRollbackIdFile.delete();
        return rollbackId;
    }

    private static void logEvent(@Nullable VersionedPackage moduleMetadataPackage, int type,
            int rollbackReason, @NonNull String failingPackageName) {
        Slog.i(TAG, "Watchdog event occurred of type: " + type);
        if (moduleMetadataPackage != null) {
            StatsLog.logWatchdogRollbackOccurred(type, moduleMetadataPackage.getPackageName(),
                    moduleMetadataPackage.getVersionCode(), rollbackReason, failingPackageName);
        }
    }


    /**
     * Returns true if the package name is the name of a module.
     */
    private boolean isModule(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getModuleInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException ignore) {
            return false;
        }
    }

    private VersionedPackage getVersionedPackage(String packageName) {
        try {
            return new VersionedPackage(packageName, mContext.getPackageManager().getPackageInfo(
                    packageName, 0 /* flags */).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Rolls back the session that owns {@code failedPackage}
     *
     * @param rollback {@code rollbackInfo} of the {@code failedPackage}
     * @param failedPackage the package that needs to be rolled back
     */
    private void rollbackPackage(RollbackInfo rollback, VersionedPackage failedPackage,
            @FailureReasons int rollbackReason) {
        final RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        int reasonToLog = mapFailureReasonToMetric(rollbackReason);
        final String failedPackageToLog;
        if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            failedPackageToLog = SystemProperties.get(
                    "sys.init.updatable_crashing_process_name", "");
        } else {
            failedPackageToLog = failedPackage.getPackageName();
        }
        final VersionedPackage logPackage = isModule(failedPackage.getPackageName())
                ? getModuleMetadataPackage()
                : null;

        logEvent(logPackage,
                StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE,
                reasonToLog, failedPackageToLog);
        final LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                    RollbackManager.STATUS_FAILURE);
            if (status == RollbackManager.STATUS_SUCCESS) {
                if (rollback.isStaged()) {
                    int rollbackId = rollback.getRollbackId();
                    synchronized (mPendingStagedRollbackIds) {
                        mPendingStagedRollbackIds.add(rollbackId);
                    }
                    BroadcastReceiver listener =
                            listenForStagedSessionReady(rollbackManager, rollbackId,
                                    logPackage);
                    handleStagedSessionChange(rollbackManager, rollbackId, listener,
                            logPackage);
                } else {
                    logEvent(logPackage,
                            StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                            reasonToLog, failedPackageToLog);
                }
            } else {
                logEvent(logPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        reasonToLog, failedPackageToLog);
            }
        });

        mHandler.post(() ->
                rollbackManager.commitRollback(rollback.getRollbackId(),
                        Collections.singletonList(failedPackage),
                        rollbackReceiver.getIntentSender()));
    }

    private void rollbackAll() {
        Slog.i(TAG, "Rolling back all available rollbacks");
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        List<RollbackInfo> rollbacks = rollbackManager.getAvailableRollbacks();

        for (RollbackInfo rollback : rollbacks) {
            String samplePackageName = rollback.getPackages().get(0).getPackageName();
            VersionedPackage sampleVersionedPackage = getVersionedPackage(samplePackageName);
            if (sampleVersionedPackage == null) {
                Slog.e(TAG, "Failed to rollback " + samplePackageName);
                continue;
            }
            rollbackPackage(rollback, sampleVersionedPackage,
                    PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
        }
    }


    private int mapFailureReasonToMetric(@FailureReasons int failureReason) {
        switch (failureReason) {
            case PackageWatchdog.FAILURE_REASON_NATIVE_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
            case PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
            case PackageWatchdog.FAILURE_REASON_APP_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
            case PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
            default:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;
        }
    }

}
