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
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.R;
import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * {@code PackageHealthObserver} for {@code RollbackManagerService}.
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
    public int onHealthCheckFailed(VersionedPackage failedPackage) {
        VersionedPackage moduleMetadataPackage = getModuleMetadataPackage();
        if (moduleMetadataPackage == null) {
            // Ignore failure, no mainline update available
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }

        if (getAvailableRollback(mContext.getSystemService(RollbackManager.class),
                        failedPackage, moduleMetadataPackage) == null) {
            // Don't handle the notification, no rollbacks available for the package
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }
        // Rollback is available, we may get a callback into #execute
        return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
    }

    @Override
    public boolean execute(VersionedPackage failedPackage) {
        VersionedPackage moduleMetadataPackage = getModuleMetadataPackage();
        if (moduleMetadataPackage == null) {
            // Ignore failure, no mainline update available
            return false;
        }

        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        Pair<RollbackInfo, Boolean> rollbackPair = getAvailableRollback(rollbackManager,
                failedPackage, moduleMetadataPackage);
        if (rollbackPair == null) {
            Slog.w(TAG, "Expected rollback but no valid rollback found for package: [ "
                    + failedPackage.getPackageName() + "] with versionCode: ["
                    + failedPackage.getVersionCode() + "]");
            return false;
        }

        RollbackInfo rollback = rollbackPair.first;
        // We only log mainline package rollbacks, so check if rollback contains the
        // module metadata provider, if it does, the rollback is a mainline rollback
        boolean hasModuleMetadataPackage = rollbackPair.second;

        if (hasModuleMetadataPackage) {
            StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                    StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE,
                    moduleMetadataPackage.getPackageName(),
                    moduleMetadataPackage.getVersionCode());
        }
        LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            if (hasModuleMetadataPackage) {
                int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                        RollbackManager.STATUS_FAILURE);
                if (status == RollbackManager.STATUS_SUCCESS) {
                    if (rollback.isStaged()) {
                        int rollbackId = rollback.getRollbackId();
                        BroadcastReceiver listener =
                                listenForStagedSessionReady(rollbackManager, rollbackId,
                                        moduleMetadataPackage);
                        handleStagedSessionChange(rollbackManager, rollbackId, listener,
                                moduleMetadataPackage);
                    } else {
                        StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                                StatsLog
                                .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                                moduleMetadataPackage.getPackageName(),
                                moduleMetadataPackage.getVersionCode());
                    }
                } else {
                    StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                            StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                            moduleMetadataPackage.getPackageName(),
                            moduleMetadataPackage.getVersionCode());
                }
            }
        });

        mHandler.post(() ->
                rollbackManager.commitRollback(rollback.getRollbackId(),
                    Collections.singletonList(failedPackage),
                    rollbackReceiver.getIntentSender()));
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
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs,
                false /* withExplicitHealthCheck */);
    }

    /** Verifies the rollback state after a reboot. */
    public void onBootCompleted() {
        int rollbackId = popLastStagedRollbackId();
        if (rollbackId == INVALID_ROLLBACK_ID) {
            // No staged rollback before reboot
            return;
        }

        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
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

        String moduleMetadataPackageName = getModuleMetadataPackageName();
        if (moduleMetadataPackageName == null) {
            // Only log mainline staged rollbacks
            return;
        }

        // Use the version of the metadata package that was installed before
        // we rolled back for logging purposes.
        VersionedPackage moduleMetadataPackage = null;
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            if (moduleMetadataPackageName.equals(packageRollback.getPackageName())) {
                moduleMetadataPackage = packageRollback.getVersionRolledBackFrom();
                break;
            }
        }

        if (moduleMetadataPackage == null) {
            // Only log mainline staged rollbacks
            return;
        }

        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
            return;
        }
        if (sessionInfo.isStagedSessionApplied()) {
            StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                    StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                    moduleMetadataPackage.getPackageName(),
                    moduleMetadataPackage.getVersionCode());
        } else if (sessionInfo.isStagedSessionReady()) {
            // TODO: What do for staged session ready but not applied
        } else {
            StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                    StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                    moduleMetadataPackage.getPackageName(),
                    moduleMetadataPackage.getVersionCode());
        }
    }

    private Pair<RollbackInfo, Boolean> getAvailableRollback(RollbackManager rollbackManager,
            VersionedPackage failedPackage, VersionedPackage moduleMetadataPackage) {
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            // We only rollback mainline packages, so check if rollback contains the
            // module metadata provider, if it does, the rollback is a mainline rollback
            boolean hasModuleMetadataPackage = false;
            boolean hasFailedPackage = false;
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                hasModuleMetadataPackage |= packageRollback.getPackageName().equals(
                        moduleMetadataPackage.getPackageName());
                hasFailedPackage |= packageRollback.getPackageName().equals(
                        failedPackage.getPackageName())
                        && packageRollback.getVersionRolledBackFrom().getVersionCode()
                        == failedPackage.getVersionCode();
            }
            if (hasFailedPackage) {
                return new Pair<RollbackInfo, Boolean>(rollback, hasModuleMetadataPackage);
            }
        }
        return null;
    }

    private String getModuleMetadataPackageName() {
        String packageName = mContext.getResources().getString(
                R.string.config_defaultModuleMetadataProvider);
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        return packageName;
    }

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
            int rollbackId, VersionedPackage moduleMetadataPackage) {
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
            BroadcastReceiver listener, VersionedPackage moduleMetadataPackage) {
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
                if (sessionInfo.isStagedSessionReady()) {
                    mContext.unregisterReceiver(listener);
                    saveLastStagedRollbackId(rollbackId);
                    StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                            StatsLog
                            .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED,
                            moduleMetadataPackage.getPackageName(),
                            moduleMetadataPackage.getVersionCode());
                    mContext.getSystemService(PowerManager.class).reboot("Rollback staged install");
                } else if (sessionInfo.isStagedSessionFailed()) {
                    StatsLog.write(StatsLog.WATCHDOG_ROLLBACK_OCCURRED,
                            StatsLog
                            .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                            moduleMetadataPackage.getPackageName(),
                            moduleMetadataPackage.getVersionCode());
                    mContext.unregisterReceiver(listener);
                }
            }
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
}
