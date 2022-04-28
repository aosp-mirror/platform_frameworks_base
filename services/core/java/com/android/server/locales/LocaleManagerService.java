/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locales;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ILocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;

/**
 * The implementation of ILocaleManager.aidl.
 *
 * <p>This service is API entry point for storing app-specific UI locales
 */
public class LocaleManagerService extends SystemService {
    private static final String TAG = "LocaleManagerService";
    final Context mContext;
    private final LocaleManagerService.LocaleManagerBinderService mBinderService;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private PackageManager mPackageManager;

    private LocaleManagerBackupHelper mBackupHelper;

    private final PackageMonitor mPackageMonitor;

    public static final boolean DEBUG = false;

    public LocaleManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new LocaleManagerBinderService();
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManager = mContext.getPackageManager();

        HandlerThread broadcastHandlerThread = new HandlerThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND);
        broadcastHandlerThread.start();

        SystemAppUpdateTracker systemAppUpdateTracker =
                new SystemAppUpdateTracker(this);
        broadcastHandlerThread.getThreadHandler().postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                systemAppUpdateTracker.init();
            }
        });

        mBackupHelper = new LocaleManagerBackupHelper(this,
                mPackageManager, broadcastHandlerThread);

        mPackageMonitor = new LocaleManagerServicePackageMonitor(mBackupHelper,
                systemAppUpdateTracker);
        mPackageMonitor.register(context, broadcastHandlerThread.getLooper(),
                UserHandle.ALL,
                true);
    }

    @VisibleForTesting
    LocaleManagerService(Context context, ActivityTaskManagerInternal activityTaskManagerInternal,
            ActivityManagerInternal activityManagerInternal,
            PackageManager packageManager,
            LocaleManagerBackupHelper localeManagerBackupHelper,
            PackageMonitor packageMonitor) {
        super(context);
        mContext = context;
        mBinderService = new LocaleManagerBinderService();
        mActivityTaskManagerInternal = activityTaskManagerInternal;
        mActivityManagerInternal = activityManagerInternal;
        mPackageManager = packageManager;
        mBackupHelper = localeManagerBackupHelper;
        mPackageMonitor = packageMonitor;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LOCALE_SERVICE, mBinderService);
        LocalServices.addService(LocaleManagerInternal.class, new LocaleManagerInternalImpl());
    }

    private final class LocaleManagerInternalImpl extends LocaleManagerInternal {

        @Override
        public @Nullable byte[] getBackupPayload(int userId) {
            checkCallerIsSystem();
            return mBackupHelper.getBackupPayload(userId);
        }

        @Override
        public void stageAndApplyRestoredPayload(byte[] payload, int userId) {
            mBackupHelper.stageAndApplyRestoredPayload(payload, userId);
        }

        private void checkCallerIsSystem() {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Caller is not system.");
            }
        }
    }

    private final class LocaleManagerBinderService extends ILocaleManager.Stub {
        @Override
        public void setApplicationLocales(@NonNull String appPackageName, @UserIdInt int userId,
                @NonNull LocaleList locales) throws RemoteException {
            LocaleManagerService.this.setApplicationLocales(appPackageName, userId, locales);
        }

        @Override
        @NonNull
        public LocaleList getApplicationLocales(@NonNull String appPackageName,
                @UserIdInt int userId) throws RemoteException {
            return LocaleManagerService.this.getApplicationLocales(appPackageName, userId);
        }

        @Override
        @NonNull
        public LocaleList getSystemLocales() throws RemoteException {
            return LocaleManagerService.this.getSystemLocales();
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new LocaleManagerShellCommand(mBinderService))
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

    }

    /**
     * Sets the current UI locales for a specified app.
     */
    public void setApplicationLocales(@NonNull String appPackageName, @UserIdInt int userId,
            @NonNull LocaleList locales) throws RemoteException, IllegalArgumentException {
        AppLocaleChangedAtomRecord atomRecordForMetrics = new
                AppLocaleChangedAtomRecord(Binder.getCallingUid());
        try {
            requireNonNull(appPackageName);
            requireNonNull(locales);
            atomRecordForMetrics.setNewLocales(locales.toLanguageTags());
            //Allow apps with INTERACT_ACROSS_USERS permission to set locales for different user.
            userId = mActivityManagerInternal.handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId,
                    false /* allowAll */, ActivityManagerInternal.ALLOW_NON_FULL,
                    "setApplicationLocales", /* callerPackage= */ null);

            // This function handles two types of set operations:
            // 1.) A normal, non-privileged app setting its own locale.
            // 2.) A privileged system service setting locales of another package.
            // The least privileged case is a normal app performing a set, so check that first and
            // set locales if the package name is owned by the app. Next, check if the caller has
            // the necessary permission and set locales.
            boolean isCallerOwner = isPackageOwnedByCaller(appPackageName, userId,
                    atomRecordForMetrics);
            if (!isCallerOwner) {
                enforceChangeConfigurationPermission(atomRecordForMetrics);
            }

            final long token = Binder.clearCallingIdentity();
            try {
                setApplicationLocalesUnchecked(appPackageName, userId, locales,
                        atomRecordForMetrics);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            logMetric(atomRecordForMetrics);
        }
    }

    private void setApplicationLocalesUnchecked(@NonNull String appPackageName,
            @UserIdInt int userId, @NonNull LocaleList locales,
            @NonNull AppLocaleChangedAtomRecord atomRecordForMetrics) {
        if (DEBUG) {
            Slog.d(TAG, "setApplicationLocales: setting locales for package " + appPackageName
                    + " and user " + userId);
        }

        atomRecordForMetrics.setPrevLocales(getApplicationLocalesUnchecked(appPackageName, userId)
                .toLanguageTags());
        final ActivityTaskManagerInternal.PackageConfigurationUpdater updater =
                mActivityTaskManagerInternal.createPackageConfigurationUpdater(appPackageName,
                        userId);
        boolean isConfigChanged = updater.setLocales(locales).commit();

        //We want to send the broadcasts only if config was actually updated on commit.
        if (isConfigChanged) {
            notifyAppWhoseLocaleChanged(appPackageName, userId, locales);
            notifyInstallerOfAppWhoseLocaleChanged(appPackageName, userId, locales);
            notifyRegisteredReceivers(appPackageName, userId, locales);

            mBackupHelper.notifyBackupManager();
            atomRecordForMetrics.setStatus(
                    FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__STATUS__CONFIG_COMMITTED);
        } else {
            atomRecordForMetrics.setStatus(FrameworkStatsLog
                    .APPLICATION_LOCALES_CHANGED__STATUS__CONFIG_UNCOMMITTED);
        }
    }

    /**
     * Sends an implicit broadcast with action
     * {@link android.content.Intent#ACTION_APPLICATION_LOCALE_CHANGED}
     * to receivers with {@link android.Manifest.permission#READ_APP_SPECIFIC_LOCALES}.
     */
    private void notifyRegisteredReceivers(String appPackageName, int userId,
            LocaleList locales) {
        Intent intent = createBaseIntent(Intent.ACTION_APPLICATION_LOCALE_CHANGED,
                appPackageName, locales);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId),
                Manifest.permission.READ_APP_SPECIFIC_LOCALES);
    }

    /**
     * Sends an explicit broadcast with action
     * {@link android.content.Intent#ACTION_APPLICATION_LOCALE_CHANGED} to
     * the installer (as per {@link android.content.pm.InstallSourceInfo#getInstallingPackageName})
     * of app whose locale has changed.
     *
     * <p><b>Note:</b> This is can be used by installers to deal with cases such as
     * language-based APK Splits.
     */
    void notifyInstallerOfAppWhoseLocaleChanged(String appPackageName, int userId,
            LocaleList locales) {
        String installingPackageName = getInstallingPackageName(appPackageName);
        if (installingPackageName != null) {
            Intent intent = createBaseIntent(Intent.ACTION_APPLICATION_LOCALE_CHANGED,
                    appPackageName, locales);
            //Set package name to ensure that only installer of the app receives this intent.
            intent.setPackage(installingPackageName);
            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
        }
    }

    /**
     * Sends an explicit broadcast with action {@link android.content.Intent#ACTION_LOCALE_CHANGED}
     * to the app whose locale has changed.
     */
    private void notifyAppWhoseLocaleChanged(String appPackageName, int userId,
            LocaleList locales) {
        Intent intent = createBaseIntent(Intent.ACTION_LOCALE_CHANGED, appPackageName, locales);
        //Set package name to ensure that only the app whose locale changed receives this intent.
        intent.setPackage(appPackageName);
        intent.addFlags(Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
    }

    static Intent createBaseIntent(String intentAction, String appPackageName,
            LocaleList locales) {
        return new Intent(intentAction)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, appPackageName)
                .putExtra(Intent.EXTRA_LOCALE_LIST, locales)
                .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                        | Intent.FLAG_RECEIVER_FOREGROUND);
    }

    /**
     * Same as {@link LocaleManagerService#isPackageOwnedByCaller(String, int,
     * AppLocaleChangedAtomRecord)}, but for methods that do not log locale atom.
     */
    private boolean isPackageOwnedByCaller(String appPackageName, int userId) {
        return isPackageOwnedByCaller(appPackageName, userId, /* atomRecordForMetrics= */null);
    }

    /**
     * Checks if the package is owned by the calling app or not for the given user id.
     *
     * @throws IllegalArgumentException if package not found for given userid
     */
    private boolean isPackageOwnedByCaller(String appPackageName, int userId,
            @Nullable AppLocaleChangedAtomRecord atomRecordForMetrics) {
        final int uid = getPackageUid(appPackageName, userId);
        if (uid < 0) {
            Slog.w(TAG, "Unknown package " + appPackageName + " for user " + userId);
            if (atomRecordForMetrics != null) {
                atomRecordForMetrics.setStatus(FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__STATUS__FAILURE_INVALID_TARGET_PACKAGE);
            }
            throw new IllegalArgumentException("Unknown package: " + appPackageName
                    + " for user " + userId);
        }
        if (atomRecordForMetrics != null) {
            atomRecordForMetrics.setTargetUid(uid);
        }
        //Once valid package found, ignore the userId part for validating package ownership
        //as apps with INTERACT_ACROSS_USERS permission could be changing locale for different user.
        return UserHandle.isSameApp(Binder.getCallingUid(), uid);
    }

    private void enforceChangeConfigurationPermission(@NonNull AppLocaleChangedAtomRecord
            atomRecordForMetrics) {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_CONFIGURATION, "setApplicationLocales");
        } catch (SecurityException e) {
            atomRecordForMetrics.setStatus(FrameworkStatsLog
                    .APPLICATION_LOCALES_CHANGED__STATUS__FAILURE_PERMISSION_ABSENT);
            throw e;
        }
    }

    /**
     * Returns the current UI locales for the specified app.
     */
    @NonNull
    public LocaleList getApplicationLocales(@NonNull String appPackageName, @UserIdInt int userId)
            throws RemoteException, IllegalArgumentException {
        requireNonNull(appPackageName);

        //Allow apps with INTERACT_ACROSS_USERS permission to query locales for different user.
        userId = mActivityManagerInternal.handleIncomingUser(
                Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false /* allowAll */, ActivityManagerInternal.ALLOW_NON_FULL,
                "getApplicationLocales", /* callerPackage= */ null);

        // This function handles three types of query operations:
        // 1.) A normal, non-privileged app querying its own locale.
        // 2.) The installer of the given app querying locales of a package installed
        // by said installer.
        // 3.) A privileged system service querying locales of another package.
        // The least privileged case is a normal app performing a query, so check that first and
        // get locales if the package name is owned by the app. Next check if the calling app
        // is the installer of the given app and get locales. If neither conditions matched,
        // check if the caller has the necessary permission and fetch locales.
        if (!isPackageOwnedByCaller(appPackageName, userId)
                && !isCallerInstaller(appPackageName, userId)) {
            enforceReadAppSpecificLocalesPermission();
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return getApplicationLocalesUnchecked(appPackageName, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    private LocaleList getApplicationLocalesUnchecked(@NonNull String appPackageName,
            @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "getApplicationLocales: fetching locales for package " + appPackageName
                    + " and user " + userId);
        }

        final ActivityTaskManagerInternal.PackageConfig appConfig =
                mActivityTaskManagerInternal.getApplicationConfig(appPackageName, userId);
        if (appConfig == null) {
            if (DEBUG) {
                Slog.d(TAG, "getApplicationLocales: application config not found for "
                        + appPackageName + " and user id " + userId);
            }
            return LocaleList.getEmptyLocaleList();
        }
        LocaleList locales = appConfig.mLocales;
        return locales != null ? locales : LocaleList.getEmptyLocaleList();
    }

    /**
     * Checks if the calling app is the installer of the app whose locale changed.
     */
    private boolean isCallerInstaller(String appPackageName, int userId) {
        String installingPackageName = getInstallingPackageName(appPackageName);
        if (installingPackageName != null) {
            // Get the uid of installer-on-record to compare with the calling uid.
            int installerUid = getPackageUid(installingPackageName, userId);
            return installerUid >= 0 && UserHandle.isSameApp(Binder.getCallingUid(), installerUid);
        }
        return false;
    }

    private void enforceReadAppSpecificLocalesPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_APP_SPECIFIC_LOCALES,
                "getApplicationLocales");
    }

    private int getPackageUid(String appPackageName, int userId) {
        try {
            return mPackageManager
                    .getPackageUidAsUser(appPackageName, PackageInfoFlags.of(0), userId);
        } catch (PackageManager.NameNotFoundException e) {
            return Process.INVALID_UID;
        }
    }

    @Nullable
    String getInstallingPackageName(String packageName) {
        try {
            return mContext.getPackageManager()
                    .getInstallSourceInfo(packageName).getInstallingPackageName();
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package not found " + packageName);
        }
        return null;
    }

    /**
     * Returns the current system locales.
     */
    @NonNull
    public LocaleList getSystemLocales() throws RemoteException {
        final long token = Binder.clearCallingIdentity();
        try {
            return getSystemLocalesUnchecked();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    private LocaleList getSystemLocalesUnchecked() throws RemoteException {
        LocaleList systemLocales = null;
        Configuration conf = ActivityManager.getService().getConfiguration();
        if (conf != null) {
            systemLocales = conf.getLocales();
        }
        if (systemLocales == null) {
            systemLocales = LocaleList.getEmptyLocaleList();
        }
        return systemLocales;
    }

    private void logMetric(@NonNull AppLocaleChangedAtomRecord atomRecordForMetrics) {
        FrameworkStatsLog.write(FrameworkStatsLog.APPLICATION_LOCALES_CHANGED,
                atomRecordForMetrics.mCallingUid,
                atomRecordForMetrics.mTargetUid,
                atomRecordForMetrics.mNewLocales,
                atomRecordForMetrics.mPrevLocales,
                atomRecordForMetrics.mStatus);
    }
}
