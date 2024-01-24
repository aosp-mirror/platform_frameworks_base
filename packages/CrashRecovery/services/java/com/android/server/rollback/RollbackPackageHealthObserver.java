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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.crashrecovery.flags.Flags;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.sysprop.CrashRecoveryProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;
import com.android.server.crashrecovery.proto.CrashRecoveryStatsLog;
import com.android.server.pm.ApexManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
    private static final int PERSISTENT_MASK = ApplicationInfo.FLAG_PERSISTENT
            | ApplicationInfo.FLAG_SYSTEM;

    private static final String PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG =
            "persist.device_config.configuration.disable_high_impact_rollback";

    private final Context mContext;
    private final Handler mHandler;
    private final ApexManager mApexManager;
    private final File mLastStagedRollbackIdsFile;
    private final File mTwoPhaseRollbackEnabledFile;
    // Staged rollback ids that have been committed but their session is not yet ready
    private final Set<Integer> mPendingStagedRollbackIds = new ArraySet<>();
    // True if needing to roll back only rebootless apexes when native crash happens
    private boolean mTwoPhaseRollbackEnabled;

    @VisibleForTesting
    public RollbackPackageHealthObserver(Context context, ApexManager apexManager) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        File dataDir = new File(Environment.getDataDirectory(), "rollback-observer");
        dataDir.mkdirs();
        mLastStagedRollbackIdsFile = new File(dataDir, "last-staged-rollback-ids");
        mTwoPhaseRollbackEnabledFile = new File(dataDir, "two-phase-rollback-enabled");
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
        mApexManager = apexManager;

        if (SystemProperties.getBoolean("sys.boot_completed", false)) {
            // Load the value from the file if system server has crashed and restarted
            mTwoPhaseRollbackEnabled = readBoolean(mTwoPhaseRollbackEnabledFile);
        } else {
            // Disable two-phase rollback for a normal reboot. We assume the rebootless apex
            // installed before reboot is stable if native crash didn't happen.
            mTwoPhaseRollbackEnabled = false;
            writeBoolean(mTwoPhaseRollbackEnabledFile, false);
        }
    }

    RollbackPackageHealthObserver(Context context) {
        this(context, ApexManager.getInstance());
    }

    @Override
    public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
            @FailureReasons int failureReason, int mitigationCount) {
        int impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        if (Flags.recoverabilityDetection()) {
            List<RollbackInfo> availableRollbacks = getAvailableRollbacks();
            List<RollbackInfo> lowImpactRollbacks = getRollbacksAvailableForImpactLevel(
                    availableRollbacks, PackageManager.ROLLBACK_USER_IMPACT_LOW);
            if (!lowImpactRollbacks.isEmpty()) {
                if (failureReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
                    // For native crashes, we will directly roll back any available rollbacks at low
                    // impact level
                    impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
                } else if (getRollbackForPackage(failedPackage, lowImpactRollbacks) != null) {
                    // Rollback is available for crashing low impact package
                    impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
                } else {
                    impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_70;
                }
            }
        } else {
            boolean anyRollbackAvailable = !mContext.getSystemService(RollbackManager.class)
                    .getAvailableRollbacks().isEmpty();

            if (failureReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH
                    && anyRollbackAvailable) {
                // For native crashes, we will directly roll back any available rollbacks
                // Note: For non-native crashes the rollback-all step has higher impact
                impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
            } else if (getAvailableRollback(failedPackage) != null) {
                // Rollback is available, we may get a callback into #execute
                impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_30;
            } else if (anyRollbackAvailable) {
                // If any rollbacks are available, we will commit them
                impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_70;
            }
        }

        return impact;
    }

    @Override
    public boolean execute(@Nullable VersionedPackage failedPackage,
            @FailureReasons int rollbackReason, int mitigationCount) {
        if (Flags.recoverabilityDetection()) {
            List<RollbackInfo> availableRollbacks = getAvailableRollbacks();
            if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
                mHandler.post(() -> rollbackAllLowImpact(availableRollbacks, rollbackReason));
                return true;
            }

            List<RollbackInfo> lowImpactRollbacks = getRollbacksAvailableForImpactLevel(
                    availableRollbacks, PackageManager.ROLLBACK_USER_IMPACT_LOW);
            RollbackInfo rollback = getRollbackForPackage(failedPackage, lowImpactRollbacks);
            if (rollback != null) {
                mHandler.post(() -> rollbackPackage(rollback, failedPackage, rollbackReason));
            } else if (!lowImpactRollbacks.isEmpty()) {
                // Apply all available low impact rollbacks.
                mHandler.post(() -> rollbackAllLowImpact(availableRollbacks, rollbackReason));
            }
        } else {
            if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
                mHandler.post(() -> rollbackAll(rollbackReason));
                return true;
            }

            RollbackInfo rollback = getAvailableRollback(failedPackage);
            if (rollback != null) {
                mHandler.post(() -> rollbackPackage(rollback, failedPackage, rollbackReason));
            } else {
                mHandler.post(() -> rollbackAll(rollbackReason));
            }
        }

        // Assume rollbacks executed successfully
        return true;
    }

    @Override
    public int onBootLoop(int mitigationCount) {
        int impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        if (Flags.recoverabilityDetection()) {
            List<RollbackInfo> availableRollbacks = getAvailableRollbacks();
            if (!availableRollbacks.isEmpty()) {
                impact = getUserImpactBasedOnRollbackImpactLevel(availableRollbacks);
            }
        }
        return impact;
    }

    @Override
    public boolean executeBootLoopMitigation(int mitigationCount) {
        if (Flags.recoverabilityDetection()) {
            List<RollbackInfo> availableRollbacks = getAvailableRollbacks();

            triggerLeastImpactLevelRollback(availableRollbacks,
                    PackageWatchdog.FAILURE_REASON_BOOT_LOOP);
            return true;
        }
        return false;
    }


    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean mayObservePackage(String packageName) {
        if (getAvailableRollbacks().isEmpty()) {
            return false;
        }
        return isPersistentSystemApp(packageName);
    }

    private List<RollbackInfo> getAvailableRollbacks() {
        return mContext.getSystemService(RollbackManager.class).getAvailableRollbacks();
    }

    private boolean isPersistentSystemApp(@NonNull String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void assertInWorkerThread() {
        Preconditions.checkState(mHandler.getLooper().isCurrentThread());
    }

    /**
     * Start observing health of {@code packages} for {@code durationMs}.
     * This may cause {@code packages} to be rolled back if they crash too freqeuntly.
     */
    @AnyThread
    void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs);
    }

    @AnyThread
    void notifyRollbackAvailable(RollbackInfo rollback) {
        mHandler.post(() -> {
            // Enable two-phase rollback when a rebootless apex rollback is made available.
            // We assume the rebootless apex is stable and is less likely to be the cause
            // if native crash doesn't happen before reboot. So we will clear the flag and disable
            // two-phase rollback after reboot.
            if (isRebootlessApex(rollback)) {
                mTwoPhaseRollbackEnabled = true;
                writeBoolean(mTwoPhaseRollbackEnabledFile, true);
            }
        });
    }

    private static boolean isRebootlessApex(RollbackInfo rollback) {
        if (!rollback.isStaged()) {
            for (PackageRollbackInfo info : rollback.getPackages()) {
                if (info.isApex()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Verifies the rollback state after a reboot and schedules polling for sometime after reboot
     * to check for native crashes and mitigate them if needed.
     */
    @AnyThread
    void onBootCompletedAsync() {
        mHandler.post(()->onBootCompleted());
    }

    @WorkerThread
    private void onBootCompleted() {
        assertInWorkerThread();

        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        if (!rollbackManager.getAvailableRollbacks().isEmpty()) {
            // TODO(gavincorkery): Call into Package Watchdog from outside the observer
            PackageWatchdog.getInstance(mContext).scheduleCheckAndMitigateNativeCrashes();
        }

        SparseArray<String> rollbackIds = popLastStagedRollbackIds();
        for (int i = 0; i < rollbackIds.size(); i++) {
            WatchdogRollbackLogger.logRollbackStatusOnBoot(mContext,
                    rollbackIds.keyAt(i), rollbackIds.valueAt(i),
                    rollbackManager.getRecentlyCommittedRollbacks());
        }
    }

    @AnyThread
    private RollbackInfo getAvailableRollback(VersionedPackage failedPackage) {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageRollback.getVersionRolledBackFrom().equals(failedPackage)) {
                    return rollback;
                }
                // TODO(b/147666157): Extract version number of apk-in-apex so that we don't have
                //  to rely on complicated reasoning as below

                // Due to b/147666157, for apk in apex, we do not know the version we are rolling
                // back from. But if a package X is embedded in apex A exclusively (not embedded in
                // any other apex), which is not guaranteed, then it is sufficient to check only
                // package names here, as the version of failedPackage and the PackageRollbackInfo
                // can't be different. If failedPackage has a higher version, then it must have
                // been updated somehow. There are two ways: it was updated by an update of apex A
                // or updated directly as apk. In both cases, this rollback would have gotten
                // expired when onPackageReplaced() was called. Since the rollback exists, it has
                // same version as failedPackage.
                if (packageRollback.isApkInApex()
                        && packageRollback.getVersionRolledBackFrom().getPackageName()
                        .equals(failedPackage.getPackageName())) {
                    return rollback;
                }
            }
        }
        return null;
    }

    @AnyThread
    private RollbackInfo getRollbackForPackage(@Nullable VersionedPackage failedPackage,
            List<RollbackInfo> availableRollbacks) {
        if (failedPackage == null) {
            return null;
        }

        for (RollbackInfo rollback : availableRollbacks) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageRollback.getVersionRolledBackFrom().equals(failedPackage)) {
                    return rollback;
                }
                // TODO(b/147666157): Extract version number of apk-in-apex so that we don't have
                //  to rely on complicated reasoning as below

                // Due to b/147666157, for apk in apex, we do not know the version we are rolling
                // back from. But if a package X is embedded in apex A exclusively (not embedded in
                // any other apex), which is not guaranteed, then it is sufficient to check only
                // package names here, as the version of failedPackage and the PackageRollbackInfo
                // can't be different. If failedPackage has a higher version, then it must have
                // been updated somehow. There are two ways: it was updated by an update of apex A
                // or updated directly as apk. In both cases, this rollback would have gotten
                // expired when onPackageReplaced() was called. Since the rollback exists, it has
                // same version as failedPackage.
                if (packageRollback.isApkInApex()
                        && packageRollback.getVersionRolledBackFrom().getPackageName()
                        .equals(failedPackage.getPackageName())) {
                    return rollback;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if staged session associated with {@code rollbackId} was marked
     * as handled, {@code false} if already handled.
     */
    @WorkerThread
    private boolean markStagedSessionHandled(int rollbackId) {
        assertInWorkerThread();
        return mPendingStagedRollbackIds.remove(rollbackId);
    }

    /**
     * Returns {@code true} if all pending staged rollback sessions were marked as handled,
     * {@code false} if there is any left.
     */
    @WorkerThread
    private boolean isPendingStagedSessionsEmpty() {
        assertInWorkerThread();
        return mPendingStagedRollbackIds.isEmpty();
    }

    private static boolean readBoolean(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.read() == 1;
        } catch (IOException ignore) {
            return false;
        }
    }

    private static void writeBoolean(File file, boolean value) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(value ? 1 : 0);
            fos.flush();
            FileUtils.sync(fos);
        } catch (IOException ignore) {
        }
    }

    @WorkerThread
    private void saveStagedRollbackId(int stagedRollbackId, @Nullable VersionedPackage logPackage) {
        assertInWorkerThread();
        writeStagedRollbackId(mLastStagedRollbackIdsFile, stagedRollbackId, logPackage);
    }

    static void writeStagedRollbackId(File file, int stagedRollbackId,
            @Nullable VersionedPackage logPackage) {
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            PrintWriter pw = new PrintWriter(fos);
            String logPackageName = logPackage != null ? logPackage.getPackageName() : "";
            pw.append(String.valueOf(stagedRollbackId)).append(",").append(logPackageName);
            pw.println();
            pw.flush();
            FileUtils.sync(fos);
            pw.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save last staged rollback id", e);
            file.delete();
        }
    }

    @WorkerThread
    private SparseArray<String> popLastStagedRollbackIds() {
        assertInWorkerThread();
        try {
            return readStagedRollbackIds(mLastStagedRollbackIdsFile);
        } finally {
            mLastStagedRollbackIdsFile.delete();
        }
    }

    static SparseArray<String> readStagedRollbackIds(File file) {
        SparseArray<String> result = new SparseArray<>();
        try {
            String line;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            while ((line = reader.readLine()) != null) {
                // Each line is of the format: "id,logging_package"
                String[] values = line.trim().split(",");
                String rollbackId = values[0];
                String logPackageName = "";
                if (values.length > 1) {
                    logPackageName = values[1];
                }
                result.put(Integer.parseInt(rollbackId), logPackageName);
            }
        } catch (Exception ignore) {
            return new SparseArray<>();
        }
        return result;
    }


    /**
     * Returns true if the package name is the name of a module.
     */
    @AnyThread
    private boolean isModule(String packageName) {
        // Check if the package is an APK inside an APEX. If it is, use the parent APEX package when
        // querying PackageManager.
        String apexPackageName = mApexManager.getActiveApexPackageNameContainingPackage(
                packageName);
        if (apexPackageName != null) {
            packageName = apexPackageName;
        }

        PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getModuleInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException ignore) {
            return false;
        }
    }

    /**
     * Rolls back the session that owns {@code failedPackage}
     *
     * @param rollback {@code rollbackInfo} of the {@code failedPackage}
     * @param failedPackage the package that needs to be rolled back
     */
    @WorkerThread
    private void rollbackPackage(RollbackInfo rollback, VersionedPackage failedPackage,
            @FailureReasons int rollbackReason) {
        assertInWorkerThread();

        final RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        int reasonToLog = WatchdogRollbackLogger.mapFailureReasonToMetric(rollbackReason);
        final String failedPackageToLog;
        if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            failedPackageToLog = SystemProperties.get(
                    "sys.init.updatable_crashing_process_name", "");
        } else {
            failedPackageToLog = failedPackage.getPackageName();
        }
        VersionedPackage logPackageTemp = null;
        if (isModule(failedPackage.getPackageName())) {
            logPackageTemp = WatchdogRollbackLogger.getLogPackage(mContext, failedPackage);
        }

        final VersionedPackage logPackage = logPackageTemp;
        WatchdogRollbackLogger.logEvent(logPackage,
                CrashRecoveryStatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE,
                reasonToLog, failedPackageToLog);

        Consumer<Intent> onResult = result -> {
            assertInWorkerThread();
            int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                    RollbackManager.STATUS_FAILURE);
            if (status == RollbackManager.STATUS_SUCCESS) {
                if (rollback.isStaged()) {
                    int rollbackId = rollback.getRollbackId();
                    saveStagedRollbackId(rollbackId, logPackage);
                    WatchdogRollbackLogger.logEvent(logPackage,
                            CrashRecoveryStatsLog
                            .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED,
                            reasonToLog, failedPackageToLog);

                } else {
                    WatchdogRollbackLogger.logEvent(logPackage,
                            CrashRecoveryStatsLog
                                    .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                            reasonToLog, failedPackageToLog);
                }
            } else {
                WatchdogRollbackLogger.logEvent(logPackage,
                        CrashRecoveryStatsLog
                                .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        reasonToLog, failedPackageToLog);
            }
            if (rollback.isStaged()) {
                markStagedSessionHandled(rollback.getRollbackId());
                // Wait for all pending staged sessions to get handled before rebooting.
                if (isPendingStagedSessionsEmpty()) {
                    CrashRecoveryProperties.attemptingReboot(true);
                    mContext.getSystemService(PowerManager.class).reboot("Rollback staged install");
                }
            }
        };

        final LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver(result -> {
            mHandler.post(() -> onResult.accept(result));
        });

        rollbackManager.commitRollback(rollback.getRollbackId(),
                Collections.singletonList(failedPackage), rollbackReceiver.getIntentSender());
    }

    /**
     * Two-phase rollback:
     * 1. roll back rebootless apexes first
     * 2. roll back all remaining rollbacks if native crash doesn't stop after (1) is done
     *
     * This approach gives us a better chance to correctly attribute native crash to rebootless
     * apex update without rolling back Mainline updates which might contains critical security
     * fixes.
     */
    @WorkerThread
    private boolean useTwoPhaseRollback(List<RollbackInfo> rollbacks) {
        assertInWorkerThread();
        if (!mTwoPhaseRollbackEnabled) {
            return false;
        }

        Slog.i(TAG, "Rolling back all rebootless APEX rollbacks");
        boolean found = false;
        for (RollbackInfo rollback : rollbacks) {
            if (isRebootlessApex(rollback)) {
                VersionedPackage firstRollback =
                        rollback.getPackages().get(0).getVersionRolledBackFrom();
                rollbackPackage(rollback, firstRollback,
                        PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
                found = true;
            }
        }
        return found;
    }

    /**
     * Rollback the package that has minimum rollback impact level.
     * @param availableRollbacks all available rollbacks
     * @param rollbackReason reason to rollback
     */
    private void triggerLeastImpactLevelRollback(List<RollbackInfo> availableRollbacks,
            @FailureReasons int rollbackReason) {
        int minRollbackImpactLevel = getMinRollbackImpactLevel(availableRollbacks);

        if (minRollbackImpactLevel == PackageManager.ROLLBACK_USER_IMPACT_LOW) {
            // Apply all available low impact rollbacks.
            mHandler.post(() -> rollbackAllLowImpact(availableRollbacks, rollbackReason));
        } else if (minRollbackImpactLevel == PackageManager.ROLLBACK_USER_IMPACT_HIGH) {
            // Check disable_high_impact_rollback device config before performing rollback
            if (SystemProperties.getBoolean(PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG, false)) {
                return;
            }
            // Rollback one package at a time. If that doesn't resolve the issue, rollback
            // next with same impact level.
            mHandler.post(() -> rollbackHighImpact(availableRollbacks, rollbackReason));
        }
    }

    /**
     * sort the available high impact rollbacks by first package name to have a deterministic order.
     * Apply the first available rollback.
     * @param availableRollbacks all available rollbacks
     * @param rollbackReason reason to rollback
     */
    @WorkerThread
    private void rollbackHighImpact(List<RollbackInfo> availableRollbacks,
            @FailureReasons int rollbackReason) {
        assertInWorkerThread();
        List<RollbackInfo> highImpactRollbacks =
                getRollbacksAvailableForImpactLevel(
                        availableRollbacks, PackageManager.ROLLBACK_USER_IMPACT_HIGH);

        // sort rollbacks based on package name of the first package. This is to have a
        // deterministic order of rollbacks.
        List<RollbackInfo> sortedHighImpactRollbacks = highImpactRollbacks.stream().sorted(
                Comparator.comparing(a -> a.getPackages().get(0).getPackageName())).toList();
        VersionedPackage firstRollback =
                sortedHighImpactRollbacks
                        .get(0)
                        .getPackages()
                        .get(0)
                        .getVersionRolledBackFrom();
        rollbackPackage(sortedHighImpactRollbacks.get(0), firstRollback, rollbackReason);
    }

    @WorkerThread
    private void rollbackAll(@FailureReasons int rollbackReason) {
        assertInWorkerThread();
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        List<RollbackInfo> rollbacks = rollbackManager.getAvailableRollbacks();
        if (useTwoPhaseRollback(rollbacks)) {
            return;
        }

        Slog.i(TAG, "Rolling back all available rollbacks");
        // Add all rollback ids to mPendingStagedRollbackIds, so that we do not reboot before all
        // pending staged rollbacks are handled.
        for (RollbackInfo rollback : rollbacks) {
            if (rollback.isStaged()) {
                mPendingStagedRollbackIds.add(rollback.getRollbackId());
            }
        }

        for (RollbackInfo rollback : rollbacks) {
            VersionedPackage firstRollback =
                    rollback.getPackages().get(0).getVersionRolledBackFrom();
            rollbackPackage(rollback, firstRollback, rollbackReason);
        }
    }

    /**
     * Rollback all available low impact rollbacks
     * @param availableRollbacks all available rollbacks
     * @param rollbackReason reason to rollbacks
     */
    @WorkerThread
    private void rollbackAllLowImpact(
            List<RollbackInfo> availableRollbacks, @FailureReasons int rollbackReason) {
        assertInWorkerThread();

        List<RollbackInfo> lowImpactRollbacks = getRollbacksAvailableForImpactLevel(
                availableRollbacks,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
        if (useTwoPhaseRollback(lowImpactRollbacks)) {
            return;
        }

        Slog.i(TAG, "Rolling back all available low impact rollbacks");
        // Add all rollback ids to mPendingStagedRollbackIds, so that we do not reboot before all
        // pending staged rollbacks are handled.
        for (RollbackInfo rollback : lowImpactRollbacks) {
            if (rollback.isStaged()) {
                mPendingStagedRollbackIds.add(rollback.getRollbackId());
            }
        }

        for (RollbackInfo rollback : lowImpactRollbacks) {
            VersionedPackage firstRollback =
                    rollback.getPackages().get(0).getVersionRolledBackFrom();
            rollbackPackage(rollback, firstRollback, rollbackReason);
        }
    }

    private List<RollbackInfo> getRollbacksAvailableForImpactLevel(
            List<RollbackInfo> availableRollbacks, int impactLevel) {
        return availableRollbacks.stream()
                .filter(rollbackInfo -> rollbackInfo.getRollbackImpactLevel() == impactLevel)
                .toList();
    }

    private int getMinRollbackImpactLevel(List<RollbackInfo> availableRollbacks) {
        return availableRollbacks.stream()
                .mapToInt(RollbackInfo::getRollbackImpactLevel)
                .min()
                .orElse(-1);
    }

    private int getUserImpactBasedOnRollbackImpactLevel(List<RollbackInfo> availableRollbacks) {
        int impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        int minImpact = getMinRollbackImpactLevel(availableRollbacks);
        switch (minImpact) {
            case PackageManager.ROLLBACK_USER_IMPACT_LOW:
                impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_70;
                break;
            case PackageManager.ROLLBACK_USER_IMPACT_HIGH:
                if (!SystemProperties.getBoolean(PROP_DISABLE_HIGH_IMPACT_ROLLBACK_FLAG, false)) {
                    impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_90;
                }
                break;
            default:
                impact = PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        }
        return impact;
    }

    @VisibleForTesting
    Handler getHandler() {
        return mHandler;
    }
}
