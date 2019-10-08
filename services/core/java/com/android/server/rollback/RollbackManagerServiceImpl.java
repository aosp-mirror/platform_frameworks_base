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
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;   // duped to avoid merge conflict
import android.os.UserManager;  // out of order to avoid merge conflict
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.pm.Installer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of service that manages APK level rollbacks.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub {

    private static final String TAG = "RollbackManager";

    // Rollbacks expire after 48 hours.
    private static final long DEFAULT_ROLLBACK_LIFETIME_DURATION_MILLIS =
            TimeUnit.HOURS.toMillis(48);

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

    // Package rollback data for rollbacks we are in the process of enabling.
    @GuardedBy("mLock")
    private final Set<NewRollback> mNewRollbacks = new ArraySet<>();

    // The list of all rollbacks, including available and committed rollbacks.
    // This list is null until the rollback data has been loaded.
    @GuardedBy("mLock")
    private List<RollbackData> mRollbacks;

    private final RollbackStore mRollbackStore;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Installer mInstaller;
    private final RollbackPackageHealthObserver mPackageHealthObserver;
    private final AppDataRollbackHelper mAppDataRollbackHelper;

    // This field stores the difference in Millis between the uptime (millis since device
    // has booted) and current time (device wall clock) - it's used to update rollback data
    // timestamps when the time is changed, by the user or by change of timezone.
    // No need for guarding with lock because value is only accessed in handler thread.
    private long  mRelativeBootTime = calculateRelativeBootTime();

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        // Note that we're calling onStart here because this object is only constructed on
        // SystemService#onStart.
        mInstaller = new Installer(mContext);
        mInstaller.onStart();
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();

        // Monitor the handler thread
        Watchdog.getInstance().addThread(getHandler(), HANDLER_THREAD_TIMEOUT_DURATION_MILLIS);

        mRollbackStore = new RollbackStore(new File(Environment.getDataDirectory(), "rollback"));

        mPackageHealthObserver = new RollbackPackageHealthObserver(mContext);
        mAppDataRollbackHelper = new AppDataRollbackHelper(mInstaller);

        // Kick off loading of the rollback data from strorage in a background
        // thread.
        // TODO: Consider loading the rollback data directly here instead, to
        // avoid the need to call ensureRollbackDataLoaded every time before
        // accessing the rollback data?
        // TODO: Test that this kicks off initial scheduling of rollback
        // expiration.
        getHandler().post(() -> ensureRollbackDataLoaded());

        // TODO: Make sure to register these call backs when a new user is
        // added too.
        SessionCallback sessionCallback = new SessionCallback();
        for (UserInfo userInfo : UserManager.get(mContext).getUsers(true)) {
            registerUserCallbacks(userInfo.getUserHandle());
        }

        IntentFilter enableRollbackFilter = new IntentFilter();
        enableRollbackFilter.addAction(Intent.ACTION_PACKAGE_ENABLE_ROLLBACK);
        try {
            enableRollbackFilter.addDataType("application/vnd.android.package-archive");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Log.e(TAG, "addDataType", e);
        }

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_PACKAGE_ENABLE_ROLLBACK.equals(intent.getAction())) {
                    int token = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_TOKEN, -1);
                    int installFlags = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_INSTALL_FLAGS, 0);
                    int[] installedUsers = intent.getIntArrayExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_INSTALLED_USERS);
                    int user = intent.getIntExtra(
                            PackageManagerInternal.EXTRA_ENABLE_ROLLBACK_USER, 0);

                    File newPackageCodePath = new File(intent.getData().getPath());

                    getHandler().post(() -> {
                        boolean success = enableRollback(installFlags, newPackageCodePath,
                                installedUsers, user, token);
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
                    synchronized (mLock) {
                        for (NewRollback rollback : mNewRollbacks) {
                            if (rollback.hasToken(token)) {
                                rollback.isCancelled = true;
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
            Log.e(TAG, "Unable to register user callbacks for user " + user);
            return;
        }

        // TODO: Reuse the same SessionCallback and broadcast receiver
        // instances, rather than creating new instances for each user.

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
                    onPackageReplaced(packageName);
                }
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                    String packageName = intent.getData().getSchemeSpecificPart();
                    onPackageFullyRemoved(packageName);
                }
            }
        }, filter, null, getHandler());
    }

    /**
     * This method posts a blocking call to the handler thread, so it should not be called from
     * that same thread.
     * @throws {@link IllegalStateException} if called from {@link #mHandlerThread}
     */
    @Override
    public ParceledListSlice getAvailableRollbacks() {
        enforceManageRollbacks("getAvailableRollbacks");
        if (Thread.currentThread().equals(mHandlerThread)) {
            Log.wtf(TAG, "Calling getAvailableRollbacks from mHandlerThread "
                    + "causes a deadlock");
            throw new IllegalStateException("Cannot call RollbackManager#getAvailableRollbacks "
                    + "from the handler thread!");
        }

        // Wait for the handler thread to get the list of available rollbacks
        // to get the most up-to-date results. This is intended to reduce test
        // flakiness when checking available rollbacks immediately after
        // installing a package with rollback enabled.
        final LinkedBlockingQueue<Boolean> result = new LinkedBlockingQueue<>();
        getHandler().post(() -> result.offer(true));

        try {
            result.take();
        } catch (InterruptedException ie) {
            // We may not get the most up-to-date information, but whatever we
            // can get now is better than nothing, so log but otherwise ignore
            // the exception.
            Log.w(TAG, "Interrupted while waiting for handler thread in getAvailableRollbacks");
        }

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                RollbackData data = mRollbacks.get(i);
                if (data.state == RollbackData.ROLLBACK_STATE_AVAILABLE) {
                    rollbacks.add(data.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public ParceledListSlice<RollbackInfo> getRecentlyExecutedRollbacks() {
        enforceManageRollbacks("getRecentlyCommittedRollbacks");

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                RollbackData data = mRollbacks.get(i);
                if (data.state == RollbackData.ROLLBACK_STATE_COMMITTED) {
                    rollbacks.add(data.info);
                }
            }
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public void commitRollback(int rollbackId, ParceledListSlice causePackages,
            String callerPackageName, IntentSender statusReceiver) {
        enforceManageRollbacks("executeRollback");

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
                    ensureRollbackDataLoadedLocked();

                    Iterator<RollbackData> iter = mRollbacks.iterator();
                    while (iter.hasNext()) {
                        RollbackData data = iter.next();
                        data.timestamp = data.timestamp.plusMillis(timeDifference);
                        saveRollbackData(data);
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
        Log.i(TAG, "Initiating rollback");

        RollbackData data = getRollbackForId(rollbackId);
        if (data == null || data.state != RollbackData.ROLLBACK_STATE_AVAILABLE) {
            sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                    "Rollback unavailable");
            return;
        }

        // Get a context for the caller to use to install the downgraded
        // version of the package.
        Context context = null;
        try {
            context = mContext.createPackageContext(callerPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE,
                    "Invalid callerPackageName");
            return;
        }

        PackageManager pm = context.getPackageManager();
        try {
            PackageInstaller packageInstaller = pm.getPackageInstaller();
            PackageInstaller.SessionParams parentParams = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setRequestDowngrade(true);
            parentParams.setMultiPackage();
            if (data.isStaged()) {
                parentParams.setStaged();
            }

            int parentSessionId = packageInstaller.createSession(parentParams);
            PackageInstaller.Session parentSession = packageInstaller.openSession(parentSessionId);

            for (PackageRollbackInfo info : data.info.getPackages()) {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                // TODO: We can't get the installerPackageName for apex
                // (b/123920130). Is it okay to ignore the installer package
                // for apex?
                if (!info.isApex()) {
                    String installerPackageName = pm.getInstallerPackageName(info.getPackageName());
                    if (installerPackageName != null) {
                        params.setInstallerPackageName(installerPackageName);
                    }
                }
                params.setRequestDowngrade(true);
                params.setRequiredInstalledVersionCode(
                        info.getVersionRolledBackFrom().getLongVersionCode());
                if (data.isStaged()) {
                    params.setStaged();
                }
                if (info.isApex()) {
                    params.setInstallAsApex();
                }
                int sessionId = packageInstaller.createSession(params);
                PackageInstaller.Session session = packageInstaller.openSession(sessionId);

                File[] packageCodePaths = RollbackStore.getPackageCodePaths(
                        data, info.getPackageName());
                if (packageCodePaths == null) {
                    sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE,
                            "Backup copy of package inaccessible");
                    return;
                }

                for (File packageCodePath : packageCodePaths) {
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(packageCodePath,
                                ParcelFileDescriptor.MODE_READ_ONLY)) {
                        final long token = Binder.clearCallingIdentity();
                        try {
                            session.write(packageCodePath.getName(), 0, packageCodePath.length(),
                                    fd);
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                }
                parentSession.addChildSessionId(sessionId);
            }

            final LocalIntentReceiver receiver = new LocalIntentReceiver(
                    (Intent result) -> {
                        getHandler().post(() -> {

                            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE);
                            if (status != PackageInstaller.STATUS_SUCCESS) {
                                // Committing the rollback failed, but we
                                // still have all the info we need to try
                                // rolling back again, so restore the rollback
                                // state to how it was before we tried
                                // committing.
                                // TODO: Should we just kill this rollback if
                                // commit failed? Why would we expect commit
                                // not to fail again?
                                synchronized (mLock) {
                                    // TODO: Could this cause a rollback to be
                                    // resurrected if it should otherwise have
                                    // expired by now?
                                    data.state = RollbackData.ROLLBACK_STATE_AVAILABLE;
                                    data.restoreUserDataInProgress = false;
                                }
                                sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_INSTALL,
                                        "Rollback downgrade install failed: "
                                        + result.getStringExtra(
                                                PackageInstaller.EXTRA_STATUS_MESSAGE));
                                return;
                            }

                            synchronized (mLock) {
                                if (!data.isStaged()) {
                                    // All calls to restoreUserData should have
                                    // completed by now for a non-staged install.
                                    data.restoreUserDataInProgress = false;
                                }

                                data.info.setCommittedSessionId(parentSessionId);
                                data.info.getCausePackages().addAll(causePackages);
                            }
                            mRollbackStore.deletePackageCodePaths(data);
                            saveRollbackData(data);

                            sendSuccess(statusReceiver);

                            Intent broadcast = new Intent(Intent.ACTION_ROLLBACK_COMMITTED);

                            mContext.sendBroadcastAsUser(broadcast, UserHandle.SYSTEM,
                                    Manifest.permission.MANAGE_ROLLBACKS);
                        });
                    }
            );

            synchronized (mLock) {
                data.state = RollbackData.ROLLBACK_STATE_COMMITTED;
                data.restoreUserDataInProgress = true;
            }
            parentSession.commit(receiver.getIntentSender());
        } catch (IOException e) {
            Log.e(TAG, "Rollback failed", e);
            sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE,
                    "IOException: " + e.toString());
            return;
        }
    }

    @Override
    public void reloadPersistedData() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "reloadPersistedData");

        synchronized (mLock) {
            mRollbacks = null;
        }
        getHandler().post(() -> {
            updateRollbackLifetimeDurationInMillis();
            ensureRollbackDataLoaded();
        });
    }

    @Override
    public void expireRollbackForPackage(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.TEST_MANAGE_ROLLBACKS,
                "expireRollbackForPackage");
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                for (PackageRollbackInfo info : data.info.getPackages()) {
                    if (info.getPackageName().equals(packageName)) {
                        iter.remove();
                        deleteRollback(data);
                        break;
                    }
                }
            }
        }
    }

    void onUnlockUser(int userId) {
        getHandler().post(() -> {
            final List<RollbackData> rollbacks;
            synchronized (mLock) {
                rollbacks = new ArrayList<>(mRollbacks);
            }

            final Set<RollbackData> changed =
                    mAppDataRollbackHelper.commitPendingBackupAndRestoreForUser(userId, rollbacks);

            for (RollbackData rd : changed) {
                saveRollbackData(rd);
            }
        });
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
            List<RollbackData> enabling = new ArrayList<>();
            List<RollbackData> restoreInProgress = new ArrayList<>();
            Set<String> apexPackageNames = new HashSet<>();
            synchronized (mLock) {
                ensureRollbackDataLoadedLocked();
                for (RollbackData data : mRollbacks) {
                    if (data.isStaged()) {
                        if (data.state == RollbackData.ROLLBACK_STATE_ENABLING) {
                            enabling.add(data);
                        } else if (data.restoreUserDataInProgress) {
                            restoreInProgress.add(data);
                        }

                        for (PackageRollbackInfo info : data.info.getPackages()) {
                            if (info.isApex()) {
                                apexPackageNames.add(info.getPackageName());
                            }
                        }
                    }
                }
            }

            for (RollbackData data : enabling) {
                PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionInfo session = installer.getSessionInfo(
                        data.stagedSessionId);
                // TODO: What if session is null?
                if (session != null) {
                    if (session.isStagedSessionApplied()) {
                        makeRollbackAvailable(data);
                    } else if (session.isStagedSessionFailed()) {
                        // TODO: Do we need to remove this from
                        // mRollbacks, or is it okay to leave as
                        // unavailable until the next reboot when it will go
                        // away on its own?
                        deleteRollback(data);
                    }
                }
            }

            for (RollbackData data : restoreInProgress) {
                PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionInfo session = installer.getSessionInfo(
                        data.stagedSessionId);
                // TODO: What if session is null?
                if (session != null) {
                    if (session.isStagedSessionApplied() || session.isStagedSessionFailed()) {
                        synchronized (mLock) {
                            data.restoreUserDataInProgress = false;
                        }
                        saveRollbackData(data);
                    }
                }
            }

            for (String apexPackageName : apexPackageNames) {
                // We will not recieve notifications when an apex is updated,
                // so check now in case any rollbacks ought to be expired. The
                // onPackagedReplace function is safe to call if the package
                // hasn't actually been updated.
                onPackageReplaced(apexPackageName);
            }
            mPackageHealthObserver.onBootCompletedAsync();
        });
    }

    /**
     * Load rollback data from storage if it has not already been loaded.
     * After calling this funciton, mAvailableRollbacks and
     * mRecentlyExecutedRollbacks will be non-null.
     */
    private void ensureRollbackDataLoaded() {
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
        }
    }

    /**
     * Load rollback data from storage if it has not already been loaded.
     * After calling this function, mRollbacks will be non-null.
     */
    @GuardedBy("mLock")
    private void ensureRollbackDataLoadedLocked() {
        if (mRollbacks == null) {
            loadAllRollbackDataLocked();
        }
    }

    /**
     * Load all rollback data from storage.
     * Note: We do potentially heavy IO here while holding mLock, because we
     * have to have the rollback data loaded before we can do anything else
     * meaningful.
     */
    @GuardedBy("mLock")
    private void loadAllRollbackDataLocked() {
        mRollbacks = mRollbackStore.loadAllRollbackData();
        for (RollbackData data : mRollbacks) {
            mAllocatedRollbackIds.put(data.info.getRollbackId(), true);
        }
    }

    /**
     * Called when a package has been replaced with a different version.
     * Removes all backups for the package not matching the currently
     * installed package version.
     */
    private void onPackageReplaced(String packageName) {
        // TODO: Could this end up incorrectly deleting a rollback for a
        // package that is about to be installed?
        VersionedPackage installedVersion = getInstalledPackageVersion(packageName);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                // TODO: Should we remove rollbacks in the ENABLING state here?
                if (data.state == RollbackData.ROLLBACK_STATE_AVAILABLE
                        || data.state == RollbackData.ROLLBACK_STATE_ENABLING) {
                    for (PackageRollbackInfo info : data.info.getPackages()) {
                        if (info.getPackageName().equals(packageName)
                                && !packageVersionsEqual(
                                    info.getVersionRolledBackFrom(),
                                    installedVersion)) {
                            iter.remove();
                            deleteRollback(data);
                            break;
                        }
                    }
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
    private void sendFailure(IntentSender statusReceiver, @RollbackManager.Status int status,
            String message) {
        Log.e(TAG, message);
        try {
            final Intent fillIn = new Intent();
            fillIn.putExtra(RollbackManager.EXTRA_STATUS, status);
            fillIn.putExtra(RollbackManager.EXTRA_STATUS_MESSAGE, message);
            statusReceiver.sendIntent(mContext, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException e) {
            // Nowhere to send the result back to, so don't bother.
        }
    }

    /**
     * Notifies an IntentSender of success.
     */
    private void sendSuccess(IntentSender statusReceiver) {
        try {
            final Intent fillIn = new Intent();
            fillIn.putExtra(RollbackManager.EXTRA_STATUS, RollbackManager.STATUS_SUCCESS);
            statusReceiver.sendIntent(mContext, 0, fillIn, null, null);
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
            ensureRollbackDataLoadedLocked();

            Iterator<RollbackData> iter = mRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                if (data.state != RollbackData.ROLLBACK_STATE_AVAILABLE) {
                    continue;
                }
                if (!now.isBefore(data.timestamp.plusMillis(mRollbackLifetimeDurationInMillis))) {
                    iter.remove();
                    deleteRollback(data);
                } else if (oldest == null || oldest.isAfter(data.timestamp)) {
                    oldest = data.timestamp;
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
     * @param installedUsers the set of users for which a given package is installed.
     * @param user the user that owns the install session to enable rollback on.
     * @param token the distinct rollback token sent by package manager.
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    private boolean enableRollback(int installFlags, File newPackageCodePath,
            int[] installedUsers, @UserIdInt int user, int token) {

        // Find the session id associated with this install.
        // TODO: It would be nice if package manager or package installer told
        // us the session directly, rather than have to search for it
        // ourselves.

        // getAllSessions only returns sessions for the associated user.
        // Create a context with the right user so we can find the matching
        // session.
        final Context context = getContextAsUser(UserHandle.of(user));
        if (context == null) {
            Log.e(TAG, "Unable to create context for install session user.");
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
            Log.e(TAG, "Unable to find session for enabled rollback.");
            return false;
        }

        // Check to see if this is the apk session for a staged session with
        // rollback enabled.
        // TODO: This check could be made more efficient.
        RollbackData rd = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                RollbackData data = mRollbacks.get(i);
                if (data.apkSessionId == parentSession.getSessionId()) {
                    rd = data;
                    break;
                }
            }
        }

        if (rd != null) {
            // This is the apk session for a staged session. We do not need to create a new rollback
            // for this session.
            PackageParser.PackageLite newPackage = null;
            try {
                newPackage = PackageParser.parsePackageLite(
                        new File(packageSession.resolvedBaseCodePath), 0);
            } catch (PackageParser.PackageParserException e) {
                Log.e(TAG, "Unable to parse new package", e);
                return false;
            }
            String packageName = newPackage.packageName;
            for (PackageRollbackInfo info : rd.info.getPackages()) {
                if (info.getPackageName().equals(packageName)) {
                    info.getInstalledUsers().addAll(IntArray.wrap(installedUsers));
                    return true;
                }
            }
            Log.e(TAG, "Unable to find package in apk session");
            return false;
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

        return enableRollbackForPackageSession(newRollback.data, packageSession, installedUsers);
    }

    /**
     * Do code and userdata backups to enable rollback of the given session.
     * In case of multiPackage sessions, <code>session</code> should be one of
     * the child sessions, not the parent session.
     *
     * @return true on success, false on failure.
     */
    private boolean enableRollbackForPackageSession(RollbackData data,
            PackageInstaller.SessionInfo session, @NonNull int[] installedUsers) {
        // TODO: Don't attempt to enable rollback for split installs.
        final int installFlags = session.installFlags;
        if ((installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) == 0) {
            Log.e(TAG, "Rollback is not enabled.");
            return false;
        }
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            Log.e(TAG, "Rollbacks not supported for instant app install");
            return false;
        }

        if (session.resolvedBaseCodePath == null) {
            Log.e(TAG, "Session code path has not been resolved.");
            return false;
        }

        // Get information about the package to be installed.
        PackageParser.PackageLite newPackage = null;
        try {
            newPackage = PackageParser.parsePackageLite(new File(session.resolvedBaseCodePath), 0);
        } catch (PackageParser.PackageParserException e) {
            Log.e(TAG, "Unable to parse new package", e);
            return false;
        }

        String packageName = newPackage.packageName;
        Log.i(TAG, "Enabling rollback for install of " + packageName
                + ", session:" + session.sessionId);

        String installerPackageName = session.getInstallerPackageName();
        if (!enableRollbackAllowed(installerPackageName, packageName)) {
            Log.e(TAG, "Installer " + installerPackageName
                    + " is not allowed to enable rollback on " + packageName);
            return false;
        }

        VersionedPackage newVersion = new VersionedPackage(packageName, newPackage.versionCode);
        final boolean isApex = ((installFlags & PackageManager.INSTALL_APEX) != 0);

        // Get information about the currently installed package.
        PackageManager pm = mContext.getPackageManager();
        final PackageInfo pkgInfo;
        try {
            pkgInfo = getPackageInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: Support rolling back fresh package installs rather than
            // fail here. Test this case.
            Log.e(TAG, packageName + " is not installed");
            return false;
        }

        VersionedPackage installedVersion = new VersionedPackage(packageName,
                pkgInfo.getLongVersionCode());

        PackageRollbackInfo packageRollbackInfo = new PackageRollbackInfo(
                newVersion, installedVersion,
                new IntArray() /* pendingBackups */, new ArrayList<>() /* pendingRestores */,
                isApex, IntArray.wrap(installedUsers),
                new SparseLongArray() /* ceSnapshotInodes */);

        try {
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            RollbackStore.backupPackageCodePath(data, packageName, appInfo.sourceDir);
            if (!ArrayUtils.isEmpty(appInfo.splitSourceDirs)) {
                for (String sourceDir : appInfo.splitSourceDirs) {
                    RollbackStore.backupPackageCodePath(data, packageName, sourceDir);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to copy package for rollback for " + packageName, e);
            return false;
        }

        synchronized (mLock) {
            data.info.getPackages().add(packageRollbackInfo);
        }
        return true;
    }

    @Override
    public void snapshotAndRestoreUserData(String packageName, int[] userIds, int appId,
            long ceDataInode, String seInfo, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "snapshotAndRestoreUserData may only be called by the system.");
        }

        getHandler().post(() -> {
            snapshotUserDataInternal(packageName);
            restoreUserDataInternal(packageName, userIds, appId, ceDataInode, seInfo, token);
            final PackageManagerInternal pmi = LocalServices.getService(
                    PackageManagerInternal.class);
            pmi.finishPackageInstall(token, false);
        });
    }

    private void snapshotUserDataInternal(String packageName) {
        synchronized (mLock) {
            // staged installs
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mRollbacks.size(); i++) {
                RollbackData data = mRollbacks.get(i);
                if (data.state != RollbackData.ROLLBACK_STATE_ENABLING) {
                    continue;
                }

                for (PackageRollbackInfo info : data.info.getPackages()) {
                    if (info.getPackageName().equals(packageName)) {
                        mAppDataRollbackHelper.snapshotAppData(data.info.getRollbackId(), info);
                        saveRollbackData(data);
                        break;
                    }
                }
            }
            // non-staged installs
            PackageRollbackInfo info;
            for (NewRollback rollback : mNewRollbacks) {
                info = getPackageRollbackInfo(rollback.data, packageName);
                if (info != null) {
                    mAppDataRollbackHelper.snapshotAppData(rollback.data.info.getRollbackId(),
                            info);
                    saveRollbackData(rollback.data);
                }
            }
        }
    }

    private void restoreUserDataInternal(String packageName, int[] userIds, int appId,
            long ceDataInode, String seInfo, int token) {
        PackageRollbackInfo info = null;
        RollbackData rollbackData = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                RollbackData data = mRollbacks.get(i);
                if (data.restoreUserDataInProgress) {
                    info = getPackageRollbackInfo(data, packageName);
                    if (info != null) {
                        rollbackData = data;
                        break;
                    }
                }
            }
        }

        if (rollbackData == null) {
            return;
        }

        for (int userId : userIds) {
            final boolean changedRollbackData = mAppDataRollbackHelper.restoreAppData(
                    rollbackData.info.getRollbackId(), info, userId, appId, seInfo);

            // We've updated metadata about this rollback, so save it to flash.
            if (changedRollbackData) {
                saveRollbackData(rollbackData);
            }
        }
    }

    @Override
    public boolean notifyStagedSession(int sessionId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyStagedSession may only be called by the system.");
        }
        final LinkedBlockingQueue<Boolean> result = new LinkedBlockingQueue<>();

        // NOTE: We post this runnable on the RollbackManager's binder thread because we'd prefer
        // to preserve the invariant that all operations that modify state happen there.
        getHandler().post(() -> {
            PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();

            final PackageInstaller.SessionInfo session = installer.getSessionInfo(sessionId);
            if (session == null) {
                Log.e(TAG, "No matching install session for: " + sessionId);
                result.offer(false);
                return;
            }

            NewRollback newRollback;
            synchronized (mLock) {
                newRollback = createNewRollbackLocked(session);
            }

            if (!session.isMultiPackage()) {
                if (!enableRollbackForPackageSession(newRollback.data, session,
                            new int[0])) {
                    Log.e(TAG, "Unable to enable rollback for session: " + sessionId);
                    result.offer(false);
                    return;
                }
            } else {
                for (int childSessionId : session.getChildSessionIds()) {
                    final PackageInstaller.SessionInfo childSession =
                            installer.getSessionInfo(childSessionId);
                    if (childSession == null) {
                        Log.e(TAG, "No matching child install session for: " + childSessionId);
                        result.offer(false);
                        return;
                    }
                    if (!enableRollbackForPackageSession(newRollback.data, childSession,
                                new int[0])) {
                        Log.e(TAG, "Unable to enable rollback for session: " + sessionId);
                        result.offer(false);
                        return;
                    }
                }
            }

            result.offer(completeEnableRollback(newRollback, true) != null);
        });

        try {
            return result.take();
        } catch (InterruptedException ie) {
            Log.e(TAG, "Interrupted while waiting for notifyStagedSession response");
            return false;
        }
    }

    @Override
    public void notifyStagedApkSession(int originalSessionId, int apkSessionId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyStagedApkSession may only be called by the system.");
        }
        getHandler().post(() -> {
            RollbackData rd = null;
            synchronized (mLock) {
                ensureRollbackDataLoadedLocked();
                for (int i = 0; i < mRollbacks.size(); ++i) {
                    RollbackData data = mRollbacks.get(i);
                    if (data.stagedSessionId == originalSessionId) {
                        data.apkSessionId = apkSessionId;
                        rd = data;
                        break;
                    }
                }
            }

            if (rd != null) {
                saveRollbackData(rd);
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
        return (isModule(packageName) && manageRollbacksGranted)
            || testManageRollbacksGranted;
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
     * Returns null if the package is not currently installed.
     */
    private VersionedPackage getInstalledPackageVersion(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = getPackageInfo(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        return new VersionedPackage(packageName, pkgInfo.getLongVersionCode());
    }

    /**
     * Gets PackageInfo for the given package.
     * Matches any user and apex. Returns null if no such package is
     * installed.
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


    private boolean packageVersionsEqual(VersionedPackage a, VersionedPackage b) {
        return a.getPackageName().equals(b.getPackageName())
            && a.getLongVersionCode() == b.getLongVersionCode();
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
            NewRollback newRollback;
            synchronized (mLock) {
                newRollback = getNewRollbackForPackageSessionLocked(sessionId);
                if (newRollback != null) {
                    mNewRollbacks.remove(newRollback);
                }
            }

            if (newRollback != null) {
                RollbackData rollback = completeEnableRollback(newRollback, success);
                if (rollback != null && !rollback.isStaged()) {
                    makeRollbackAvailable(rollback);
                }
            }
        }
    }

    /**
     * Add a rollback to the list of rollbacks.
     * This should be called after rollback has been enabled for all packages
     * in the rollback. It does not make the rollback available yet.
     *
     * @return the rollback data for a successfully enable-completed rollback,
     * or null on error.
     */
    private RollbackData completeEnableRollback(NewRollback newRollback, boolean success) {
        RollbackData data = newRollback.data;
        if (!success) {
            // The install session was aborted, clean up the pending install.
            deleteRollback(data);
            return null;
        }
        if (newRollback.isCancelled) {
            Log.e(TAG, "Rollback has been cancelled by PackageManager");
            deleteRollback(data);
            return null;
        }

        // It's safe to access data.info outside a synchronized block because
        // this is running on the handler thread and all changes to the
        // data.info occur on the handler thread.
        if (data.info.getPackages().size() != newRollback.packageSessionIds.length) {
            Log.e(TAG, "Failed to enable rollback for all packages in session.");
            deleteRollback(data);
            return null;
        }

        saveRollbackData(data);
        synchronized (mLock) {
            // Note: There is a small window of time between when
            // the session has been committed by the package
            // manager and when we make the rollback available
            // here. Presumably the window is small enough that
            // nobody will want to roll back the newly installed
            // package before we make the rollback available.
            // TODO: We'll lose the rollback data if the
            // device reboots between when the session is
            // committed and this point. Revisit this after
            // adding support for rollback of staged installs.
            ensureRollbackDataLoadedLocked();
            mRollbacks.add(data);
        }

        return data;
    }

    private void makeRollbackAvailable(RollbackData data) {
        // TODO: What if the rollback has since been expired, for example due
        // to a new package being installed. Won't this revive an expired
        // rollback? Consider adding a ROLLBACK_STATE_EXPIRED to address this.
        synchronized (mLock) {
            data.state = RollbackData.ROLLBACK_STATE_AVAILABLE;
            data.timestamp = Instant.now();
        }
        saveRollbackData(data);

        // TODO(zezeozue): Provide API to explicitly start observing instead
        // of doing this for all rollbacks. If we do this for all rollbacks,
        // should document in PackageInstaller.SessionParams#setEnableRollback
        // After enabling and commiting any rollback, observe packages and
        // prepare to rollback if packages crashes too frequently.
        List<String> packages = new ArrayList<>();
        for (int i = 0; i < data.info.getPackages().size(); i++) {
            packages.add(data.info.getPackages().get(i).getPackageName());
        }
        mPackageHealthObserver.startObservingHealth(packages,
                mRollbackLifetimeDurationInMillis);
        scheduleExpiration(mRollbackLifetimeDurationInMillis);
    }

    /*
     * Returns the RollbackData, if any, for a rollback with the given
     * rollbackId.
     */
    private RollbackData getRollbackForId(int rollbackId) {
        synchronized (mLock) {
            // TODO: Have ensureRollbackDataLoadedLocked return the list of
            // available rollbacks, to hopefully avoid forgetting to call it?
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mRollbacks.size(); ++i) {
                RollbackData data = mRollbacks.get(i);
                if (data.info.getRollbackId() == rollbackId) {
                    return data;
                }
            }
        }

        return null;
    }

    /**
     * Returns the {@code PackageRollbackInfo} associated with {@code packageName} from
     * a specified {@code RollbackData}.
     */
    private static PackageRollbackInfo getPackageRollbackInfo(RollbackData data,
            String packageName) {
        for (PackageRollbackInfo info : data.info.getPackages()) {
            if (info.getPackageName().equals(packageName)) {
                return info;
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

    private void deleteRollback(RollbackData rollbackData) {
        for (PackageRollbackInfo info : rollbackData.info.getPackages()) {
            IntArray installedUsers = info.getInstalledUsers();
            for (int i = 0; i < installedUsers.size(); i++) {
                int userId = installedUsers.get(i);
                mAppDataRollbackHelper.destroyAppDataSnapshot(rollbackData.info.getRollbackId(),
                        info, userId);
            }
        }
        mRollbackStore.deleteRollbackData(rollbackData);
    }

    /**
     * Saves rollback data, swallowing any IOExceptions.
     * For those times when it's not obvious what to do about the IOException.
     * TODO: Double check we can't do a better job handling the IOException in
     * a cases where this method is called.
     */
    private void saveRollbackData(RollbackData rollbackData) {
        try {
            mRollbackStore.saveRollbackData(rollbackData);
        } catch (IOException ioe) {
            Log.e(TAG, "Unable to save rollback info for: "
                    + rollbackData.info.getRollbackId(), ioe);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        synchronized (mLock) {
            for (RollbackData data : mRollbacks) {
                RollbackInfo info = data.info;
                ipw.println(info.getRollbackId() + ":");
                ipw.increaseIndent();
                ipw.println("-state: " + data.getStateAsString());
                ipw.println("-timestamp: " + data.timestamp);
                if (data.stagedSessionId != -1) {
                    ipw.println("-stagedSessionId: " + data.stagedSessionId);
                }
                ipw.println("-packages:");
                ipw.increaseIndent();
                for (PackageRollbackInfo pkg : info.getPackages()) {
                    ipw.println(pkg.getPackageName()
                            + " " + pkg.getVersionRolledBackFrom().getLongVersionCode()
                            + " -> " + pkg.getVersionRolledBackTo().getLongVersionCode());
                }
                ipw.decreaseIndent();
                if (data.state == RollbackData.ROLLBACK_STATE_COMMITTED) {
                    ipw.println("-causePackages:");
                    ipw.increaseIndent();
                    for (VersionedPackage cPkg : info.getCausePackages()) {
                        ipw.println(cPkg.getPackageName() + " " + cPkg.getLongVersionCode());
                    }
                    ipw.decreaseIndent();
                    ipw.println("-committedSessionId: " + info.getCommittedSessionId());
                }
                ipw.decreaseIndent();
            }
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
        public final RollbackData data;

        /**
         * This array holds all of the rollback tokens associated with package sessions included
         * in this rollback. This is used to identify which rollback should be cancelled in case
         * {@link PackageManager} sends an {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK} intent.
         */
        private final IntArray mTokens = new IntArray();

        /**
         * Session ids for all packages in the install.
         * For multi-package sessions, this is the list of child session ids.
         * For normal sessions, this list is a single element with the normal
         * session id.
         */
        public final int[] packageSessionIds;

        /**
         * Flag to determine whether the RollbackData has been cancelled.
         *
         * <p>RollbackData could be invalidated and cancelled if RollbackManager receives
         * {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK} from {@link PackageManager}.
         *
         * <p>The main underlying assumption here is that if enabling the rollback times out, then
         * {@link PackageManager} will NOT send
         * {@link PackageInstaller.SessionCallback#onFinished(int, boolean)} before it broadcasts
         * {@link Intent#ACTION_CANCEL_ENABLE_ROLLBACK}.
         */
        public boolean isCancelled = false;

        NewRollback(RollbackData data, int[] packageSessionIds) {
            this.data = data;
            this.packageSessionIds = packageSessionIds;
        }

        public void addToken(int token) {
            mTokens.add(token);
        }

        public boolean hasToken(int token) {
            return mTokens.indexOf(token) != -1;
        }
    }

    NewRollback createNewRollbackLocked(PackageInstaller.SessionInfo parentSession) {
        int rollbackId = allocateRollbackIdLocked();
        final RollbackData data;
        int parentSessionId = parentSession.getSessionId();

        if (parentSession.isStaged()) {
            data = mRollbackStore.createStagedRollback(rollbackId, parentSessionId);
        } else {
            data = mRollbackStore.createNonStagedRollback(rollbackId);
        }

        int[] packageSessionIds;
        if (parentSession.isMultiPackage()) {
            packageSessionIds = parentSession.getChildSessionIds();
        } else {
            packageSessionIds = new int[]{parentSessionId};
        }

        return new NewRollback(data, packageSessionIds);
    }

    /**
     * Returns the NewRollback associated with the given package session.
     * Returns null if no NewRollback is found for the given package
     * session.
     */
    NewRollback getNewRollbackForPackageSessionLocked(int packageSessionId) {
        // We expect mNewRollbacks to be a very small list; linear search
        // should be plenty fast.
        for (NewRollback newRollbackData : mNewRollbacks) {
            for (int id : newRollbackData.packageSessionIds) {
                if (id == packageSessionId) {
                    return newRollbackData;
                }
            }
        }
        return null;
    }
}
