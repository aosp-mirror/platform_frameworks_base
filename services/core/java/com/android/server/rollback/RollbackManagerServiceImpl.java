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
import android.annotation.NonNull;
import android.annotation.UserIdInt;
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
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IntArray;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of service that manages APK level rollbacks.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub {

    private static final String TAG = "RollbackManager";
    private static final boolean LOCAL_LOGV = false;

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

    // Rollbacks we are in the process of enabling.
    @GuardedBy("mLock")
    private final Set<NewRollback> mNewRollbacks = new ArraySet<>();

    // The list of all rollbacks, including available and committed rollbacks.
    @GuardedBy("mLock")
    private final List<Rollback> mRollbacks;

    // Apk sessions from a staged session with no matching rollback.
    @GuardedBy("mLock")
    private final IntArray mOrphanedApkSessionIds = new IntArray();

    private final RollbackStore mRollbackStore;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
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
            for (Rollback rollback : mRollbacks) {
                mAllocatedRollbackIds.put(rollback.info.getRollbackId(), true);
            }
        }

        // Kick off and start monitoring the handler thread.
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();
        Watchdog.getInstance().addThread(getHandler(), HANDLER_THREAD_TIMEOUT_DURATION_MILLIS);

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
                    int installFlags = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_INSTALL_FLAGS, 0);
                    int user = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_USER, 0);

                    File newPackageCodePath = new File(intent.getData().getPath());

                    queueSleepIfNeeded();

                    getHandler().post(() -> {
                        boolean success =
                                enableRollback(installFlags, newPackageCodePath, user, token);
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
                    int token = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN, -1);
                    if (LOCAL_LOGV) {
                        Slog.v(TAG, "broadcast=ACTION_CANCEL_ENABLE_ROLLBACK token=" + token);
                    }
                    synchronized (mLock) {
                        for (NewRollback rollback : mNewRollbacks) {
                            if (rollback.hasToken(token)) {
                                rollback.setCancelled();
                                return;
                            }
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

    private static long calculateRelativeBootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /**
     * Performs the actual work to commit a rollback.
     * The work is done on the current thread. This may be a long running
     * operation.
     */
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
            updateRollbackLifetimeDurationInMillis();
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
            for (NewRollback newRollback : mNewRollbacks) {
                if (newRollback.rollback.includesPackage(packageName)) {
                    newRollback.setCancelled();
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
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            throw new IllegalStateException("RollbackManagerHandlerThread interrupted");
        }
    }

    private void updateRollbackLifetimeDurationInMillis() {
        mRollbackLifetimeDurationInMillis = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ROLLBACK_BOOT,
                RollbackManager.PROPERTY_ROLLBACK_LIFETIME_MILLIS,
                DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS);
        if (mRollbackLifetimeDurationInMillis < 0) {
            mRollbackLifetimeDurationInMillis = DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS;
        }
    }

    void onBootCompleted() {
        getHandler().post(() -> updateRollbackLifetimeDurationInMillis());
        // Also posts to handler thread
        scheduleExpiration(0);

        getHandler().post(() -> {
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
    private void scheduleExpiration(long duration) {
        getHandler().postDelayed(() -> runExpiration(), duration);
    }

    private Handler getHandler() {
        return mHandlerThread.getThreadHandler();
    }

    // Returns true if <code>session</code> has installFlags and code path
    // matching the installFlags and new package code path given to
    // enableRollback.
    private boolean sessionMatchesForEnableRollback(PackageInstaller.SessionInfo session,
            int installFlags, File newPackageCodePath) {
        if (session == null || session.resolvedBaseCodePath == null) {
            return false;
        }

        File packageCodePath = new File(session.resolvedBaseCodePath).getParentFile();
        if (newPackageCodePath.equals(packageCodePath) && installFlags == session.installFlags) {
            return true;
        }

        return false;
    }

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
     * @param installFlags information about what is being installed.
     * @param newPackageCodePath path to the package about to be installed.
     * @param user the user that owns the install session to enable rollback on.
     * @param token the distinct rollback token sent by package manager.
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    private boolean enableRollback(
            int installFlags, File newPackageCodePath, @UserIdInt int user, int token) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "enableRollback user=" + user + " token=" + token
                    + " path=" + newPackageCodePath.getAbsolutePath());
        }

        // Find the session id associated with this install.
        // TODO: It would be nice if package manager or package installer told
        // us the session directly, rather than have to search for it
        // ourselves.

        // getAllSessions only returns sessions for the associated user.
        // Create a context with the right user so we can find the matching
        // session.
        final Context context = getContextAsUser(UserHandle.of(user));
        if (context == null) {
            Slog.e(TAG, "Unable to create context for install session user.");
            return false;
        }

        PackageInstaller.SessionInfo parentSession = null;
        PackageInstaller.SessionInfo packageSession = null;
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo info : installer.getAllSessions()) {
            if (info.isMultiPackage()) {
                for (int childId : info.getChildSessionIds()) {
                    PackageInstaller.SessionInfo child = installer.getSessionInfo(childId);
                    if (sessionMatchesForEnableRollback(child, installFlags, newPackageCodePath)) {
                        // TODO: Check we only have one matching session?
                        parentSession = info;
                        packageSession = child;
                        break;
                    }
                }
            } else if (sessionMatchesForEnableRollback(info, installFlags, newPackageCodePath)) {
                // TODO: Check we only have one matching session?
                parentSession = info;
                packageSession = info;
                break;
            }
        }

        if (parentSession == null || packageSession == null) {
            Slog.e(TAG, "Unable to find session for enabled rollback.");
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

        NewRollback newRollback;
        synchronized (mLock) {
            // See if we already have a NewRollback that contains this package
            // session. If not, create a NewRollback for the parent session
            // that we will use for all the packages in the session.
            newRollback = getNewRollbackForPackageSessionLocked(packageSession.getSessionId());
            if (newRollback == null) {
                newRollback = createNewRollbackLocked(parentSession);
                mNewRollbacks.add(newRollback);
            }
        }
        newRollback.addToken(token);

        return enableRollbackForPackageSession(newRollback.rollback, packageSession);
    }

    /**
     * Do code and userdata backups to enable rollback of the given session.
     * In case of multiPackage sessions, <code>session</code> should be one of
     * the child sessions, not the parent session.
     *
     * @return true on success, false on failure.
     */
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

        ApplicationInfo appInfo = pkgInfo.applicationInfo;
        return rollback.enableForPackage(packageName, newPackage.versionCode,
                pkgInfo.getLongVersionCode(), isApex, appInfo.sourceDir,
                appInfo.splitSourceDirs);
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
            final PackageManagerInternal pmi = LocalServices.getService(
                    PackageManagerInternal.class);
            pmi.finishPackageInstall(token, false);
        });
    }

    private void snapshotUserDataInternal(String packageName, int[] userIds) {
        if (LOCAL_LOGV) {
            Slog.v(TAG, "snapshotUserData pkg=" + packageName
                    + " users=" + Arrays.toString(userIds));
        }
        synchronized (mLock) {
            // staged installs
            for (int i = 0; i < mRollbacks.size(); i++) {
                Rollback rollback = mRollbacks.get(i);
                rollback.snapshotUserData(packageName, userIds, mAppDataRollbackHelper);
            }
            // non-staged installs
            for (NewRollback rollback : mNewRollbacks) {
                rollback.rollback.snapshotUserData(
                        packageName, userIds, mAppDataRollbackHelper);
            }
        }
    }

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

            NewRollback newRollback;
            synchronized (mLock) {
                newRollback = createNewRollbackLocked(session);
            }

            if (!session.isMultiPackage()) {
                if (!enableRollbackForPackageSession(newRollback.rollback, session)) {
                    Slog.e(TAG, "Unable to enable rollback for session: " + sessionId);
                    result.offer(-1);
                    return;
                }
            } else {
                for (int childSessionId : session.getChildSessionIds()) {
                    final PackageInstaller.SessionInfo childSession =
                            installer.getSessionInfo(childSessionId);
                    if (childSession == null) {
                        Slog.e(TAG, "No matching child install session for: " + childSessionId);
                        result.offer(-1);
                        return;
                    }
                    if (!enableRollbackForPackageSession(newRollback.rollback, childSession)) {
                        Slog.e(TAG, "Unable to enable rollback for session: " + sessionId);
                        result.offer(-1);
                        return;
                    }
                }
            }

            Rollback rollback = completeEnableRollback(newRollback, true);
            if (rollback == null) {
                result.offer(-1);
            } else {
                result.offer(rollback.info.getRollbackId());
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
    private boolean isRollbackWhitelisted(String packageName) {
        // TODO: Remove #isModule when the white list is ready.
        return SystemConfig.getInstance().getRollbackWhitelistedPackages().contains(packageName)
                || isModule(packageName);
    }
    /**
     * Returns true if the package name is the name of a module.
     */
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
            NewRollback newRollback;
            synchronized (mLock) {
                newRollback = getNewRollbackForPackageSessionLocked(sessionId);
                if (newRollback != null) {
                    mNewRollbacks.remove(newRollback);
                }
            }

            if (newRollback != null) {
                Rollback rollback = completeEnableRollback(newRollback, success);
                if (rollback != null && !rollback.isStaged()) {
                    makeRollbackAvailable(rollback);
                }
            }

            // Clear the queue so it will never be leaked to next tests.
            mSleepDuration.clear();
        }
    }

    /**
     * Add a rollback to the list of rollbacks. This should be called after rollback has been
     * enabled for all packages in the rollback. It does not make the rollback available yet.
     *
     * @return the Rollback instance for a successfully enable-completed rollback,
     * or null on error.
     */
    private Rollback completeEnableRollback(NewRollback newRollback, boolean success) {
        Rollback rollback = newRollback.rollback;
        if (LOCAL_LOGV) {
            Slog.v(TAG, "completeEnableRollback id="
                    + rollback.info.getRollbackId() + " success=" + success);
        }
        if (!success) {
            // The install session was aborted, clean up the pending install.
            rollback.delete(mAppDataRollbackHelper);
            return null;
        }

        if (newRollback.isCancelled()) {
            Slog.e(TAG, "Rollback has been cancelled by PackageManager");
            rollback.delete(mAppDataRollbackHelper);
            return null;
        }

        if (rollback.getPackageCount() != newRollback.getPackageSessionIdCount()) {
            Slog.e(TAG, "Failed to enable rollback for all packages in session.");
            rollback.delete(mAppDataRollbackHelper);
            return null;
        }

        rollback.saveRollback();
        synchronized (mLock) {
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
            mRollbacks.add(rollback);
        }

        return rollback;
    }

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

    private static class NewRollback {
        public final Rollback rollback;

        /**
         * This array holds all of the rollback tokens associated with package sessions included in
         * this rollback.
         */
        @GuardedBy("mNewRollbackLock")
        private final IntArray mTokens = new IntArray();

        /**
         * Session ids for all packages in the install. For multi-package sessions, this is the list
         * of child session ids. For normal sessions, this list is a single element with the normal
         * session id.
         */
        private final int[] mPackageSessionIds;

        @GuardedBy("mNewRollbackLock")
        private boolean mIsCancelled = false;

        private final Object mNewRollbackLock = new Object();

        NewRollback(Rollback rollback, int[] packageSessionIds) {
            this.rollback = rollback;
            this.mPackageSessionIds = packageSessionIds;
        }

        /**
         * Adds a rollback token to be associated with this NewRollback. This may be used to
         * identify which rollback should be cancelled in case {@link PackageManager} sends an
         * {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK} intent.
         */
        void addToken(int token) {
            synchronized (mNewRollbackLock) {
                mTokens.add(token);
            }
        }

        /**
         * Returns true if this NewRollback is associated with the provided {@code token}.
         */
        boolean hasToken(int token) {
            synchronized (mNewRollbackLock) {
                return mTokens.indexOf(token) != -1;
            }
        }

        /**
         * Returns true if this NewRollback has been cancelled.
         *
         * <p>Rollback could be invalidated and cancelled if RollbackManager receives
         * {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK} from {@link PackageManager}.
         *
         * <p>The main underlying assumption here is that if enabling the rollback times out, then
         * {@link PackageManager} will NOT send
         * {@link PackageInstaller.SessionCallback#onFinished(int, boolean)} before it broadcasts
         * {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK}.
         */
        boolean isCancelled() {
            synchronized (mNewRollbackLock) {
                return mIsCancelled;
            }
        }

        /**
         * Sets this NewRollback to be marked as cancelled.
         */
        void setCancelled() {
            synchronized (mNewRollbackLock) {
                mIsCancelled = true;
            }
        }

        /**
         * Returns true if this NewRollback contains the provided {@code packageSessionId}.
         */
        boolean containsSessionId(int packageSessionId) {
            for (int id : mPackageSessionIds) {
                if (id == packageSessionId) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the number of package session ids in this NewRollback.
         */
        int getPackageSessionIdCount() {
            return mPackageSessionIds.length;
        }
    }

    @GuardedBy("mLock")
    private NewRollback createNewRollbackLocked(PackageInstaller.SessionInfo parentSession) {
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

        if (parentSession.isStaged()) {
            rollback = mRollbackStore.createStagedRollback(rollbackId, parentSessionId, userId,
                    installerPackageName);
        } else {
            rollback = mRollbackStore.createNonStagedRollback(rollbackId, userId,
                    installerPackageName);
        }

        int[] packageSessionIds;
        if (parentSession.isMultiPackage()) {
            packageSessionIds = parentSession.getChildSessionIds();
        } else {
            packageSessionIds = new int[]{parentSessionId};
        }

        return new NewRollback(rollback, packageSessionIds);
    }

    /**
     * Returns the NewRollback associated with the given package session.
     * Returns null if no NewRollback is found for the given package
     * session.
     */
    @GuardedBy("mLock")
    NewRollback getNewRollbackForPackageSessionLocked(int packageSessionId) {
        // We expect mNewRollbacks to be a very small list; linear search
        // should be plenty fast.
        for (NewRollback newRollback: mNewRollbacks) {
            if (newRollback.containsSessionId(packageSessionId)) {
                return newRollback;
            }
        }
        return null;
    }
}
