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
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
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
import android.provider.DeviceConfig;
import android.util.IntArray;
import android.util.Log;
import android.util.LongArrayQueue;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.PackageWatchdog;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.ApexManager;
import com.android.server.pm.Installer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of service that manages APK level rollbacks.
 *
 * Threading model:
 *
 * - @AnyThread annotates thread-safe methods.
 * - @WorkerThread annotates methods that should be called from the handler thread only.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub {

    private static final String TAG = "RollbackManager";
    private static final boolean LOCAL_LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // Rollbacks expire after 14 days.
    private static final long DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS =
            TimeUnit.DAYS.toMillis(14);

    // Lock used to synchronize accesses to in-memory rollback data
    // structures. By convention, methods with the suffix "Locked" require
    // mLock is held when they are called.
    private final Object mLock = new Object();

    // No need for guarding with lock because value is only accessed in handler thread
    // and the value will be written on boot complete. Initialization here happens before
    // handler threads are running so that's fine.
    private long mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;

    private static final long HANDLER_THREAD_TIMEOUT_DURATION_MILLIS =
            TimeUnit.MINUTES.toMillis(10);

    // Used for generating rollback IDs.
    private final Random mRandom = new SecureRandom();

    // Set of allocated rollback ids
    @GuardedBy("mLock")
    private final SparseBooleanArray mAllocatedRollbackIds = new SparseBooleanArray();

    // The list of all rollbacks, including available and committed rollbacks.
    @GuardedBy("mLock")
    private final List<Rollback> mRollbacks;

    // Apk sessions from a staged session with no matching rollback.
    @GuardedBy("mLock")
    private final IntArray mOrphanedApkSessionIds = new IntArray();

    private final RollbackStore mRollbackStore;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Executor mExecutor;
    private final Installer mInstaller;
    private final RollbackPackageHealthObserver mPackageHealthObserver;
    private final AppDataRollbackHelper mAppDataRollbackHelper;

    // The # of milli-seconds to sleep for each received ACTION_PACKAGE_ENABLE_ROLLBACK.
    // Used by #blockRollbackManager to test timeout in enabling rollbacks.
    // Accessed on the handler thread only.
    private final LongArrayQueue mSleepDuration = new LongArrayQueue();

    // This field stores the difference in Millis between the uptime (millis since device
    // has booted) and current time (device wall clock) - it's used to update rollback
    // timestamps when the time is changed, by the user or by change of timezone.
    // No need for guarding with lock because value is only accessed in handler thread.
    private long  mRelativeBootTime = calculateRelativeBootTime();

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        // Note that we're calling onStart here because this object is only constructed on
        // SystemService#onStart.
        mInstaller = new Installer(mContext);
        mInstaller.onStart();

        mRollbackStore = new RollbackStore(new File(Environment.getDataDirectory(), "rollback"));

        mPackageHealthObserver = new RollbackPackageHealthObserver(mContext);
        mAppDataRollbackHelper = new AppDataRollbackHelper(mInstaller);

        // Load rollback data from device storage.
        synchronized (mLock) {
            mRollbacks = mRollbackStore.loadRollbacks();
            if (!context.getPackageManager().isDeviceUpgrading()) {
                for (Rollback rollback : mRollbacks) {
                    mAllocatedRollbackIds.put(rollback.info.getRollbackId(), true);
                }
            } else {
                // Delete rollbacks when build fingerprint has changed.
                for (Rollback rollback : mRollbacks) {
                    rollback.delete(mAppDataRollbackHelper);
                }
                mRollbacks.clear();
            }
        }

        // Kick off and start monitoring the handler thread.
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();
        Watchdog.getInstance().addThread(getHandler(), HANDLER_THREAD_TIMEOUT_DURATION_MILLIS);
        mExecutor = new HandlerExecutor(getHandler());

        for (UserInfo userInfo : UserManager.get(mContext).getUsers(true)) {
            registerUserCallbacks(userInfo.getUserHandle());
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
                if (Intent.ACTION_PACKAGE_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int token = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN, -1);
                    int sessionId = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID, -1);

                    queueSleepIfNeeded();

                    getHandler().post(() -> {
                        boolean success = enableRollback(sessionId);
                        int ret = PackageManagerInternal.ENABLE_ROLLBACK_SUCCEEDED;
                        if (!success) {
                            ret = PackageManagerInternal.ENABLE_ROLLBACK_FAILED;
                        }

                        PackageManagerInternal pm = LocalServices.getService(
                                PackageManagerInternal.class);
                        pm.setEnableRollbackCode(token, ret);
                    });

                    // We're handling the ordered broadcast. Abort the
                    // broadcast because there is no need for it to go to
                    // anyone else.
                    abortBroadcast();
                }
            }
        }, enableRollbackFilter, null, getHandler());

        IntentFilter enableRollbackTimedOutFilter = new IntentFilter();
        enableRollbackTimedOutFilter.addAction(Intent.ACTION_CANCEL_ENABLE_ROLLBACK);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_CANCEL_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int sessionId = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_SESSION_ID, -1);
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "broadcast=ACTION_CANCEL_ENABLE_ROLLBACK id=" + sessionId);
                    }
                    synchronized (mLock) {
                        Rollback rollback = getRollbackForSessionLocked(sessionId);
                        if (rollback != null && rollback.isEnabling()) {
                            mRollbacks.remove(rollback);
                            rollback.delete(mAppDataRollbackHelper);
                        }
                    }
                }
            }
        }, enableRollbackTimedOutFilter, null, getHandler());

        IntentFilter userAddedIntentFilter = new IntentFilter(Intent.ACTION_USER_ADDED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                    final int newUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (newUserId == -1) {
                        return;
                    }
                    registerUserCallbacks(UserHandle.of(newUserId));
                }
            }
        }, userAddedIntentFilter, null, getHandler());

        registerTimeChangeReceiver();
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
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
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
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "broadcast=ACTION_PACKAGE_FULLY_REMOVED"
                                + " pkg=" + packageName);
                    }
                    onPackageFullyRemoved(packageName);
                }
            }
        }, filter, null, getHandler());
    }

    @Override
    public ParceledListSlice getAvailableRollbacks() {
        enforceManageRollbacks("getAvailableRollbacks");
        synchronized (mLock) {
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.isAvailable()) {
                    rollbacks.add(rollback.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public ParceledListSlice<RollbackInfo> getRecentlyCommittedRollbacks() {
        enforceManageRollbacks("getRecentlyCommittedRollbacks");

        synchronized (mLock) {
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.isCommitted()) {
                    rollbacks.add(rollback.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public void commitRollback(int rollbackId, ParceledListSlice causePackages,
            String callerPackageName, IntentSender statusReceiver) {
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
                final long oldRelativeBootTime = mRelativeBootTime;
                mRelativeBootTime = calculateRelativeBootTime();
                final long timeDifference = mRelativeBootTime - oldRelativeBootTime;

                synchronized (mLock) {
                    Iterator<Rollback> iter = mRollbacks.iterator();
                    while (iter.hasNext()) {
                        Rollback rollback = iter.next();
                        rollback.setTimestamp(
                                rollback.getTimestamp().plusMillis(timeDifference));
                    }
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

    @Override
    public void reloadPersistedData() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "reloadPersistedData");

        CountDownLatch latch = new CountDownLatch(1);
        getHandler().post(() -> {
            synchronized (mLock) {
                mRollbacks.clear();
                mRollbacks.addAll(mRollbackStore.loadRollbacks());
            }
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            throw new IllegalStateException("RollbackManagerHandlerThread interrupted");
        }
    }

    @Override
    public void expireRollbackForPackage(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "expireRollbackForPackage");
        synchronized (mLock) {
            Iterator<Rollback> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                Rollback rollback = iter.next();
                if (rollback.includesPackage(packageName)) {
                    iter.remove();
                    rollback.delete(mAppDataRollbackHelper);
                }
            }
        }
    }

    @Override
    public void blockRollbackManager(long millis) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "blockRollbackManager");
        getHandler().post(() -> {
            mSleepDuration.addLast(millis);
        });
    }

    @WorkerThread
    private void queueSleepIfNeeded() {
        if (mSleepDuration.size() == 0) {
            return;
        }
        long millis = mSleepDuration.removeFirst();
        if (millis <= 0) {
            return;
        }
        getHandler().post(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                throw new IllegalStateException("RollbackManagerHandlerThread interrupted");
            }
        });
    }

    void onUnlockUser(int userId) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "onUnlockUser id=" + userId);
        }
        // In order to ensure that no package begins running while a backup or restore is taking
        // place, onUnlockUser must remain blocked until all pending backups and restores have
        // completed.
        CountDownLatch latch = new CountDownLatch(1);
        getHandler().post(() -> {
            final List<Rollback> rollbacks;
            synchronized (mLock) {
                rollbacks = new ArrayList<>(mRollbacks);
            }

            for (int i = 0; i < rollbacks.size(); i++) {
                Rollback rollback = rollbacks.get(i);
                rollback.commitPendingBackupAndRestoreForUser(userId, mAppDataRollbackHelper);
            }

            latch.countDown();

            destroyCeSnapshotsForExpiredRollbacks(userId);
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            throw new IllegalStateException("RollbackManagerHandlerThread interrupted");
        }
    }

    @WorkerThread
    private void destroyCeSnapshotsForExpiredRollbacks(int userId) {
        int[] rollbackIds = new int[mRollbacks.size()];
        for (int i = 0; i < rollbackIds.length; i++) {
            rollbackIds[i] = mRollbacks.get(i).info.getRollbackId();
        }
        ApexManager.getInstance().destroyCeSnapshotsNotSpecified(userId, rollbackIds);
    }

    @WorkerThread
    private void updateRollbackLifetimeDurationInMillis() {
        mRollbackLifetimeDurationInMillis = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS);
        if (mRollbackLifetimeDurationInMillis < 0) {
            mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;
        }
    }

    @AnyThread
    void onBootCompleted() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                mExecutor, properties -> updateRollbackLifetimeDurationInMillis());

        getHandler().post(() -> {
            updateRollbackLifetimeDurationInMillis();
            runExpiration();

            // Check to see if any rollback-enabled staged sessions or staged
            // rollback sessions been applied.
            List<Rollback> enabling = new ArrayList<>();
            List<Rollback> restoreInProgress = new ArrayList<>();
            Set<String> apexPackageNames = new HashSet<>();
            synchronized (mLock) {
                Iterator<Rollback> iter = mRollbacks.iterator();
                while (iter.hasNext()) {
                    Rollback rollback = iter.next();
                    if (!rollback.isStaged()) {
                        // We only care about staged rollbacks here
                        continue;
                    }

                    PackageInstaller.SessionInfo session = mContext.getPackageManager()
                            .getPackageInstaller().getSessionInfo(rollback.getStagedSessionId());
                    if (session == null || session.isStagedSessionFailed()) {
                        iter.remove();
                        rollback.delete(mAppDataRollbackHelper);
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
            }

            for (Rollback rollback : enabling) {
                makeRollbackAvailable(rollback);
            }

            for (Rollback rollback : restoreInProgress) {
                rollback.setRestoreUserDataInProgress(false);
            }

            for (String apexPackageName : apexPackageNames) {
                // We will not recieve notifications when an apex is updated,
                // so check now in case any rollbacks ought to be expired. The
                // onPackagedReplace function is safe to call if the package
                // hasn't actually been updated.
                onPackageReplaced(apexPackageName);
            }

            synchronized (mLock) {
                mOrphanedApkSessionIds.clear();
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
        // TODO: Could this end up incorrectly deleting a rollback for a
        // package that is about to be installed?
        long installedVersion = getInstalledPackageVersion(packageName);

        synchronized (mLock) {
            Iterator<Rollback> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                Rollback rollback = iter.next();
                // TODO: Should we remove rollbacks in the ENABLING state here?
                if ((rollback.isEnabling() || rollback.isAvailable())
                        && rollback.includesPackageWithDifferentVersion(packageName,
                        installedVersion)) {
                    iter.remove();
                    rollback.delete(mAppDataRollbackHelper);
                }
            }
        }
    }

    /**
     * Called when a package has been completely removed from the device.
     * Removes all backups and rollback history for the given package.
     */
    @WorkerThread
    private void onPackageFullyRemoved(String packageName) {
        expireRollbackForPackage(packageName);
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
        Instant now = Instant.now();
        Instant oldest = null;
        synchronized (mLock) {
            Iterator<Rollback> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                Rollback rollback = iter.next();
                if (!rollback.isAvailable()) {
                    continue;
                }
                Instant rollbackTimestamp = rollback.getTimestamp();
                if (!now.isBefore(
                        rollbackTimestamp
                                .plusMillis(mRollbackLifetimeDurationInMillis))) {
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "runExpiration id=" + rollback.info.getRollbackId());
                    }
                    iter.remove();
                    rollback.delete(mAppDataRollbackHelper);
                } else if (oldest == null || oldest.isAfter(rollbackTimestamp)) {
                    oldest = rollbackTimestamp;
                }
            }
        }

        if (oldest != null) {
            scheduleExpiration(now.until(oldest.plusMillis(mRollbackLifetimeDurationInMillis),
                        ChronoUnit.MILLIS));
        }
    }

    /**
     * Schedules an expiration check to be run after the given duration in
     * milliseconds has gone by.
     */
    @AnyThread
    private void scheduleExpiration(long duration) {
        getHandler().postDelayed(() -> runExpiration(), duration);
    }

    @AnyThread
    private Handler getHandler() {
        return mHandlerThread.getThreadHandler();
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

        // Check to see if this is the apk session for a staged session with
        // rollback enabled.
        synchronized (mLock) {
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.getApkSessionId() == parentSession.getSessionId()) {
                    // This is the apk session for a staged session with rollback enabled. We do
                    // not need to create a new rollback for this session.
                    return true;
                }
            }
        }

        // Check to see if this is the apk session for a staged session for which rollback was
        // cancelled.
        synchronized (mLock) {
            if (mOrphanedApkSessionIds.indexOf(parentSession.getSessionId()) != -1) {
                Slog.w(TAG, "Not enabling rollback for apk as no matching staged session "
                        + "rollback exists");
                return false;
            }
        }

        Rollback newRollback;
        synchronized (mLock) {
            // See if we already have a Rollback that contains this package
            // session. If not, create a new Rollback for the parent session
            // that we will use for all the packages in the session.
            newRollback = getRollbackForSessionLocked(packageSession.getSessionId());
            if (newRollback == null) {
                newRollback = createNewRollbackLocked(parentSession);
            }
        }

        return enableRollbackForPackageSession(newRollback, packageSession);
    }

    /**
     * Do code and userdata backups to enable rollback of the given session.
     * In case of multiPackage sessions, <code>session</code> should be one of
     * the child sessions, not the parent session.
     *
     * @return true on success, false on failure.
     */
    @WorkerThread
    private boolean enableRollbackForPackageSession(Rollback rollback,
            PackageInstaller.SessionInfo session) {
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
        PackageParser.PackageLite newPackage;
        try {
            newPackage = PackageParser.parsePackageLite(new File(session.resolvedBaseCodePath), 0);
        } catch (PackageParser.PackageParserException e) {
            Slog.e(TAG, "Unable to parse new package", e);
            return false;
        }

        String packageName = newPackage.packageName;
        Slog.i(TAG, "Enabling rollback for install of " + packageName
                + ", session:" + session.sessionId);

        String installerPackageName = session.getInstallerPackageName();
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
                        apkInApex, apkPkgInfo.getLongVersionCode(), session.rollbackDataPolicy)) {
                    return false;
                }
            }
        }

        /**
         * The order is important here! Always enable the embedded apk-in-apex (if any) before
         * enabling the embedding apex. Otherwise the rollback object might be in an inconsistent
         * state where an embedding apex is successfully enabled while one of its embedded
         * apk-in-apex failed. Note {@link Rollback#allPackagesEnabled()} won't behave correctly if
         * a rollback object is inconsistent because it doesn't count apk-in-apex.
         */
        ApplicationInfo appInfo = pkgInfo.applicationInfo;
        return rollback.enableForPackage(packageName, newPackage.versionCode,
                pkgInfo.getLongVersionCode(), isApex, appInfo.sourceDir,
                appInfo.splitSourceDirs, session.rollbackDataPolicy);
    }

    @Override
    public void snapshotAndRestoreUserData(String packageName, int[] userIds, int appId,
            long ceDataInode, String seInfo, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "snapshotAndRestoreUserData may only be called by the system.");
        }

        getHandler().post(() -> {
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
        if (LOCAL_LOGV) {
            Slog.v(TAG, "snapshotUserData pkg=" + packageName
                    + " users=" + Arrays.toString(userIds));
        }
        synchronized (mLock) {
            for (int i = 0; i < mRollbacks.size(); i++) {
                Rollback rollback = mRollbacks.get(i);
                rollback.snapshotUserData(packageName, userIds, mAppDataRollbackHelper);
            }
        }
    }

    @WorkerThread
    private void restoreUserDataInternal(
            String packageName, int[] userIds, int appId, String seInfo) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "restoreUserData pkg=" + packageName
                    + " users=" + Arrays.toString(userIds));
        }
        synchronized (mLock) {
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.restoreUserDataForPackageIfInProgress(
                        packageName, userIds, appId, seInfo, mAppDataRollbackHelper)) {
                    return;
                }
            }
        }
    }

    @Override
    public int notifyStagedSession(int sessionId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyStagedSession may only be called by the system.");
        }
        final LinkedBlockingQueue<Integer> result = new LinkedBlockingQueue<>();

        // NOTE: We post this runnable on the RollbackManager's binder thread because we'd prefer
        // to preserve the invariant that all operations that modify state happen there.
        getHandler().post(() -> {
            PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();

            final PackageInstaller.SessionInfo session = installer.getSessionInfo(sessionId);
            if (session == null) {
                Slog.e(TAG, "No matching install session for: " + sessionId);
                result.offer(-1);
                return;
            }

            Rollback newRollback;
            synchronized (mLock) {
                newRollback = createNewRollbackLocked(session);
            }

            if (!session.isMultiPackage()) {
                if (!enableRollbackForPackageSession(newRollback, session)) {
                    Slog.e(TAG, "Unable to enable rollback for session: " + sessionId);
                }
            } else {
                for (int childSessionId : session.getChildSessionIds()) {
                    final PackageInstaller.SessionInfo childSession =
                            installer.getSessionInfo(childSessionId);
                    if (childSession == null) {
                        Slog.e(TAG, "No matching child install session for: " + childSessionId);
                        break;
                    }
                    if (!enableRollbackForPackageSession(newRollback, childSession)) {
                        Slog.e(TAG, "Unable to enable rollback for session: " + sessionId);
                        break;
                    }
                }
            }

            if (!completeEnableRollback(newRollback)) {
                result.offer(-1);
            } else {
                result.offer(newRollback.info.getRollbackId());
            }
        });

        try {
            return result.take();
        } catch (InterruptedException ie) {
            Slog.e(TAG, "Interrupted while waiting for notifyStagedSession response");
            return -1;
        }
    }

    @Override
    public void notifyStagedApkSession(int originalSessionId, int apkSessionId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyStagedApkSession may only be called by the system.");
        }
        getHandler().post(() -> {
            Rollback rollback = null;
            synchronized (mLock) {
                for (int i = 0; i < mRollbacks.size(); ++i) {
                    Rollback candidate = mRollbacks.get(i);
                    if (candidate.getStagedSessionId() == originalSessionId) {
                        rollback = candidate;
                        break;
                    }
                }
                if (rollback == null) {
                    // Did not find rollback matching originalSessionId.
                    Slog.e(TAG, "notifyStagedApkSession did not find rollback for session "
                            + originalSessionId
                            + ". Adding orphaned apk session " + apkSessionId);
                    mOrphanedApkSessionIds.add(apkSessionId);
                }
            }

            if (rollback != null) {
                rollback.setApkSessionId(apkSessionId);
            }
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

        // For now only allow rollbacks for modules or for testing.
        return (isRollbackWhitelisted(packageName) && manageRollbacksGranted)
            || testManageRollbacksGranted;
    }

    /**
     * Returns true is this package is eligible for enabling rollback.
     */
    @AnyThread
    private boolean isRollbackWhitelisted(String packageName) {
        // TODO: Remove #isModule when the white list is ready.
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
            if (LOCAL_LOGV) {
                Slog.v(TAG, "SessionCallback.onFinished id=" + sessionId + " success=" + success);
            }

            if (success) {
                Rollback rollback;
                synchronized (mLock) {
                    rollback = getRollbackForSessionLocked(sessionId);
                }
                if (rollback != null && !rollback.isStaged() && rollback.isEnabling()
                        && rollback.notifySessionWithSuccess()
                        && completeEnableRollback(rollback)) {
                    makeRollbackAvailable(rollback);
                }
            } else {
                synchronized (mLock) {
                    Rollback rollback = getRollbackForSessionLocked(sessionId);
                    if (rollback != null && rollback.isEnabling()) {
                        Slog.w(TAG, "Delete rollback id=" + rollback.info.getRollbackId()
                                + " for failed session id=" + sessionId);
                        mRollbacks.remove(rollback);
                        rollback.delete(mAppDataRollbackHelper);
                    }
                }
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
            rollback.delete(mAppDataRollbackHelper);
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
        if (LOCAL_LOGV) {
            Slog.v(TAG, "makeRollbackAvailable id=" + rollback.info.getRollbackId());
        }
        rollback.makeAvailable();

        // TODO(zezeozue): Provide API to explicitly start observing instead
        // of doing this for all rollbacks. If we do this for all rollbacks,
        // should document in PackageInstaller.SessionParams#setEnableRollback
        // After enabling and committing any rollback, observe packages and
        // prepare to rollback if packages crashes too frequently.
        mPackageHealthObserver.startObservingHealth(rollback.getPackageNames(),
                mRollbackLifetimeDurationInMillis);
        scheduleExpiration(mRollbackLifetimeDurationInMillis);
    }

    /*
     * Returns the rollback with the given rollbackId, if any.
     */
    @WorkerThread
    private Rollback getRollbackForId(int rollbackId) {
        synchronized (mLock) {
            for (int i = 0; i < mRollbacks.size(); ++i) {
                Rollback rollback = mRollbacks.get(i);
                if (rollback.info.getRollbackId() == rollbackId) {
                    return rollback;
                }
            }
        }

        return null;
    }

    @WorkerThread
    @GuardedBy("mLock")
    private int allocateRollbackIdLocked() {
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        synchronized (mLock) {
            for (Rollback rollback : mRollbacks) {
                rollback.dump(ipw);
            }
            ipw.println();
            PackageWatchdog.getInstance(mContext).dump(ipw);
        }
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
    @GuardedBy("mLock")
    private Rollback createNewRollbackLocked(PackageInstaller.SessionInfo parentSession) {
        int rollbackId = allocateRollbackIdLocked();
        final int userId;
        if (parentSession.getUser() == UserHandle.ALL) {
            userId = UserHandle.USER_SYSTEM;
        } else {
            userId = parentSession.getUser().getIdentifier();
        }
        String installerPackageName = parentSession.getInstallerPackageName();
        final Rollback rollback;
        int parentSessionId = parentSession.getSessionId();

        if (LOCAL_LOGV) {
            Slog.v(TAG, "createNewRollback id=" + rollbackId
                    + " user=" + userId + " installer=" + installerPackageName);
        }

        int[] packageSessionIds;
        if (parentSession.isMultiPackage()) {
            packageSessionIds = parentSession.getChildSessionIds();
        } else {
            packageSessionIds = new int[]{parentSessionId};
        }

        if (parentSession.isStaged()) {
            rollback = mRollbackStore.createStagedRollback(rollbackId, parentSessionId, userId,
                    installerPackageName, packageSessionIds);
        } else {
            rollback = mRollbackStore.createNonStagedRollback(rollbackId, userId,
                    installerPackageName, packageSessionIds);
        }

        mRollbacks.add(rollback);
        return rollback;
    }

    /**
     * Returns the Rollback associated with the given session if parent or child session id matches.
     * Returns null if not found.
     */
    @WorkerThread
    @GuardedBy("mLock")
    @Nullable
    private Rollback getRollbackForSessionLocked(int sessionId) {
        // We expect mRollbacks to be a very small list; linear search should be plenty fast.
        for (int i = 0; i < mRollbacks.size(); ++i) {
            Rollback rollback = mRollbacks.get(i);
            if (rollback.getStagedSessionId() == sessionId
                    || rollback.containsSessionId(sessionId)) {
                return rollback;
            }
        }
        return null;
    }
}
