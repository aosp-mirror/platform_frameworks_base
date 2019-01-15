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
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.StringParceledListSlice;
import android.content.rollback.IRollbackManager;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerServiceUtils;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    // Data for available rollbacks and recently executed rollbacks is
    // persisted in storage. Assuming the rollback data directory is
    // /data/rollback, we use the following directory structure
    // to store this data:
    //   /data/rollback/
    //      available/
    //          XXX/
    //              com.package.A/
    //                  base.apk
    //                  info.json
    //              enabled.txt
    //          YYY/
    //              com.package.B/
    //                  base.apk
    //                  info.json
    //              enabled.txt
    //      recently_executed.json
    //
    // * XXX, YYY are random strings from Files.createTempDirectory
    // * info.json contains the package version to roll back from/to.
    // * enabled.txt contains a timestamp for when the rollback was first
    //   made available. This file is not written until the rollback is made
    //   available.
    //
    // TODO: Use AtomicFile for all the .json files?
    private final File mRollbackDataDir;
    private final File mAvailableRollbacksDir;
    private final File mRecentlyExecutedRollbacksFile;

    private final Context mContext;
    private final HandlerThread mHandlerThread;

    RollbackManagerServiceImpl(Context context) {
        mContext = context;
        mHandlerThread = new HandlerThread("RollbackManagerServiceHandler");
        mHandlerThread.start();

        mRollbackDataDir = new File(Environment.getDataDirectory(), "rollback");
        mAvailableRollbacksDir = new File(mRollbackDataDir, "available");
        mRecentlyExecutedRollbacksFile = new File(mRollbackDataDir, "recently_executed.json");

        // Kick off loading of the rollback data from strorage in a background
        // thread.
        // TODO: Consider loading the rollback data directly here instead, to
        // avoid the need to call ensureRollbackDataLoaded every time before
        // accessing the rollback data?
        // TODO: Test that this kicks off initial scheduling of rollback
        // expiration.
        getHandler().post(() -> ensureRollbackDataLoaded());

        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        installer.registerSessionCallback(new SessionCallback(), getHandler());

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
                    File newPackageCodePath = new File(intent.getData().getPath());

                    getHandler().post(() -> {
                        boolean success = enableRollback(installFlags, newPackageCodePath);
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
    public RollbackInfo getAvailableRollback(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getAvailableRollback");

        RollbackData data = getRollbackForPackage(packageName);
        if (data == null) {
            return null;
        }

        // Note: The rollback for the package ought to be for the currently
        // installed version, otherwise the rollback data is out of date. In
        // that rare case, we'll check when we execute the rollback whether
        // it's out of date or not, so no need to check package versions here.

        for (PackageRollbackInfo info : data.packages) {
            if (info.packageName.equals(packageName)) {
                // TODO: Once the RollbackInfo API supports info about
                // dependant packages, add that info here.
                return new RollbackInfo(info);
            }
        }
        return null;
    }

    @Override
    public StringParceledListSlice getPackagesWithAvailableRollbacks() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "getPackagesWithAvailableRollbacks");

        final Set<String> packageNames = new HashSet<>();
        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            for (int i = 0; i < mAvailableRollbacks.size(); ++i) {
                RollbackData data = mAvailableRollbacks.get(i);
                for (PackageRollbackInfo info : data.packages) {
                    packageNames.add(info.packageName);
                }
            }
        }
        return new StringParceledListSlice(new ArrayList<>(packageNames));
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
    public void executeRollback(RollbackInfo rollback, String callerPackageName,
            IntentSender statusReceiver) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ROLLBACKS,
                "executeRollback");

        final int callingUid = Binder.getCallingUid();
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(callingUid, callerPackageName);

        getHandler().post(() ->
                executeRollbackInternal(rollback, callerPackageName, statusReceiver));
    }

    /**
     * Performs the actual work to execute a rollback.
     * The work is done on the current thread. This may be a long running
     * operation.
     */
    private void executeRollbackInternal(RollbackInfo rollback,
            String callerPackageName, IntentSender statusReceiver) {
        String targetPackageName = rollback.targetPackage.packageName;
        Log.i(TAG, "Initiating rollback of " + targetPackageName);

        // Get the latest RollbackData for the target package.
        RollbackData data = getRollbackForPackage(targetPackageName);
        if (data == null) {
            sendFailure(statusReceiver, "No rollback available for package.");
            return;
        }

        // Verify the latest rollback matches the version requested.
        // TODO: Check dependant packages too once RollbackInfo includes that
        // information.
        for (PackageRollbackInfo info : data.packages) {
            if (info.packageName.equals(targetPackageName)
                    && !rollback.targetPackage.higherVersion.equals(info.higherVersion)) {
                sendFailure(statusReceiver, "Rollback is out of date.");
                return;
            }
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
            PackageRollbackInfo.PackageVersion installedVersion =
                    getInstalledPackageVersion(info.packageName);
            if (installedVersion == null) {
                // TODO: Test this case
                sendFailure(statusReceiver, "Package to roll back is not installed");
                return;
            }

            if (!info.higherVersion.equals(installedVersion)) {
                // TODO: Test this case
                sendFailure(statusReceiver, "Package version to roll back not installed.");
                return;
            }
        }

        // Get a context for the caller to use to install the downgraded
        // version of the package.
        Context context = null;
        try {
            context = mContext.createPackageContext(callerPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            sendFailure(statusReceiver, "Invalid callerPackageName");
            return;
        }

        PackageManager pm = context.getPackageManager();
        try {
            PackageInstaller packageInstaller = pm.getPackageInstaller();
            PackageInstaller.SessionParams parentParams = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setAllowDowngrade(true);
            parentParams.setMultiPackage();
            int parentSessionId = packageInstaller.createSession(parentParams);
            PackageInstaller.Session parentSession = packageInstaller.openSession(parentSessionId);

            for (PackageRollbackInfo info : data.packages) {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAllowDowngrade(true);
                int sessionId = packageInstaller.createSession(params);
                PackageInstaller.Session session = packageInstaller.openSession(sessionId);

                // TODO: Will it always be called "base.apk"? What about splits?
                // What about apex?
                File packageDir = new File(data.backupDir, info.packageName);
                File baseApk = new File(packageDir, "base.apk");
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(baseApk,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                    final long token = Binder.clearCallingIdentity();
                    try {
                        session.write("base.apk", 0, baseApk.length(), fd);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
                parentSession.addChildSessionId(sessionId);
            }

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            parentSession.commit(receiver.getIntentSender());

            Intent result = receiver.getResult();
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status != PackageInstaller.STATUS_SUCCESS) {
                sendFailure(statusReceiver, "Rollback downgrade install failed: "
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                return;
            }

            addRecentlyExecutedRollback(rollback);
            sendSuccess(statusReceiver);

            Intent broadcast = new Intent(Intent.ACTION_PACKAGE_ROLLBACK_EXECUTED,
                    Uri.fromParts("package", targetPackageName,
                        Manifest.permission.MANAGE_ROLLBACKS));

            // TODO: This call emits the warning "Calling a method in the
            // system process without a qualified user". Fix that.
            mContext.sendBroadcast(broadcast);
        } catch (IOException e) {
            Log.e(TAG, "Unable to roll back " + targetPackageName, e);
            sendFailure(statusReceiver, "IOException: " + e.toString());
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
                    if (info.packageName.equals(packageName)) {
                        iter.remove();
                        removeFile(data.backupDir);
                        break;
                    }
                }
            }
        }
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
            loadRollbackDataLocked();
        }
    }

    /**
     * Load rollback data from storage.
     * Note: We do potentially heavy IO here while holding mLock, because we
     * have to have the rollback data loaded before we can do anything else
     * meaningful.
     */
    @GuardedBy("mLock")
    private void loadRollbackDataLocked() {
        mAvailableRollbacksDir.mkdirs();
        mAvailableRollbacks = new ArrayList<>();
        for (File rollbackDir : mAvailableRollbacksDir.listFiles()) {
            File enabledFile = new File(rollbackDir, "enabled.txt");
            // TODO: Delete any directories without an enabled.txt? That could
            // potentially delete pending rollback data if reloadPersistedData
            // is called, though there's no reason besides testing for that to
            // be called.
            if (rollbackDir.isDirectory() && enabledFile.isFile()) {
                RollbackData data = new RollbackData(rollbackDir);
                try {
                    PackageRollbackInfo info = null;
                    for (File packageDir : rollbackDir.listFiles()) {
                        if (packageDir.isDirectory()) {
                            File jsonFile = new File(packageDir, "info.json");
                            String jsonString = IoUtils.readFileAsString(
                                    jsonFile.getAbsolutePath());
                            JSONObject jsonObject = new JSONObject(jsonString);
                            String packageName = jsonObject.getString("packageName");
                            long higherVersionCode = jsonObject.getLong("higherVersionCode");
                            long lowerVersionCode = jsonObject.getLong("lowerVersionCode");

                            data.packages.add(new PackageRollbackInfo(packageName,
                                        new PackageRollbackInfo.PackageVersion(higherVersionCode),
                                        new PackageRollbackInfo.PackageVersion(lowerVersionCode)));
                        }
                    }

                    if (data.packages.isEmpty()) {
                        throw new IOException("No package rollback info found");
                    }

                    String enabledString = IoUtils.readFileAsString(enabledFile.getAbsolutePath());
                    data.timestamp = Instant.parse(enabledString.trim());
                    mAvailableRollbacks.add(data);
                } catch (IOException | JSONException | DateTimeParseException e) {
                    Log.e(TAG, "Unable to read rollback data at " + rollbackDir, e);
                    removeFile(rollbackDir);
                }
            }
        }

        mRecentlyExecutedRollbacks = new ArrayList<>();
        if (mRecentlyExecutedRollbacksFile.exists()) {
            try {
                // TODO: How to cope with changes to the format of this file from
                // when RollbackStore is updated in the future?
                String jsonString = IoUtils.readFileAsString(
                        mRecentlyExecutedRollbacksFile.getAbsolutePath());
                JSONObject object = new JSONObject(jsonString);
                JSONArray array = object.getJSONArray("recentlyExecuted");
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject element = array.getJSONObject(i);
                    String packageName = element.getString("packageName");
                    long higherVersionCode = element.getLong("higherVersionCode");
                    long lowerVersionCode = element.getLong("lowerVersionCode");
                    PackageRollbackInfo target = new PackageRollbackInfo(packageName,
                            new PackageRollbackInfo.PackageVersion(higherVersionCode),
                            new PackageRollbackInfo.PackageVersion(lowerVersionCode));
                    RollbackInfo rollback = new RollbackInfo(target);
                    mRecentlyExecutedRollbacks.add(rollback);
                }
            } catch (IOException | JSONException e) {
                // TODO: What to do here? Surely we shouldn't just forget about
                // everything after the point of exception?
                Log.e(TAG, "Failed to read recently executed rollbacks", e);
            }
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
        PackageRollbackInfo.PackageVersion installedVersion =
                getInstalledPackageVersion(packageName);

        synchronized (mLock) {
            ensureRollbackDataLoadedLocked();
            Iterator<RollbackData> iter = mAvailableRollbacks.iterator();
            while (iter.hasNext()) {
                RollbackData data = iter.next();
                for (PackageRollbackInfo info : data.packages) {
                    if (info.packageName.equals(packageName)
                            && !info.higherVersion.equals(installedVersion)) {
                        iter.remove();
                        removeFile(data.backupDir);
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
                if (packageName.equals(rollback.targetPackage.packageName)) {
                    iter.remove();
                    changed = true;
                }
            }

            if (changed) {
                saveRecentlyExecutedRollbacksLocked();
            }
        }
    }

    /**
     * Write the list of recently executed rollbacks to storage.
     * Note: This happens while mLock is held, which should be okay because we
     * expect executed rollbacks to be modified only in exceptional cases.
     */
    @GuardedBy("mLock")
    private void saveRecentlyExecutedRollbacksLocked() {
        try {
            JSONObject json = new JSONObject();
            JSONArray array = new JSONArray();
            json.put("recentlyExecuted", array);

            for (int i = 0; i < mRecentlyExecutedRollbacks.size(); ++i) {
                RollbackInfo rollback = mRecentlyExecutedRollbacks.get(i);
                JSONObject element = new JSONObject();
                element.put("packageName", rollback.targetPackage.packageName);
                element.put("higherVersionCode", rollback.targetPackage.higherVersion.versionCode);
                element.put("lowerVersionCode", rollback.targetPackage.lowerVersion.versionCode);
                array.put(element);
            }

            PrintWriter pw = new PrintWriter(mRecentlyExecutedRollbacksFile);
            pw.println(json.toString());
            pw.close();
        } catch (IOException | JSONException e) {
            // TODO: What to do here?
            Log.e(TAG, "Failed to save recently executed rollbacks", e);
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
            mRecentlyExecutedRollbacks.add(rollback);
            saveRecentlyExecutedRollbacksLocked();
        }
    }

    /**
     * Notifies an IntentSender of failure.
     *
     * @param statusReceiver where to send the failure
     * @param message the failure message.
     */
    private void sendFailure(IntentSender statusReceiver, String message) {
        Log.e(TAG, message);
        try {
            // TODO: More context on which rollback failed?
            // TODO: More refined failure code?
            final Intent fillIn = new Intent();
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
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
            fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
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
                if (!now.isBefore(data.timestamp.plusMillis(ROLLBACK_LIFETIME_DURATION_MILLIS))) {
                    iter.remove();
                    removeFile(data.backupDir);
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
     * @param id the id of the enable rollback request
     * @param installFlags information about what is being installed.
     * @param newPackageCodePath path to the package about to be installed.
     * @return true if enabling the rollback succeeds, false otherwise.
     */
    private boolean enableRollback(int installFlags, File newPackageCodePath) {
        if ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            Log.e(TAG, "Rollbacks not supported for instant app install");
            return false;
        }
        if ((installFlags & PackageManager.INSTALL_APEX) != 0) {
            Log.e(TAG, "Rollbacks not supported for apex install");
            return false;
        }

        // Get information about the package to be installed.
        PackageParser.PackageLite newPackage = null;
        try {
            newPackage = PackageParser.parsePackageLite(newPackageCodePath, 0);
        } catch (PackageParser.PackageParserException e) {
            Log.e(TAG, "Unable to parse new package", e);
            return false;
        }

        String packageName = newPackage.packageName;
        Log.i(TAG, "Enabling rollback for install of " + packageName);

        // Figure out the session id associated with this install.
        int parentSessionId = PackageInstaller.SessionInfo.INVALID_ID;
        int childSessionId = PackageInstaller.SessionInfo.INVALID_ID;
        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo info : installer.getAllSessions()) {
            if (info.isMultiPackage()) {
                for (int childId : info.getChildSessionIds()) {
                    PackageInstaller.SessionInfo child = installer.getSessionInfo(childId);
                    if (sessionMatchesForEnableRollback(child, installFlags, newPackageCodePath)) {
                        // TODO: Check we only have one matching session?
                        parentSessionId = info.getSessionId();
                        childSessionId = childId;
                    }
                }
            } else {
                if (sessionMatchesForEnableRollback(info, installFlags, newPackageCodePath)) {
                    // TODO: Check we only have one matching session?
                    parentSessionId = info.getSessionId();
                    childSessionId = parentSessionId;
                }
            }
        }

        if (parentSessionId == PackageInstaller.SessionInfo.INVALID_ID) {
            Log.e(TAG, "Unable to find session id for enabled rollback.");
            return false;
        }

        PackageRollbackInfo.PackageVersion newVersion =
                new PackageRollbackInfo.PackageVersion(newPackage.versionCode);

        // Get information about the currently installed package.
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        PackageParser.Package installedPackage = pm.getPackage(packageName);
        if (installedPackage == null) {
            // TODO: Support rolling back fresh package installs rather than
            // fail here. Test this case.
            Log.e(TAG, packageName + " is not installed");
            return false;
        }
        PackageRollbackInfo.PackageVersion installedVersion =
                new PackageRollbackInfo.PackageVersion(installedPackage.getLongVersionCode());

        PackageRollbackInfo info = new PackageRollbackInfo(
                packageName, newVersion, installedVersion);

        RollbackData data;
        try {
            synchronized (mLock) {
                mChildSessions.put(childSessionId, parentSessionId);
                data = mPendingRollbacks.get(parentSessionId);
                if (data == null) {
                    File backupDir = Files.createTempDirectory(
                            mAvailableRollbacksDir.toPath(), null).toFile();
                    data = new RollbackData(backupDir);
                    mPendingRollbacks.put(parentSessionId, data);
                }
                data.packages.add(info);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to create rollback for " + packageName, e);
            return false;
        }

        File packageDir = new File(data.backupDir, packageName);
        packageDir.mkdirs();
        try {
            JSONObject json = new JSONObject();
            json.put("packageName", packageName);
            json.put("higherVersionCode", newVersion.versionCode);
            json.put("lowerVersionCode", installedVersion.versionCode);

            File jsonFile = new File(packageDir, "info.json");
            PrintWriter pw = new PrintWriter(jsonFile);
            pw.println(json.toString());
            pw.close();
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Unable to create rollback for " + packageName, e);
            removeFile(packageDir);
            return false;
        }

        // TODO: Copy by hard link instead to save on cpu and storage space?
        int status = PackageManagerServiceUtils.copyPackage(installedPackage.codePath, packageDir);
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            Log.e(TAG, "Unable to copy package for rollback for " + packageName);
            removeFile(packageDir);
            return false;
        }

        return true;
    }

    // TODO: Don't copy this from PackageManagerShellCommand like this?
    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Deletes a file completely.
     * If the file is a directory, its contents are deleted as well.
     * Has no effect if the directory does not exist.
     */
    private void removeFile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                removeFile(child);
            }
        }
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * Gets the version of the package currently installed.
     * Returns null if the package is not currently installed.
     */
    private PackageRollbackInfo.PackageVersion getInstalledPackageVersion(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = pm.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        return new PackageRollbackInfo.PackageVersion(pkgInfo.getLongVersionCode());
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
                        File enabledFile = new File(data.backupDir, "enabled.txt");
                        PrintWriter pw = new PrintWriter(enabledFile);
                        pw.println(data.timestamp.toString());
                        pw.close();

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

                        scheduleExpiration(ROLLBACK_LIFETIME_DURATION_MILLIS);
                    } catch (IOException e) {
                        Log.e(TAG, "Unable to enable rollback", e);
                        removeFile(data.backupDir);
                    }
                } else {
                    // The install session was aborted, clean up the pending
                    // install.
                    removeFile(data.backupDir);
                }
            }
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
                for (PackageRollbackInfo info : data.packages) {
                    if (info.packageName.equals(packageName)) {
                        return data;
                    }
                }
            }
        }
        return null;
    }
}
