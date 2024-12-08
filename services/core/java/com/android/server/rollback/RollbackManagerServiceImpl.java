/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.Flags;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.VersionedPackage;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.rollback.IRollbackManager;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.ext.SdkExtensions;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongArrayQueue;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.PackageWatchdog;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.ApexManager;
import com.android.server.pm.Installer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Implementation of service that manages APK level rollbacks.
 *
 * Threading model:
 *
 * Each method falls into one of the 3 categories:
 * - @AnyThread annotates thread-safe methods.
 * - @WorkerThread annotates methods that should be called from the handler thread only.
 * - @ExtThread annotates methods that should never be called from the handler thread.
 *
 * Runtime checks that enforce thread annotations:
 * - #assertInWorkerThread checks a method is called from the handler thread only. The handler
 *   thread is where we handle state changes. By having all state changes in the same thread, each
 *   method can run to complete without worrying about state changes in-between locks. It also
 *   allows us to remove the use of lock and reduce the chance of deadlock.
 * - #assertNotInWorkerThread checks a method is never called from the handler thread. These methods
 *   are intended for external entities and should never change internal states directly. Instead
 *   they should dispatch tasks to the handler to make state changes. Violation will fail
 *   #assertInWorkerThread. #assertNotInWorkerThread and #assertInWorkerThread are
 *   mutually-exclusive to ensure @WorkerThread methods and @ExtThread ones never call into each
 *   other.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub implements RollbackManagerInternal {
    /**
     * Denotes that the annotated methods is intended for external entities and should be called on
     * an external thread. By 'external' we mean any thread that is not the handler thread.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.METHOD})
    private @interface ExtThread {
    }

    private static final String TAG = "RollbackManager";
    private static final boolean LOCAL_LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Rollbacks expire after 14 days.
    private static final long DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS =
            TimeUnit.DAYS.toMillis(14);

    // Accessed on the handler thread only.
    private long mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;

    private static final long HANDLER_THREAD_TIMEOUT_DURATION_MILLIS =
            TimeUnit.MINUTES.toMillis(10);

    // Used for generating rollback IDs.
    // Accessed on the handler thread only.
    private final Random mRandom = new SecureRandom();

    // Set of allocated rollback ids.
    // Accessed on the handler thread only.
    private final SparseBooleanArray mAllocatedRollbackIds = new SparseBooleanArray();

    // The list of all rollbacks, including available and committed rollbacks.
    // Accessed on the handler thread only.
    private final List<Rollback> mRollbacks = new ArrayList<>();

    private final RollbackStore mRollbackStore;

    private final Context mContext;
    private final Handler mHandler;
    private final Executor mExecutor;
    private final Installer mInstaller;
    private final RollbackPackageHealthObserver mPackageHealthObserver;
    private final AppDataRollbackHelper mAppDataRollbackHelper;
    private final Runnable mRunExpiration = this::runExpiration;
    private final PackageWatchdog mPackageWatchdog;

    // The # of milli-seconds to sleep for each received ACTION_PACKAGE_ENABLE_ROLLBACK.
    // Used by #blockRollbackManager to test timeout in enabling rollbacks.
    // Accessed on the handler thread only.
    private final LongArrayQueue mSleepDuration = new LongArrayQueue();

    // This field stores the difference in Millis between the uptime (millis since device
    // has booted) and current time (device wall clock) - it's used to update rollback
    // timestamps when the time is changed, by the user or by change of timezone.
    // Accessed on the handler thread only.
    private long  mRelativeBootTime = calculateRelativeBootTime();

    private final ArrayMap<Integer, Pair<Context, BroadcastReceiver>> mUserBroadcastReceivers;

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        // Note that we're calling onStart here because this object is only constructed on
        // SystemService#onStart.
        mInstaller = new Installer(mContext);
        mInstaller.onStart();

        mRollbackStore = new RollbackStore(
                new File(Environment.getDataDirectory(), "rollback"),
                new File(Environment.getDataDirectory(), "rollback-history"));

        mPackageHealthObserver = new RollbackPackageHealthObserver(mContext);
        mAppDataRollbackHelper = new AppDataRollbackHelper(mInstaller);
        mPackageWatchdog = PackageWatchdog.getInstance(mContext);

        // Kick off and start monitoring the handler thread.
        HandlerThread handlerThread = new HandlerThread("RollbackManagerServiceHandler");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        Watchdog.getInstance().addThread(getHandler(), HANDLER_THREAD_TIMEOUT_DURATION_MILLIS);
        mExecutor = new HandlerExecutor(getHandler());

        // Load rollback data from device storage.
        getHandler().post(() -> {
            mRollbacks.addAll(mRollbackStore.loadRollbacks());
            if (!context.getPackageManager().isDeviceUpgrading()) {
                for (Rollback rollback : mRollbacks) {
                    mAllocatedRollbackIds.put(rollback.info.getRollbackId(), true);
                }
            } else {
                // Delete rollbacks when build fingerprint has changed.
                for (Rollback rollback : mRollbacks) {
                    deleteRollback(rollback, "Fingerprint changed");
                }
                mRollbacks.clear();
            }
        });

        mUserBroadcastReceivers = new ArrayMap<>();

        UserManager userManager = mContext.getSystemService(UserManager.class);
        for (UserHandle user : userManager.getUserHandles(true)) {
            registerUserCallbacks(user);
        }

        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction(Intent.ACTION_PACKAGE_ENABLE_ROLLBACK);
        try {
            enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Slog.e(TAG, "addDataType", e);
        }

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertInWorkerThread();

                if (Intent.ACTION_PACKAGE_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int token = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN, -1);
                    int sessionId = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID, -1);

                    queueSleepIfNeeded();

                    getHandler().post(() -> {
                        assertInWorkerThread();
                        boolean success = enableRollback(sessionId);
                        int ret = PackageManagerInternal.ENABLE_ROLLBACK_SUCCEEDED;
                        if (!success) {
                            ret = PackageManagerInternal.ENABLE_ROLLBACK_FAILED;
                        }

                        PackageManagerInternal pm = LocalServices.getService(
                                PackageManagerInternal.class);
                        pm.setEnableRollbackCode(token, ret);
                    });
                }
            }
        }, enableRollbackFilter, null, getHandler());

        IntentFilter enableRollbackTimedOutFilter = new IntentFilter();
        enableRollbackTimedOutFilter.addAction(Intent.ACTION_CANCEL_ENABLE_ROLLBACK);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertInWorkerThread();

                if (Intent.ACTION_CANCEL_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int sessionId = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID, -1);
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "broadcast=ACTION_CANCEL_ENABLE_ROLLBACK id=" + sessionId);
                    }
                    Rollback rollback = getRollbackForSession(sessionId);
                    if (rollback != null && rollback.isEnabling()) {
                        mRollbacks.remove(rollback);
                        deleteRollback(rollback, "Rollback canceled");
                    }
                }
            }
        }, enableRollbackTimedOutFilter, null, getHandler());

        IntentFilter userIntentFilter = new IntentFilter();
        userIntentFilter.addAction(Intent.ACTION_USER_ADDED);
        userIntentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertInWorkerThread();

                if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                    final int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (newUserId == -1) {
                        return;
                    }
                    registerUserCallbacks(UserHandle.of(newUserId));
                } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                    final int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (newUserId == -1) {
                        return;
                    }
                    unregisterUserCallbacks(UserHandle.of(newUserId));
                }
            }
        }, userIntentFilter, null, getHandler());

        registerTimeChangeReceiver();
    }

    private <U> U awaitResult(Supplier<U> supplier) {
        assertNotInWorkerThread();
        try {
            return CompletableFuture.supplyAsync(supplier, mExecutor).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitResult(Runnable runnable) {
        assertNotInWorkerThread();
        try {
            CompletableFuture.runAsync(runnable, mExecutor).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertInWorkerThread() {
        Preconditions.checkState(getHandler().getLooper().isCurrentThread());
    }

    private void assertNotInWorkerThread() {
        Preconditions.checkState(!getHandler().getLooper().isCurrentThread());
    }

    @AnyThread
    private void registerUserCallbacks(UserHandle user) {
        Context context = getContextAsUser(user);
        if (context == null) {
            Slog.e(TAG, "Unable to register user callbacks for user " + user);
            return;
        }

        context.getPackageManager().getPackageInstaller()
                .registerSessionCallback(new SessionCallback(), getHandler());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertInWorkerThread();

                String action = intent.getAction();
                if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "broadcast=ACTION_PACKAGE_REPLACED" + " pkg=" + packageName);
                    }
                    onPackageReplaced(packageName);
                }
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    Slog.i(TAG, "broadcast=ACTION_PACKAGE_FULLY_REMOVED pkg=" + packageName);
                    onPackageFullyRemoved(packageName);
                }
            }
        };
        context.registerReceiver(receiver, filter, null, getHandler());
        mUserBroadcastReceivers.put(user.getIdentifier(), new Pair(context, receiver));
    }

    @AnyThread
    private void unregisterUserCallbacks(UserHandle user) {
        Pair<Context, BroadcastReceiver> pair = mUserBroadcastReceivers.get(user.getIdentifier());
        if (pair == null || pair.first == null || pair.second == null) {
            Slog.e(TAG, "No receiver found for the user" + user);
            return;
        }

        pair.first.unregisterReceiver(pair.second);
        mUserBroadcastReceivers.remove(user.getIdentifier());
    }

    @ExtThread
    @Override
    public ParceledListSlice getAvailableRollbacks() {
        assertNotInWorkerThread();
        enforceManageRollbacks("getAvailableRollbacks");
        return awaitResult(() -> {
            assertInWorkerThread();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.isAvailable()) {
                    rollbacks.add(rollback.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        });
    }

    @ExtThread
    @Override
    public ParceledListSlice<RollbackInfo> getRecentlyCommittedRollbacks() {
        assertNotInWorkerThread();
        enforceManageRollbacks("getRecentlyCommittedRollbacks");

        return awaitResult(() -> {
            assertInWorkerThread();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.isCommitted()) {
                    rollbacks.add(rollback.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        });
    }

    @ExtThread
    @Override
    public void commitRollback(int rollbackId, ParceledListSlice causePackages,
            String callerPackageName, IntentSender statusReceiver) {
        assertNotInWorkerThread();
        enforceManageRollbacks("commitRollback");

        final int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(callingUid, callerPackageName);

        getHandler().post(() ->
                commitRollbackInternal(rollbackId, causePackages.getList(),
                    callerPackageName, statusReceiver));
    }

    @AnyThread
    private void registerTimeChangeReceiver() {
        final BroadcastReceiver timeChangeIntentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                assertInWorkerThread();
                final long oldRelativeBootTime = mRelativeBootTime;
                mRelativeBootTime = calculateRelativeBootTime();
                final long timeDifference = mRelativeBootTime - oldRelativeBootTime;

                Iterator<Rollback> iter = mRollbacks.iterator();
                while (iter.hasNext()) {
                    Rollback rollback = iter.next();
                    rollback.setTimestamp(rollback.getTimestamp().plusMillis(timeDifference));
                }
            }
        };
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        mContext.registerReceiver(timeChangeIntentReceiver, filter,
                null /* broadcastPermission */, getHandler());
    }

    @AnyThread
    private static long calculateRelativeBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /**
     * Performs the actual work to commit a rollback.
     * The work is done on the current thread. This may be a long running
     * operation.
     */
    @WorkerThread
    private void commitRollbackInternal(int rollbackId, List<VersionedPackage> causePackages,
            String callerPackageName, IntentSender statusReceiver) {
        assertInWorkerThread();
        Slog.i(TAG, "commitRollback id=" + rollbackId + " caller=" + callerPackageName);

        Rollback rollback = getRollbackForId(rollbackId);
        if (rollback == null) {
            sendFailure(
                    mContext, statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                    "Rollback unavailable");
            return;
        }
        rollback.commit(mContext, causePackages, callerPackageName, statusReceiver);
    }

    @ExtThread
    @Override
    public void reloadPersistedData() {
        assertNotInWorkerThread();
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "reloadPersistedData");

        awaitResult(() -> {
            assertInWorkerThread();
            mRollbacks.clear();
            mRollbacks.addAll(mRollbackStore.loadRollbacks());
        });
    }

    @WorkerThread
    private void expireRollbackForPackageInternal(String packageName, String reason) {
        assertInWorkerThread();
        Iterator<Rollback> iter = mRollbacks.iterator();
        while (iter.hasNext()) {
            Rollback rollback = iter.next();
            if (rollback.includesPackage(packageName)) {
                iter.remove();
                deleteRollback(rollback, reason);
            }
        }
    }

    @ExtThread
    @Override
    public void expireRollbackForPackage(String packageName) {
        assertNotInWorkerThread();
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "expireRollbackForPackage");
        awaitResult(() -> expireRollbackForPackageInternal(packageName, "Expired by API"));
    }

    @ExtThread
    @Override
    public void blockRollbackManager(long millis) {
        assertNotInWorkerThread();
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "blockRollbackManager");
        getHandler().post(() -> {
            assertInWorkerThread();
            mSleepDuration.addLast(millis);
        });
    }

    @WorkerThread
    private void queueSleepIfNeeded() {
        assertInWorkerThread();
        if (mSleepDuration.size() == 0) {
            return;
        }
        long millis = mSleepDuration.removeFirst();
        if (millis <= 0) {
            return;
        }
        getHandler().post(() -> {
            assertInWorkerThread();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                throw new IllegalStateException("RollbackManagerHandlerThread interrupted");
            }
        });
    }

    @ExtThread
    void onUnlockUser(int userId) {
        assertNotInWorkerThread();
        if (LOCAL_LOGV) {
            Slog.v(TAG, "onUnlockUser id=" + userId);
        }
        // In order to ensure that no package begins running while a backup or restore is taking
        // place, onUnlockUser must remain blocked until all pending backups and restores have
        // completed.
        awaitResult(() -> {
            assertInWorkerThread();
            final List<Rollback> rollbacks;
            rollbacks = new ArrayList<>(mRollbacks);

            for (int i = 0; i < rollbacks.size(); i++) {
                Rollback rollback = rollbacks.get(i);
                rollback.commitPendingBackupAndRestoreForUser(userId, mAppDataRollbackHelper);
            }
        });

        getHandler().post(() -> {
            destroyCeSnapshotsForExpiredRollbacks(userId);
        });
    }

    @WorkerThread
    private void destroyCeSnapshotsForExpiredRollbacks(int userId) {
        int[] rollbackIds = new int[mRollbacks.size()];
        for (int i = 0; i < rollbackIds.length; i++) {
            rollbackIds[i] = mRollbacks.get(i).info.getRollbackId();
        }
        ApexManager.getInstance().destroyCeSnapshotsNotSpecified(userId, rollbackIds);
        try {
            mInstaller.destroyCeSnapshotsNotSpecified(userId, rollbackIds);
        } catch (Installer.InstallerException ie) {
            Slog.e(TAG, "Failed to delete snapshots for user: " + userId, ie);
        }
    }

    @WorkerThread
    private void updateRollbackLifetimeDurationInMillis() {
        assertInWorkerThread();
        mRollbackLifetimeDurationInMillis = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS);
        if (mRollbackLifetimeDurationInMillis < 0) {
            mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;
        }
        Slog.d(TAG, "mRollbackLifetimeDurationInMillis=" + mRollbackLifetimeDurationInMillis);
        runExpiration();
    }

    @AnyThread
    void onBootCompleted() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                mExecutor, properties -> updateRollbackLifetimeDurationInMillis());

        getHandler().post(() -> {
            assertInWorkerThread();
            updateRollbackLifetimeDurationInMillis();
            runExpiration();

            // Check to see if any rollback-enabled staged sessions or staged
            // rollback sessions been applied.
            List<Rollback> enabling = new ArrayList<>();
            List<Rollback> restoreInProgress = new ArrayList<>();
            Set<String> apexPackageNames = new HashSet<>();
            Iterator<Rollback> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                Rollback rollback = iter.next();
                if (!rollback.isStaged()) {
                    // We only care about staged rollbacks here
                    continue;
                }

                PackageInstaller.SessionInfo session = mContext.getPackageManager()
                        .getPackageInstaller().getSessionInfo(rollback.getOriginalSessionId());
                if (session == null || session.isStagedSessionFailed()) {
                    if (rollback.isEnabling()) {
                        iter.remove();
                        deleteRollback(rollback, "Session " + rollback.getOriginalSessionId()
                                + " not existed or failed");
                    }
                    continue;
                }

                if (session.isStagedSessionApplied()) {
                    if (rollback.isEnabling()) {
                        enabling.add(rollback);
                    } else if (rollback.isRestoreUserDataInProgress()) {
                        restoreInProgress.add(rollback);
                    }
                }
                apexPackageNames.addAll(rollback.getApexPackageNames());
            }

            for (Rollback rollback : enabling) {
                makeRollbackAvailable(rollback);
            }

            for (Rollback rollback : restoreInProgress) {
                rollback.setRestoreUserDataInProgress(false);
            }

            for (String apexPackageName : apexPackageNames) {
                // We will not receive notifications when an apex is updated,
                // so check now in case any rollbacks ought to be expired. The
                // onPackagedReplace function is safe to call if the package
                // hasn't actually been updated.
                onPackageReplaced(apexPackageName);
            }

            mPackageHealthObserver.onBootCompletedAsync();
        });
    }

    /**
     * Called when a package has been replaced with a different version.
     * Removes all backups for the package not matching the currently
     * installed package version.
     */
    @WorkerThread
    private void onPackageReplaced(String packageName) {
        assertInWorkerThread();
        long installedVersion = getInstalledPackageVersion(packageName);
        Iterator<Rollback> iter = mRollbacks.iterator();
        while (iter.hasNext()) {
            Rollback rollback = iter.next();
            if ((rollback.isAvailable())
                    && rollback.includesPackageWithDifferentVersion(packageName,
                    installedVersion)) {
                iter.remove();
                deleteRollback(rollback, "Package " + packageName + " replaced");
            }
        }
    }

    /**
     * Called when a package has been completely removed from the device.
     * Removes all backups and rollback history for the given package.
     */
    @WorkerThread
    private void onPackageFullyRemoved(String packageName) {
        assertInWorkerThread();
        expireRollbackForPackageInternal(packageName, "Package " + packageName + " removed");
    }

    /**
     * Notifies an IntentSender of failure.
     *
     * @param statusReceiver where to send the failure
     * @param status the RollbackManager.STATUS_* code with the failure.
     * @param message the failure message.
     */
    @AnyThread
    static void sendFailure(Context context, IntentSender statusReceiver,
            @RollbackManager.Status int status, String message) {
        Slog.e(TAG, message);
        try {
            final Intent fillIn = new Intent();
            fillIn.putExtra(RollbackManager.EXTRA_STATUS, status);
            fillIn.putExtra(RollbackManager.EXTRA_STATUS_MESSAGE, message);
            statusReceiver.sendIntent(context, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Nowhere to send the result back to, so don't bother.
        }
    }

    // Check to see if anything needs expiration, and if so, expire it.
    // Schedules future expiration as appropriate.
    @WorkerThread
    private void runExpiration() {
        if (Flags.rollbackLifetime()) {
            runExpirationCustomRollbackLifetime();
        } else {
            runExpirationDefaultRollbackLifetime();
        }
    }

    @WorkerThread
    private void runExpirationDefaultRollbackLifetime() {
        getHandler().removeCallbacks(mRunExpiration);
        assertInWorkerThread();
        Instant now = Instant.now();
        Instant oldest = null;
        Iterator<Rollback> iter = mRollbacks.iterator();
        while (iter.hasNext()) {
            Rollback rollback = iter.next();
            if (!rollback.isAvailable() && !rollback.isCommitted()) {
                continue;
            }
            Instant rollbackTimestamp = rollback.getTimestamp();
            if (!now.isBefore(rollbackTimestamp.plusMillis(mRollbackLifetimeDurationInMillis))) {
                Slog.i(TAG, "runExpiration id=" + rollback.info.getRollbackId());
                iter.remove();
                deleteRollback(rollback, "Expired by timeout");
            } else if (oldest == null || oldest.isAfter(rollbackTimestamp)) {
                oldest = rollbackTimestamp;
            }
        }

        if (oldest != null) {
            long delay = now.until(
                    oldest.plusMillis(mRollbackLifetimeDurationInMillis), ChronoUnit.MILLIS);
            getHandler().postDelayed(mRunExpiration, delay);
        }
    }

    @WorkerThread
    private void runExpirationCustomRollbackLifetime() {
        getHandler().removeCallbacks(mRunExpiration);
        assertInWorkerThread();
        Instant now = Instant.now();
        long minDelay = 0;
        Iterator<Rollback> iter = mRollbacks.iterator();
        while (iter.hasNext()) {
            Rollback rollback = iter.next();
            if (!rollback.isAvailable() && !rollback.isCommitted()) {
                continue;
            }
            long rollbackLifetimeMillis = rollback.getRollbackLifetimeMillis();
            if (rollbackLifetimeMillis <= 0) {
                rollbackLifetimeMillis = mRollbackLifetimeDurationInMillis;
            }

            Instant rollbackExpiryTimestamp = rollback.getTimestamp()
                    .plusMillis(rollbackLifetimeMillis);
            if (!now.isBefore(rollbackExpiryTimestamp)) {
                Slog.i(TAG, "runExpiration id=" + rollback.info.getRollbackId());
                iter.remove();
                deleteRollback(rollback, "Expired by timeout");
                continue;
            }

            long delay = now.until(
                    rollbackExpiryTimestamp, ChronoUnit.MILLIS);
            if (minDelay == 0 || delay < minDelay) {
                minDelay = delay;
            }
        }

        if (minDelay != 0) {
            getHandler().postDelayed(mRunExpiration, minDelay);
        }
    }

    @AnyThread
    private Handler getHandler() {
        return mHandler;
    }

    @AnyThread
    private Context getContextAsUser(UserHandle user) {
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Called via broadcast by the package manager when a package is being
     * staged for install with rollback enabled. Called before the package has
     * been installed.
     *
     * @param sessionId the id of the install session
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    @WorkerThread
    private boolean enableRollback(int sessionId) {
        assertInWorkerThread();
        if (LOCAL_LOGV) {
            Slog.v(TAG, "enableRollback sessionId=" + sessionId);
        }

        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo packageSession = installer.getSessionInfo(sessionId);
        if (packageSession == null) {
            Slog.e(TAG, "Unable to find session for enabled rollback.");
            return false;
        }

        PackageInstaller.SessionInfo parentSession = packageSession.hasParentSessionId()
                ? installer.getSessionInfo(packageSession.getParentSessionId()) : packageSession;
        if (parentSession == null) {
            Slog.e(TAG, "Unable to find parent session for enabled rollback.");
            return false;
        }

        // See if we already have a Rollback that contains this package
        // session. If not, create a new Rollback for the parent session
        // that we will use for all the packages in the session.
        Rollback newRollback = getRollbackForSession(packageSession.getSessionId());
        if (newRollback == null) {
            newRollback = createNewRollback(parentSession);
        }

        if (enableRollbackForPackageSession(newRollback, packageSession)) {
            // Persist the rollback if all packages are enabled. We will make the rollback
            // available once the whole session is installed successfully.
            return newRollback.allPackagesEnabled() ? completeEnableRollback(newRollback) : true;
        } else {
            return false;
        }
    }

    @WorkerThread
    private int computeRollbackDataPolicy(int sessionPolicy, int manifestPolicy) {
        assertInWorkerThread();
        // TODO: In order not to break existing code, the policy specified in the manifest will take
        // precedence only when it is not the default (i.e. RESTORE). We will remove
        // SessionParams#setEnableRollback(boolean, int) and related code when Play has migrated to
        // using the manifest to specify the policy.
        if (manifestPolicy != PackageManager.ROLLBACK_DATA_POLICY_RESTORE) {
            return manifestPolicy;
        }
        return sessionPolicy;
    }

    /**
     * Do code and user-data backups to enable rollback of the given session.
     * In case of multiPackage sessions, <code>session</code> should be one of
     * the child sessions, not the parent session.
     *
     * @return true on success, false on failure.
     */
    @WorkerThread
    private boolean enableRollbackForPackageSession(Rollback rollback,
            PackageInstaller.SessionInfo session) {
        assertInWorkerThread();
        // TODO: Don't attempt to enable rollback for split installs.
        final int installFlags = session.installFlags;
        if ((installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) == 0) {
            Slog.e(TAG, "Rollback is not enabled.");
            return false;
        }
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            Slog.e(TAG, "Rollbacks not supported for instant app install");
            return false;
        }

        if (session.resolvedBaseCodePath == null) {
            Slog.e(TAG, "Session code path has not been resolved.");
            return false;
        }

        // Get information about the package to be installed.
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<ApkLite> parseResult = ApkLiteParseUtils.parseApkLite(
                input.reset(), new File(session.resolvedBaseCodePath), 0);
        if (parseResult.isError()) {
            Slog.e(TAG, "Unable to parse new package: " + parseResult.getErrorMessage(),
                    parseResult.getException());
            return false;
        }
        final ApkLite newPackage = parseResult.getResult();

        final String packageName = newPackage.getPackageName();
        final int rollbackDataPolicy = computeRollbackDataPolicy(
                session.rollbackDataPolicy, newPackage.getRollbackDataPolicy());
        if (!session.isStaged() && (installFlags & PackageManager.INSTALL_APEX) != 0
                && rollbackDataPolicy != PackageManager.ROLLBACK_DATA_POLICY_RETAIN) {
            Slog.e(TAG, "Only RETAIN is supported for rebootless APEX: " + packageName);
            return false;
        }
        Slog.i(TAG, "Enabling rollback for install of " + packageName
                + ", session:" + session.sessionId
                + ", rollbackDataPolicy=" + rollbackDataPolicy
                + ", rollbackId:" + rollback.info.getRollbackId()
                + ", originalSessionId:" + rollback.getOriginalSessionId());

        final String installerPackageName = session.getInstallerPackageName();
        if (!enableRollbackAllowed(installerPackageName, packageName)) {
            Slog.e(TAG, "Installer " + installerPackageName
                    + " is not allowed to enable rollback on " + packageName);
            return false;
        }

        final boolean isApex = ((installFlags & PackageManager.INSTALL_APEX) != 0);

        // Get information about the currently installed package.
        final PackageInfo pkgInfo;
        try {
            pkgInfo = getPackageInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: Support rolling back fresh package installs rather than
            // fail here. Test this case.
            Slog.e(TAG, packageName + " is not installed");
            return false;
        }

        if (isApex) {
            // Check if this apex contains apks inside it. If true, then they should be added as
            // a RollbackPackageInfo into this rollback
            final PackageManagerInternal pmi = LocalServices.getService(
                    PackageManagerInternal.class);
            List<String> apksInApex = pmi.getApksInApex(packageName);
            for (String apkInApex : apksInApex) {
                // Get information about the currently installed package.
                final PackageInfo apkPkgInfo;
                try {
                    apkPkgInfo = getPackageInfo(apkInApex);
                } catch (PackageManager.NameNotFoundException e) {
                    // TODO: Support rolling back fresh package installs rather than
                    // fail here. Test this case.
                    Slog.e(TAG, apkInApex + " is not installed");
                    return false;
                }
                if (!rollback.enableForPackageInApex(
                        apkInApex, apkPkgInfo.getLongVersionCode(), rollbackDataPolicy)) {
                    return false;
                }
            }
        }

        /*
         * The order is important here! Always enable the embedded apk-in-apex (if any) before
         * enabling the embedding apex. Otherwise the rollback object might be in an inconsistent
         * state where an embedding apex is successfully enabled while one of its embedded
         * apk-in-apex failed. Note {@link Rollback#allPackagesEnabled()} won't behave correctly if
         * a rollback object is inconsistent because it doesn't count apk-in-apex.
         */
        ApplicationInfo appInfo = pkgInfo.applicationInfo;
        return rollback.enableForPackage(packageName, newPackage.getVersionCode(),
                pkgInfo.getLongVersionCode(), isApex, appInfo.sourceDir,
                appInfo.splitSourceDirs, rollbackDataPolicy, session.rollbackImpactLevel);
    }

    @ExtThread
    @Override
    public void snapshotAndRestoreUserData(String packageName, List<UserHandle> users, int appId,
            long ceDataInode, String seInfo, int token) {
        assertNotInWorkerThread();
        snapshotAndRestoreUserData(packageName, UserHandle.fromUserHandles(users), appId,
                ceDataInode, seInfo, token);
    }

    @ExtThread
    @Override
    public void snapshotAndRestoreUserData(String packageName, int[] userIds, int appId,
            long ceDataInode, String seInfo, int token) {
        assertNotInWorkerThread();
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "snapshotAndRestoreUserData may only be called by the system.");
        }

        getHandler().post(() -> {
            assertInWorkerThread();
            snapshotUserDataInternal(packageName, userIds);
            restoreUserDataInternal(packageName, userIds, appId, seInfo);
            // When this method is called as part of the install flow, a positive token number is
            // passed to it. Need to notify the PackageManager when we are done.
            if (token > 0) {
                final PackageManagerInternal pmi = LocalServices.getService(
                        PackageManagerInternal.class);
                pmi.finishPackageInstall(token, false);
            }
        });
    }

    @WorkerThread
    private void snapshotUserDataInternal(String packageName, int[] userIds) {
        assertInWorkerThread();
        if (LOCAL_LOGV) {
            Slog.v(TAG, "snapshotUserData pkg=" + packageName
                    + " users=" + Arrays.toString(userIds));
        }
        for (int i = 0; i < mRollbacks.size(); i++) {
            Rollback rollback = mRollbacks.get(i);
            rollback.snapshotUserData(packageName, userIds, mAppDataRollbackHelper);
        }
    }

    @WorkerThread
    private void restoreUserDataInternal(
            String packageName, int[] userIds, int appId, String seInfo) {
        assertInWorkerThread();
        if (LOCAL_LOGV) {
            Slog.v(TAG, "restoreUserData pkg=" + packageName
                    + " users=" + Arrays.toString(userIds));
        }
        for (int i = 0; i < mRollbacks.size(); ++i) {
            Rollback rollback = mRollbacks.get(i);
            if (rollback.restoreUserDataForPackageIfInProgress(
                    packageName, userIds, appId, seInfo, mAppDataRollbackHelper)) {
                return;
            }
        }
    }

    @ExtThread
    @Override
    public int notifyStagedSession(int sessionId) {
        assertNotInWorkerThread();
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyStagedSession may only be called by the system.");
        }

        return awaitResult(() -> {
            assertInWorkerThread();
            Rollback rollback = getRollbackForSession(sessionId);
            return rollback != null ? rollback.info.getRollbackId() : -1;
        });
    }

    /**
     * Returns true if the installer is allowed to enable rollback for the
     * given named package, false otherwise.
     */
    @AnyThread
    private boolean enableRollbackAllowed(String installerPackageName, String packageName) {
        if (installerPackageName == null) {
            return false;
        }

        PackageManager pm = mContext.getPackageManager();
        boolean manageRollbacksGranted = pm.checkPermission(
                Manifest.permission.MANAGE_ROLLBACKS,
                installerPackageName) == PackageManager.PERMISSION_GRANTED;

        boolean testManageRollbacksGranted = pm.checkPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                installerPackageName) == PackageManager.PERMISSION_GRANTED;

        // For now only allow rollbacks for modules, allowlisted packages, or for testing.
        return (isRollbackAllowed(packageName) && manageRollbacksGranted)
            || testManageRollbacksGranted;
    }

    /**
     * Returns true is this package is eligible for enabling rollback.
     */
    @AnyThread
    private boolean isRollbackAllowed(String packageName) {
        return SystemConfig.getInstance().getRollbackWhitelistedPackages().contains(packageName)
                || isModule(packageName);
    }
    /**
     * Returns true if the package name is the name of a module.
     */
    @AnyThread
    private boolean isModule(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        final ModuleInfo moduleInfo;
        try {
            moduleInfo = pm.getModuleInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return moduleInfo != null;
    }

    /**
     * Gets the version of the package currently installed.
     * Returns -1 if the package is not currently installed.
     */
    @AnyThread
    private long getInstalledPackageVersion(String packageName) {
        PackageInfo pkgInfo;
        try {
            pkgInfo = getPackageInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }

        return pkgInfo.getLongVersionCode();
    }

    /**
     * Gets PackageInfo for the given package. Matches any user and apex.
     *
     * @throws PackageManager.NameNotFoundException if no such package is installed.
     */
    @AnyThread
    private PackageInfo getPackageInfo(String packageName)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = mContext.getPackageManager();
        try {
            // The MATCH_ANY_USER flag doesn't mix well with the MATCH_APEX
            // flag, so make two separate attempts to get the package info.
            // We don't need both flags at the same time because we assume
            // apex files are always installed for all users.
            return pm.getPackageInfo(packageName, PackageManager.MATCH_ANY_USER);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
        }
    }

    @WorkerThread
    private class SessionCallback extends PackageInstaller.SessionCallback {

        @Override
        public void onCreated(int sessionId) { }

        @Override
        public void onBadgingChanged(int sessionId) { }

        @Override
        public void onActiveChanged(int sessionId, boolean active) { }

        @Override
        public void onProgressChanged(int sessionId, float progress) { }

        @Override
        public void onFinished(int sessionId, boolean success) {
            assertInWorkerThread();
            if (LOCAL_LOGV) {
                Slog.v(TAG, "SessionCallback.onFinished id=" + sessionId + " success=" + success);
            }

            Rollback rollback = getRollbackForSession(sessionId);
            if (rollback == null || !rollback.isEnabling()
                    || sessionId != rollback.getOriginalSessionId()) {
                // We only care about the parent session id which will tell us whether the
                // whole session is successful or not.
                return;
            }
            if (success) {
                if (!rollback.isStaged() && completeEnableRollback(rollback)) {
                    // completeEnableRollback() ensures the rollback is deleted if not all packages
                    // are enabled. For staged rollbacks, we will make them available in
                    // onBootCompleted().
                    makeRollbackAvailable(rollback);
                }
            } else {
                Slog.w(TAG, "Delete rollback id=" + rollback.info.getRollbackId()
                        + " for failed session id=" + sessionId);
                mRollbacks.remove(rollback);
                deleteRollback(rollback, "Session " + sessionId + " failed");
            }
        }
    }

    /**
     * Persist a rollback as enable-completed. It does not make the rollback available yet.
     * This rollback will be deleted and removed from {@link #mRollbacks} should any error happens.
     *
     * @return {code true} if {code rollback} is successfully enable-completed,
     * or {code false} otherwise.
     */
    @WorkerThread
    private boolean completeEnableRollback(Rollback rollback) {
        assertInWorkerThread();
        if (LOCAL_LOGV) {
            Slog.v(TAG, "completeEnableRollback id=" + rollback.info.getRollbackId());
        }

        // We are checking if number of packages (excluding apk-in-apex) we enabled for rollback is
        // equal to the number of sessions we are installing, to ensure we didn't skip enabling
        // of any sessions. If we successfully enable an apex, then we can assume we enabled
        // rollback for the embedded apk-in-apex, if any.
        if (!rollback.allPackagesEnabled()) {
            Slog.e(TAG, "Failed to enable rollback for all packages in session.");
            mRollbacks.remove(rollback);
            deleteRollback(rollback, "Failed to enable rollback for all packages in session");
            return false;
        }

        // Note: There is a small window of time between when
        // the session has been committed by the package
        // manager and when we make the rollback available
        // here. Presumably the window is small enough that
        // nobody will want to roll back the newly installed
        // package before we make the rollback available.
        // TODO: We'll lose the rollback if the
        // device reboots between when the session is
        // committed and this point. Revisit this after
        // adding support for rollback of staged installs.
        rollback.saveRollback();

        return true;
    }

    @WorkerThread
    @GuardedBy("rollback.getLock")
    private void makeRollbackAvailable(Rollback rollback) {
        assertInWorkerThread();
        Slog.i(TAG, "makeRollbackAvailable id=" + rollback.info.getRollbackId());
        rollback.makeAvailable();
        mPackageHealthObserver.notifyRollbackAvailable(rollback.info);

        if (Flags.recoverabilityDetection()) {
            if (rollback.info.getRollbackImpactLevel() == PackageManager.ROLLBACK_USER_IMPACT_LOW) {
                // TODO(zezeozue): Provide API to explicitly start observing instead
                // of doing this for all rollbacks. If we do this for all rollbacks,
                // should document in PackageInstaller.SessionParams#setEnableRollback
                // After enabling and committing any rollback, observe packages and
                // prepare to rollback if packages crashes too frequently.
                mPackageWatchdog.startExplicitHealthCheck(mPackageHealthObserver,
                        rollback.getPackageNames(), mRollbackLifetimeDurationInMillis);
            }
        } else {
            mPackageWatchdog.startExplicitHealthCheck(mPackageHealthObserver,
                    rollback.getPackageNames(), mRollbackLifetimeDurationInMillis);
        }
        runExpiration();
    }

    /*
     * Returns the rollback with the given rollbackId, if any.
     */
    @WorkerThread
    private Rollback getRollbackForId(int rollbackId) {
        assertInWorkerThread();
        for (int i = 0; i < mRollbacks.size(); ++i) {
            Rollback rollback = mRollbacks.get(i);
            if (rollback.info.getRollbackId() == rollbackId) {
                return rollback;
            }
        }

        return null;
    }

    @WorkerThread
    private int allocateRollbackId() {
        assertInWorkerThread();
        int n = 0;
        int rollbackId;
        do {
            rollbackId = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (!mAllocatedRollbackIds.get(rollbackId, false)) {
                mAllocatedRollbackIds.put(rollbackId, true);
                return rollbackId;
            }
        } while (n++ < 32);

        throw new IllegalStateException("Failed to allocate rollback ID");
    }

    @ExtThread
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        assertNotInWorkerThread();
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        awaitResult(() -> {
            assertInWorkerThread();
            for (Rollback rollback : mRollbacks) {
                rollback.dump(ipw);
            }
            ipw.println();

            List<Rollback> historicalRollbacks = mRollbackStore.loadHistorialRollbacks();
            if (!historicalRollbacks.isEmpty()) {
                ipw.println("Historical rollbacks:");
                ipw.increaseIndent();
                for (Rollback rollback : historicalRollbacks) {
                    rollback.dump(ipw);
                }
                ipw.decreaseIndent();
                ipw.println();
            }

        });
        mPackageWatchdog.dump(ipw);
    }

    @AnyThread
    private void enforceManageRollbacks(@NonNull String message) {
        if ((PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(
                        Manifest.permission.MANAGE_ROLLBACKS))
                && (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(
                        Manifest.permission.TEST_MANAGE_ROLLBACKS))) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.MANAGE_ROLLBACKS + " or "
                    + Manifest.permission.TEST_MANAGE_ROLLBACKS);
        }
    }

    /**
     * Creates and returns a Rollback according to the given SessionInfo
     * and adds it to {@link #mRollbacks}.
     */
    @WorkerThread
    private Rollback createNewRollback(PackageInstaller.SessionInfo parentSession) {
        assertInWorkerThread();
        int rollbackId = allocateRollbackId();
        final int userId;
        if (parentSession.getUser().equals(UserHandle.ALL)) {
            userId = UserHandle.SYSTEM.getIdentifier();
        } else {
            userId = parentSession.getUser().getIdentifier();
        }
        String installerPackageName = parentSession.getInstallerPackageName();
        int parentSessionId = parentSession.getSessionId();

        if (LOCAL_LOGV) {
            Slog.v(TAG, "createNewRollback id=" + rollbackId
                    + " user=" + userId + " installer=" + installerPackageName);
        }

        final int[] packageSessionIds;
        if (parentSession.isMultiPackage()) {
            packageSessionIds = parentSession.getChildSessionIds();
        } else {
            packageSessionIds = new int[]{parentSessionId};
        }

        final Rollback rollback;

        if (parentSession.isStaged()) {
            rollback = mRollbackStore.createStagedRollback(rollbackId, parentSessionId, userId,
                    installerPackageName, packageSessionIds, getExtensionVersions());
        } else {
            rollback = mRollbackStore.createNonStagedRollback(rollbackId, parentSessionId, userId,
                    installerPackageName, packageSessionIds, getExtensionVersions());
        }

        if (Flags.rollbackLifetime()) {
            rollback.setRollbackLifetimeMillis(parentSession.rollbackLifetimeMillis);
        }


        mRollbacks.add(rollback);
        return rollback;
    }

    private SparseIntArray getExtensionVersions() {
        Map<Integer, Integer> allExtensionVersions = SdkExtensions.getAllExtensionVersions();
        SparseIntArray result = new SparseIntArray(allExtensionVersions.size());
        for (int extension : allExtensionVersions.keySet()) {
            result.put(extension, allExtensionVersions.get(extension));
        }
        return result;
    }

    /**
     * Returns the Rollback associated with the given session if parent or child session id matches.
     * Returns null if not found.
     */
    @WorkerThread
    @Nullable
    private Rollback getRollbackForSession(int sessionId) {
        assertInWorkerThread();
        // We expect mRollbacks to be a very small list; linear search should be plenty fast.
        for (int i = 0; i < mRollbacks.size(); ++i) {
            Rollback rollback = mRollbacks.get(i);
            if (rollback.getOriginalSessionId() == sessionId
                    || rollback.containsSessionId(sessionId)) {
                return rollback;
            }
        }
        return null;
    }

    @WorkerThread
    private void deleteRollback(Rollback rollback, String reason) {
        assertInWorkerThread();
        rollback.delete(mAppDataRollbackHelper, reason);
        mRollbackStore.saveRollbackToHistory(rollback);
    }
}
