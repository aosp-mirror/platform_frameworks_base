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

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.VersionedPackage;
import android.content.rollback.IRollbackManager;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.Installer;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Implementation of service that manages APK level rollbacks.
 */
class RollbackManagerServiceImpl extends IRollbackManager.Stub {

    private static final String TAG = "RollbackManager";

    // Rollbacks expire after 48 hours.
    // TODO: How to test rollback expiration works properly?
    private static final long ROLLBACK_LIFETIME_DURATION_MILLIS = 48 * 60 * 60 * 1000;

    // Lock used to synchronize accesses to in-memory rollback data
    // structures. By convention, methods with the suffix "Locked" require
    // mLock is held when they are called.
    private final Object mLock = new Object();

    // Used for generating rollback IDs.
    private final Random mRandom = new SecureRandom();

    // Set of allocated rollback ids
    @GuardedBy("mLock")
    private final SparseBooleanArray mAllocatedRollbackIds = new SparseBooleanArray();

    // Package rollback data for rollback-enabled installs that have not yet
    // been committed. Maps from sessionId to rollback data.
    @GuardedBy("mLock")
    private final Map<Integer, RollbackData> mPendingRollbacks = new HashMap<>();

    // Map from child session id's for enabled rollbacks to their
    // corresponding parent session ids.
    @GuardedBy("mLock")
    private final Map<Integer, Integer> mChildSessions = new HashMap<>();

    // Package rollback data available to be used for rolling back a package.
    // This list is null until the rollback data has been loaded.
    @GuardedBy("mLock")
    private List<RollbackData> mAvailableRollbacks;

    // The list of recently executed rollbacks.
    // This list is null until the rollback data has been loaded.
    @GuardedBy("mLock")
    private List<RollbackInfo> mRecentlyExecutedRollbacks;

    private final RollbackStore mRollbackStore;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Installer mInstaller;
    private final RollbackPackageHealthObserver mPackageHealthObserver;
    private final AppDataRollbackHelper mAppDataRollbackHelper;

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        // Note that we're calling onStart here because this object is only constructed on
        // SystemService#onStart.
        mInstaller = new Installer(mContext);
        mInstaller.onStart();
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();

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

        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
        packageInstaller.registerSessionCallback(new SessionCallback(), getHandler());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
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

        // NOTE: A new intent filter is being created here because this broadcast
        // doesn't use a data scheme ("package") like above.
        IntentFilter sessionUpdatedFilter = new IntentFilter();
        sessionUpdatedFilter.addAction(PackageInstaller.ACTION_SESSION_UPDATED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onStagedSessionUpdated(intent);
            }
        }, sessionUpdatedFilter, null, getHandler());

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
                    File newPackageCodePath = new File(intent.getData().getPath());

                    getHandler().post(() -> {
                        boolean success = enableRollback(installFlags, newPackageCodePath,
                                installedUsers);
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
    }

    @Override
    public ParceledListSlice getAvailableRollbacks() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getAvailableRollbacks");

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                RollbackData data = mAvailableRollbacks.get(i);
                if (data.isAvailable) {
                    rollbacks.add(new RollbackInfo(data.rollbackId,
                                data.packages, data.isStaged()));
                }
            }
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public ParceledListSlice<RollbackInfo> getRecentlyExecutedRollbacks() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getRecentlyExecutedRollbacks");

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            List<RollbackInfo> rollbacks = new ArrayList<>(mRecentlyExecutedRollbacks);
            return new ParceledListSlice<>(rollbacks);
        }
    }

    @Override
    public void commitRollback(int rollbackId, ParceledListSlice causePackages,
            String callerPackageName, IntentSender statusReceiver) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "executeRollback");

        final int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(callingUid, callerPackageName);

        getHandler().post(() ->
                commitRollbackInternal(rollbackId, causePackages.getList(),
                    callerPackageName, statusReceiver));
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
        if (data == null) {
            sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                    "Rollback unavailable");
            return;
        }

        if (data.inProgress) {
            sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                    "Rollback for package is already in progress.");
            return;
        }

        // Verify the RollbackData is up to date with what's installed on
        // device.
        // TODO: We assume that between now and the time we commit the
        // downgrade install, the currently installed package version does not
        // change. This is not safe to assume, particularly in the case of a
        // rollback racing with a roll-forward fix of a buggy package.
        // Figure out how to ensure we don't commit the rollback if
        // roll forward happens at the same time.
        for (PackageRollbackInfo info : data.packages) {
            VersionedPackage installedVersion = getInstalledPackageVersion(info.getPackageName());
            if (installedVersion == null) {
                // TODO: Test this case
                sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                        "Package to roll back is not installed");
                return;
            }

            if (!packageVersionsEqual(info.getVersionRolledBackFrom(), installedVersion)) {
                // TODO: Test this case
                sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_ROLLBACK_UNAVAILABLE,
                        "Package version to roll back not installed.");
                return;
            }
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
            parentParams.setAllowDowngrade(true);
            parentParams.setMultiPackage();
            if (data.isStaged()) {
                parentParams.setStaged();
            }

            int parentSessionId = packageInstaller.createSession(parentParams);
            PackageInstaller.Session parentSession = packageInstaller.openSession(parentSessionId);

            for (PackageRollbackInfo info : data.packages) {
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
                params.setAllowDowngrade(true);
                if (data.isStaged()) {
                    params.setStaged();
                }
                if (info.isApex()) {
                    params.setInstallAsApex();
                }
                int sessionId = packageInstaller.createSession(params);
                PackageInstaller.Session session = packageInstaller.openSession(sessionId);

                File packageCode = RollbackStore.getPackageCode(data, info.getPackageName());
                if (packageCode == null) {
                    sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE,
                            "Backup copy of package code inaccessible");
                    return;
                }

                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(packageCode,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        session.write(packageCode.getName(), 0, packageCode.length(), fd);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                parentSession.addChildSessionId(sessionId);
            }

            final LocalIntentReceiver receiver = new LocalIntentReceiver(
                    (Intent result) -> {
                        getHandler().post(() -> {
                            // We've now completed the rollback, so we mark it as no longer in
                            // progress.
                            data.inProgress = false;

                            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE);
                            if (status != PackageInstaller.STATUS_SUCCESS) {
                                sendFailure(statusReceiver, RollbackManager.STATUS_FAILURE_INSTALL,
                                        "Rollback downgrade install failed: "
                                        + result.getStringExtra(
                                                PackageInstaller.EXTRA_STATUS_MESSAGE));
                                return;
                            }

                            addRecentlyExecutedRollback(new RollbackInfo(
                                        data.rollbackId, data.packages, data.isStaged(),
                                        causePackages, parentSessionId));
                            sendSuccess(statusReceiver);

                            Intent broadcast = new Intent(Intent.ACTION_ROLLBACK_COMMITTED);

                            // TODO: This call emits the warning "Calling a method in the
                            // system process without a qualified user". Fix that.
                            // TODO: Limit this to receivers holding the
                            // MANAGE_ROLLBACKS permission?
                            mContext.sendBroadcast(broadcast);
                        });
                    }
            );

            data.inProgress = true;
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
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "reloadPersistedData");

        synchronized (mLock) {
            mAvailableRollbacks = null;
            mRecentlyExecutedRollbacks = null;
        }
        getHandler().post(() -> ensureRollbackDataLoaded());
    }

    @Override
    public void expireRollbackForPackage(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "expireRollbackForPackage");

        // TODO: Should this take a package version number in addition to
        // package name? For now, just remove all rollbacks matching the
        // package name. This method is only currently used to facilitate
        // testing anyway.
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                for (PackageRollbackInfo info : data.packages) {
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
            final ArrayList<String> pendingBackupPackages = new ArrayList<>();
            final Map<String, RestoreInfo> pendingRestorePackages = new HashMap<>();
            final List<RollbackData> changed;
            synchronized (mLock) {
                ensureRollbackDataLoadedLocked();
                changed = mAppDataRollbackHelper.computePendingBackupsAndRestores(userId,
                        pendingBackupPackages, pendingRestorePackages, mAvailableRollbacks,
                        mRecentlyExecutedRollbacks);
            }

            mAppDataRollbackHelper.commitPendingBackupAndRestoreForUser(userId,
                    pendingBackupPackages, pendingRestorePackages, changed);

            for (RollbackData rd : changed) {
                try {
                    mRollbackStore.saveAvailableRollback(rd);
                } catch (IOException ioe) {
                    Log.e(TAG, "Unable to save rollback info for : " + rd.rollbackId, ioe);
                }
            }

            synchronized (mLock) {
                mRollbackStore.saveRecentlyExecutedRollbacks(mRecentlyExecutedRollbacks);
            }
        });
    }

    void onBootCompleted() {
        getHandler().post(() -> {
            // Check to see if any staged sessions with rollback enabled have
            // been applied.
            List<RollbackData> staged = new ArrayList<>();
            synchronized (mLock) {
                ensureRollbackDataLoadedLocked();
                for (RollbackData data : mAvailableRollbacks) {
                    if (data.stagedSessionId != -1) {
                        staged.add(data);
                    }
                }
            }

            for (RollbackData data : staged) {
                PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionInfo session = installer.getSessionInfo(
                        data.stagedSessionId);
                if (session != null) {
                    if (session.isSessionApplied()) {
                        synchronized (mLock) {
                            data.isAvailable = true;
                        }
                        try {
                            mRollbackStore.saveAvailableRollback(data);
                        } catch (IOException ioe) {
                            Log.e(TAG, "Unable to save rollback info for : "
                                    + data.rollbackId, ioe);
                        }
                    } else if (session.isSessionFailed()) {
                        // TODO: Do we need to remove this from
                        // mAvailableRollbacks, or is it okay to leave as
                        // unavailable until the next reboot when it will go
                        // away on its own?
                        deleteRollback(data);
                    }
                }
            }
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
     * After calling this function, mAvailableRollbacks and
     * mRecentlyExecutedRollbacks will be non-null.
     */
    @GuardedBy("mLock")
    private void ensureRollbackDataLoadedLocked() {
        if (mAvailableRollbacks == null) {
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
        mAvailableRollbacks = mRollbackStore.loadAvailableRollbacks();
        for (RollbackData data : mAvailableRollbacks) {
            mAllocatedRollbackIds.put(data.rollbackId, true);
        }

        mRecentlyExecutedRollbacks = mRollbackStore.loadRecentlyExecutedRollbacks();
        for (RollbackInfo info : mRecentlyExecutedRollbacks) {
            mAllocatedRollbackIds.put(info.getRollbackId(), true);
        }

        scheduleExpiration(0);
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
            Iterator<RollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                for (PackageRollbackInfo info : data.packages) {
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

    /**
     * Called when a package has been completely removed from the device.
     * Removes all backups and rollback history for the given package.
     */
    private void onPackageFullyRemoved(String packageName) {
        expireRollbackForPackage(packageName);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackInfo> iter = mRecentlyExecutedRollbacks.iterator();
            boolean changed = false;
            while (iter.hasNext()) {
                RollbackInfo rollback = iter.next();
                for (PackageRollbackInfo info : rollback.getPackages()) {
                    if (packageName.equals(info.getPackageName())) {
                        iter.remove();
                        changed = true;
                        break;
                    }
                }
            }

            if (changed) {
                mRollbackStore.saveRecentlyExecutedRollbacks(mRecentlyExecutedRollbacks);
            }
        }
    }

    /**
     * Records that the given package has been recently rolled back.
     */
    private void addRecentlyExecutedRollback(RollbackInfo rollback) {
        // TODO: if the list of rollbacks gets too big, trim it to only those
        // that are necessary to keep track of.
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();

            // This should never happen because we can't have any pending backups left after
            // a rollback has been executed. See AppDataRollbackHelper#restoreAppData where we
            // clear all pending backups at the point of restore because they're guaranteed to be
            // no-ops.
            //
            // We may, however, have one or more pending restores left to handle.
            for (PackageRollbackInfo target : rollback.getPackages()) {
                if (target.getPendingBackups().size() > 0) {
                    Log.e(TAG, "No backups allowed to be pending for: " + target);
                    target.getPendingBackups().clear();
                }
            }

            mRecentlyExecutedRollbacks.add(rollback);
            mRollbackStore.saveRecentlyExecutedRollbacks(mRecentlyExecutedRollbacks);
        }
    }

    /**
     * Notifies an IntentSender of failure.
     *
     * @param statusReceiver where to send the failure
     * @param status the RollbackManager.STATUS_* code with the failure.
     * @param message the failure message.
     */
    private void sendFailure(IntentSender statusReceiver, int status, String message) {
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
    // TODO: Handle cases where the user changes time on the device.
    private void runExpiration() {
        Instant now = Instant.now();
        Instant oldest = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();

            Iterator<RollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                if (!data.isAvailable) {
                    continue;
                }

                if (!now.isBefore(data.timestamp.plusMillis(ROLLBACK_LIFETIME_DURATION_MILLIS))) {
                    iter.remove();
                    deleteRollback(data);
                } else if (oldest == null || oldest.isAfter(data.timestamp)) {
                    oldest = data.timestamp;
                }
            }
        }

        if (oldest != null) {
            scheduleExpiration(now.until(oldest.plusMillis(ROLLBACK_LIFETIME_DURATION_MILLIS),
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

    /**
     * Called via broadcast by the package manager when a package is being
     * staged for install with rollback enabled. Called before the package has
     * been installed.
     *
     * @param installFlags information about what is being installed.
     * @param newPackageCodePath path to the package about to be installed.
     * @param installedUsers the set of users for which a given package is installed.
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    private boolean enableRollback(int installFlags, File newPackageCodePath,
            int[] installedUsers) {

        // Find the session id associated with this install.
        // TODO: It would be nice if package manager or package installer told
        // us the session directly, rather than have to search for it
        // ourselves.
        PackageInstaller.SessionInfo session = null;

        int parentSessionId = -1;
        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo info : installer.getAllSessions()) {
            if (info.isMultiPackage()) {
                for (int childId : info.getChildSessionIds()) {
                    PackageInstaller.SessionInfo child = installer.getSessionInfo(childId);
                    if (sessionMatchesForEnableRollback(child, installFlags, newPackageCodePath)) {
                        // TODO: Check we only have one matching session?
                        parentSessionId = info.getSessionId();
                        session = child;
                        break;
                    }
                }
            } else if (sessionMatchesForEnableRollback(info, installFlags, newPackageCodePath)) {
                // TODO: Check we only have one matching session?
                parentSessionId = info.getSessionId();
                session = info;
                break;
            }
        }

        if (session == null) {
            Log.e(TAG, "Unable to find session id for enabled rollback.");
            return false;
        }

        // Check to see if this is the apk session for a staged session with
        // rollback enabled.
        // TODO: This check could be made more efficient.
        RollbackData rd = null;
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                RollbackData data = mAvailableRollbacks.get(i);
                if (data.apkSessionId == parentSessionId) {
                    rd = data;
                    break;
                }
            }
        }

        if (rd != null) {
            // This is the apk session for a staged session. We have already
            // backed up the apks, we just need to do user data backup.
            PackageParser.PackageLite newPackage = null;
            try {
                newPackage = PackageParser.parsePackageLite(
                        new File(session.resolvedBaseCodePath), 0);
            } catch (PackageParser.PackageParserException e) {
                Log.e(TAG, "Unable to parse new package", e);
                return false;
            }
            String packageName = newPackage.packageName;
            for (PackageRollbackInfo info : rd.packages) {
                if (info.getPackageName().equals(packageName)) {
                    AppDataRollbackHelper.SnapshotAppDataResult rs =
                            mAppDataRollbackHelper.snapshotAppData(packageName, installedUsers);
                    info.getPendingBackups().addAll(rs.pendingBackups);
                    for (int i = 0; i < rs.ceSnapshotInodes.size(); i++) {
                        info.putCeSnapshotInode(rs.ceSnapshotInodes.keyAt(i),
                                rs.ceSnapshotInodes.valueAt(i));
                    }
                    try {
                        mRollbackStore.saveAvailableRollback(rd);
                    } catch (IOException ioe) {
                        // TODO: Hopefully this is okay because we will try
                        // again to save the rollback when the staged session
                        // is applied. Just so long as the device doesn't
                        // reboot before then.
                        Log.e(TAG, "Unable to save rollback info for : " + rd.rollbackId, ioe);
                    }
                    return true;
                }
            }
            Log.e(TAG, "Unable to find package in apk session");
            return false;
        }

        return enableRollbackForSession(session, installedUsers, true);
    }

    /**
     * Do code and userdata backups to enable rollback of the given session.
     * In case of multiPackage sessions, <code>session</code> should be one of
     * the child sessions, not the parent session.
     */
    private boolean enableRollbackForSession(PackageInstaller.SessionInfo session,
            int[] installedUsers, boolean snapshotUserData) {
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

        VersionedPackage newVersion = new VersionedPackage(packageName, newPackage.versionCode);
        final boolean isApex = ((installFlags & PackageManager.INSTALL_APEX) != 0);

        // Get information about the currently installed package.
        PackageManager pm = mContext.getPackageManager();
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = pm.getPackageInfo(packageName, isApex ? PackageManager.MATCH_APEX : 0);
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: Support rolling back fresh package installs rather than
            // fail here. Test this case.
            Log.e(TAG, packageName + " is not installed");
            return false;
        }

        VersionedPackage installedVersion = new VersionedPackage(packageName,
                pkgInfo.getLongVersionCode());

        final AppDataRollbackHelper.SnapshotAppDataResult result;
        if (snapshotUserData && !isApex) {
            result = mAppDataRollbackHelper.snapshotAppData(packageName, installedUsers);
        } else {
            result = new AppDataRollbackHelper.SnapshotAppDataResult(IntArray.wrap(new int[0]),
                new SparseLongArray());
        }

        PackageRollbackInfo info = new PackageRollbackInfo(newVersion, installedVersion,
                result.pendingBackups, new ArrayList<>(), isApex, IntArray.wrap(installedUsers),
                result.ceSnapshotInodes);

        RollbackData data;
        try {
            int childSessionId = session.getSessionId();
            int parentSessionId = session.getParentSessionId();
            if (parentSessionId == PackageInstaller.SessionInfo.INVALID_ID) {
                parentSessionId = childSessionId;
            }

            synchronized (mLock) {
                // TODO: no need to add to mChildSessions if childSessionId is
                // the same as parentSessionId.
                mChildSessions.put(childSessionId, parentSessionId);
                data = mPendingRollbacks.get(parentSessionId);
                if (data == null) {
                    int rollbackId = allocateRollbackIdLocked();
                    if (session.isStaged()) {
                        data = mRollbackStore.createPendingStagedRollback(rollbackId,
                                parentSessionId);
                    } else {
                        data = mRollbackStore.createAvailableRollback(rollbackId);
                    }
                    mPendingRollbacks.put(parentSessionId, data);
                }
                data.packages.add(info);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to create rollback for " + packageName, e);
            return false;
        }

        try {
            RollbackStore.backupPackageCode(data, packageName, pkgInfo.applicationInfo.sourceDir);
        } catch (IOException e) {
            Log.e(TAG, "Unable to copy package for rollback for " + packageName, e);
            return false;
        }
        return true;
    }

    @Override
    public void restoreUserData(String packageName, int[] userIds, int appId, long ceDataInode,
            String seInfo, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("restoureUserData may only be called by the system.");
        }

        getHandler().post(() -> {
            final RollbackData rollbackData = getRollbackForPackage(packageName);
            for (int userId : userIds) {
                final boolean changedRollbackData = mAppDataRollbackHelper.restoreAppData(
                        packageName, rollbackData, userId, appId, ceDataInode, seInfo);
                // We've updated metadata about this rollback, so save it to flash.
                if (changedRollbackData) {
                    try {
                        mRollbackStore.saveAvailableRollback(rollbackData);
                    } catch (IOException ioe) {
                        // TODO(narayan): What is the right thing to do here ? This isn't a fatal
                        // error, since it will only result in us trying to restore data again,
                        // which will be a no-op if there's no data available.
                        Log.e(TAG, "Unable to save available rollback: " + packageName, ioe);
                    }
                }
            }

            final PackageManagerInternal pmi = LocalServices.getService(
                    PackageManagerInternal.class);
            pmi.finishPackageInstall(token, false);
        });
    }

    @Override
    public boolean notifyStagedSession(int sessionId) {
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

            if (!session.isMultiPackage()) {
                if (!enableRollbackForSession(session, null, false)) {
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
                    if (!enableRollbackForSession(childSession, null, false)) {
                        Log.e(TAG, "Unable to enable rollback for session: " + sessionId);
                        result.offer(false);
                        return;
                    }
                }
            }

            result.offer(true);
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
        getHandler().post(() -> {
            RollbackData rd = null;
            synchronized (mLock) {
                ensureRollbackDataLoadedLocked();
                for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                    RollbackData data = mAvailableRollbacks.get(i);
                    if (data.stagedSessionId == originalSessionId) {
                        data.apkSessionId = apkSessionId;
                        rd = data;
                        break;
                    }
                }
            }

            if (rd != null) {
                try {
                    mRollbackStore.saveAvailableRollback(rd);
                } catch (IOException ioe) {
                    Log.e(TAG, "Unable to save rollback info for : " + rd.rollbackId, ioe);
                }
            }
        });
    }

    /**
     * Gets the version of the package currently installed.
     * Returns null if the package is not currently installed.
     */
    private VersionedPackage getInstalledPackageVersion(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        return new VersionedPackage(packageName, pkgInfo.getLongVersionCode());
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
            // If sessionId refers to a staged session, we can't deal with it here since the
            // session might take an unbounded amount of time to become "ready" after the package
            // installer session is committed. In those cases, we respond to it in response to
            // a session ready broadcast.
            PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo si = packageInstaller.getSessionInfo(sessionId);
            if (si != null && si.isStaged()) {
                return;
            }

            completeEnableRollback(sessionId, success);
        }
    }

    private void completeEnableRollback(int sessionId, boolean success) {
        RollbackData data = null;
        synchronized (mLock) {
            Integer parentSessionId = mChildSessions.remove(sessionId);
            if (parentSessionId != null) {
                sessionId = parentSessionId;
            }

            data = mPendingRollbacks.remove(sessionId);
        }

        if (data != null) {
            if (success) {
                try {
                    data.timestamp = Instant.now();

                    mRollbackStore.saveAvailableRollback(data);
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
                        mAvailableRollbacks.add(data);
                    }
                    // TODO(zezeozue): Provide API to explicitly start observing instead
                    // of doing this for all rollbacks. If we do this for all rollbacks,
                    // should document in PackageInstaller.SessionParams#setEnableRollback
                    // After enabling and commiting any rollback, observe packages and
                    // prepare to rollback if packages crashes too frequently.
                    List<String> packages = new ArrayList<>();
                    for (int i = 0; i < data.packages.size(); i++) {
                        packages.add(data.packages.get(i).getPackageName());
                    }
                    mPackageHealthObserver.startObservingHealth(packages,
                            ROLLBACK_LIFETIME_DURATION_MILLIS);
                    scheduleExpiration(ROLLBACK_LIFETIME_DURATION_MILLIS);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to enable rollback", e);
                    deleteRollback(data);
                }
            } else {
                // The install session was aborted, clean up the pending
                // install.
                deleteRollback(data);
            }
        }
    }

    private void onStagedSessionUpdated(Intent intent) {
        PackageInstaller.SessionInfo pi = intent.getParcelableExtra(PackageInstaller.EXTRA_SESSION);
        if (pi == null) {
            Log.e(TAG, "Missing intent extra: " + PackageInstaller.EXTRA_SESSION);
            return;
        }

        if (pi.isStaged()) {
            if (!pi.isSessionFailed()) {
                // TODO: The session really isn't "enabled" at this point, since more work might
                // be required post reboot.
                // TODO: We need to make this case consistent with the call from onFinished.
                //  Ideally, we'd call completeEnableRollback excatly once per multi-package session
                //  with the parentSessionId only.
                completeEnableRollback(pi.sessionId, pi.isSessionReady());
            } else {
                // TODO: Clean up the saved rollback when the session fails. This may need to be
                // unified with the case where things fail post reboot.
            }
        } else {
            Log.e(TAG, "Received onStagedSessionUpdated for: " + pi.sessionId
                    + ", which isn't staged");
        }
    }

    /*
     * Returns the RollbackData, if any, for an available rollback that would
     * roll back the given package. Note: This assumes we have at most one
     * available rollback for a given package at any one time.
     */
    private RollbackData getRollbackForPackage(String packageName) {
        synchronized (mLock) {
            // TODO: Have ensureRollbackDataLoadedLocked return the list of
            // available rollbacks, to hopefully avoid forgetting to call it?
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                RollbackData data = mAvailableRollbacks.get(i);
                if (data.isAvailable && getPackageRollbackInfo(data, packageName) != null) {
                    return data;
                }
            }
        }
        return null;
    }

    /*
     * Returns the RollbackData, if any, for an available rollback with the
     * given rollbackId.
     */
    private RollbackData getRollbackForId(int rollbackId) {
        synchronized (mLock) {
            // TODO: Have ensureRollbackDataLoadedLocked return the list of
            // available rollbacks, to hopefully avoid forgetting to call it?
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                RollbackData data = mAvailableRollbacks.get(i);
                if (data.isAvailable && data.rollbackId == rollbackId) {
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
    static PackageRollbackInfo getPackageRollbackInfo(RollbackData data,
            String packageName) {
        for (PackageRollbackInfo info : data.packages) {
            if (info.getPackageName().equals(packageName)) {
                return info;
            }
        }

        return null;
    }

    @GuardedBy("mLock")
    private int allocateRollbackIdLocked() throws IOException {
        int n = 0;
        int rollbackId;
        do {
            rollbackId = mRandom.nextInt(Integer.MAX_VALUE - 1) + 1;
            if (!mAllocatedRollbackIds.get(rollbackId, false)) {
                mAllocatedRollbackIds.put(rollbackId, true);
                return rollbackId;
            }
        } while (n++ < 32);

        throw new IOException("Failed to allocate rollback ID");
    }

    private void deleteRollback(RollbackData rollbackData) {
        for (PackageRollbackInfo info : rollbackData.packages) {
            IntArray installedUsers = info.getInstalledUsers();
            SparseLongArray ceSnapshotInodes = info.getCeSnapshotInodes();
            for (int i = 0; i < installedUsers.size(); i++) {
                int userId = installedUsers.get(i);
                mAppDataRollbackHelper.destroyAppDataSnapshot(info.getPackageName(), userId,
                        ceSnapshotInodes.get(userId, 0));
            }
        }
        mRollbackStore.deleteAvailableRollback(rollbackData);
    }
}
