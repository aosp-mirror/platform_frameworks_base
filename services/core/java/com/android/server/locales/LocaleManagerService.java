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
import android.app.LocaleConfig;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.LocaleList;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The implementation of ILocaleManager.aidl.
 *
 * <p>This service is API entry point for storing app-specific UI locales and an override
 * {@link LocaleConfig} for a specified app.
 */
public class LocaleManagerService extends SystemService {
    private static final String TAG = "LocaleManagerService";
    // The feature flag control that allows the active IME to query the locales of the foreground
    // app.
    private static final String PROP_ALLOW_IME_QUERY_APP_LOCALE =
            "i18n.feature.allow_ime_query_app_locale";
    // The feature flag control that the application can dynamically override the LocaleConfig.
    private static final String PROP_DYNAMIC_LOCALES_CHANGE =
            "i18n.feature.dynamic_locales_change";
    private static final String LOCALE_CONFIGS = "locale_configs";
    private static final String SUFFIX_FILE_NAME = ".xml";
    private static final String ATTR_NAME = "name";

    final Context mContext;
    private final LocaleManagerService.LocaleManagerBinderService mBinderService;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private PackageManager mPackageManager;

    private LocaleManagerBackupHelper mBackupHelper;

    private final PackageMonitor mPackageMonitor;

    private final Object mWriteLock = new Object();

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
                systemAppUpdateTracker, this);
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
                @NonNull LocaleList locales, boolean fromDelegate) throws RemoteException {
            int caller = fromDelegate
                    ? FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__CALLER__CALLER_DELEGATE
                    : FrameworkStatsLog.APPLICATION_LOCALES_CHANGED__CALLER__CALLER_APPS;
            LocaleManagerService.this.setApplicationLocales(appPackageName, userId, locales,
                    fromDelegate, caller);
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
        public void setOverrideLocaleConfig(@NonNull String appPackageName, @UserIdInt int userId,
                @Nullable LocaleConfig localeConfig) throws RemoteException {
            LocaleManagerService.this.setOverrideLocaleConfig(appPackageName, userId, localeConfig);
        }

        @Override
        @Nullable
        public LocaleConfig getOverrideLocaleConfig(@NonNull String appPackageName,
                @UserIdInt int userId) {
            return LocaleManagerService.this.getOverrideLocaleConfig(appPackageName, userId);
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
            @NonNull LocaleList locales, boolean fromDelegate, int caller)
            throws RemoteException, IllegalArgumentException {
        AppLocaleChangedAtomRecord atomRecordForMetrics = new
                AppLocaleChangedAtomRecord(Binder.getCallingUid());
        try {
            requireNonNull(appPackageName);
            requireNonNull(locales);
            atomRecordForMetrics.setCaller(caller);
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
                    atomRecordForMetrics, null);
            if (!isCallerOwner) {
                enforceChangeConfigurationPermission(atomRecordForMetrics);
            }
            mBackupHelper.persistLocalesModificationInfo(userId, appPackageName, fromDelegate,
                    locales.isEmpty());
            final long token = Binder.clearCallingIdentity();
            try {
                setApplicationLocalesUnchecked(appPackageName, userId, locales,
                        atomRecordForMetrics);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            logAppLocalesMetric(atomRecordForMetrics);
        }
    }

    private void setApplicationLocalesUnchecked(@NonNull String appPackageName,
            @UserIdInt int userId, @NonNull LocaleList locales,
            @NonNull AppLocaleChangedAtomRecord atomRecordForMetrics) {
        if (DEBUG) {
            Slog.d(TAG, "setApplicationLocales: setting locales for package " + appPackageName
                    + " and user " + userId);
        }

        atomRecordForMetrics.setPrevLocales(
                getApplicationLocalesUnchecked(appPackageName, userId).toLanguageTags());
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
        String installingPackageName = getInstallingPackageName(appPackageName, userId);
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
     * Checks if the package is owned by the calling app or not for the given user id.
     *
     * @throws IllegalArgumentException if package not found for given userid
     */
    private boolean isPackageOwnedByCaller(String appPackageName, int userId,
            @Nullable AppLocaleChangedAtomRecord atomRecordForMetrics,
            @Nullable AppSupportedLocalesChangedAtomRecord appSupportedLocalesChangedAtomRecord) {
        final int uid = getPackageUid(appPackageName, userId);
        if (uid < 0) {
            Slog.w(TAG, "Unknown package " + appPackageName + " for user " + userId);
            if (atomRecordForMetrics != null) {
                atomRecordForMetrics.setStatus(FrameworkStatsLog
                        .APPLICATION_LOCALES_CHANGED__STATUS__FAILURE_INVALID_TARGET_PACKAGE);
            } else if (appSupportedLocalesChangedAtomRecord != null) {
                appSupportedLocalesChangedAtomRecord.setStatus(FrameworkStatsLog
                        .APP_SUPPORTED_LOCALES_CHANGED__STATUS__FAILURE_INVALID_TARGET_PACKAGE);
            }
            throw new IllegalArgumentException("Unknown package: " + appPackageName
                    + " for user " + userId);
        }
        if (atomRecordForMetrics != null) {
            atomRecordForMetrics.setTargetUid(uid);
        } else if (appSupportedLocalesChangedAtomRecord != null) {
            appSupportedLocalesChangedAtomRecord.setTargetUid(uid);
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

        // This function handles four types of query operations:
        // 1.) A normal, non-privileged app querying its own locale.
        // 2.) The installer of the given app querying locales of a package installed by said
        // installer.
        // 3.) The current input method querying locales of the current foreground app.
        // 4.) A privileged system service querying locales of another package.
        // The least privileged case is a normal app performing a query, so check that first and get
        // locales if the package name is owned by the app. Next check if the calling app is the
        // installer of the given app and get locales. Finally check if the calling app is the
        // current input method, and that app is querying locales of the current foreground app. If
        // neither conditions matched, check if the caller has the necessary permission and fetch
        // locales.
        if (!isPackageOwnedByCaller(appPackageName, userId, null, null)
                && !isCallerInstaller(appPackageName, userId)
                && !(isCallerFromCurrentInputMethod(userId)
                    && mActivityManagerInternal.isAppForeground(
                            getPackageUid(appPackageName, userId)))) {
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
        String installingPackageName = getInstallingPackageName(appPackageName, userId);
        if (installingPackageName != null) {
            // Get the uid of installer-on-record to compare with the calling uid.
            int installerUid = getPackageUid(installingPackageName, userId);
            return installerUid >= 0 && UserHandle.isSameApp(Binder.getCallingUid(), installerUid);
        }
        return false;
    }

    /**
     * Checks if the calling app is the current input method.
     */
    private boolean isCallerFromCurrentInputMethod(int userId) {
        if (!SystemProperties.getBoolean(PROP_ALLOW_IME_QUERY_APP_LOCALE, true)) {
            return false;
        }

        String currentInputMethod = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD,
                userId);
        if (!TextUtils.isEmpty(currentInputMethod)) {
            ComponentName componentName = ComponentName.unflattenFromString(currentInputMethod);
            if (componentName == null) {
                Slog.d(TAG, "inValid input method");
                return false;
            }
            String inputMethodPkgName = componentName.getPackageName();
            int inputMethodUid = getPackageUid(inputMethodPkgName, userId);
            return inputMethodUid >= 0 && UserHandle.isSameApp(Binder.getCallingUid(),
                    inputMethodUid);
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
    String getInstallingPackageName(String packageName, int userId) {
        try {
            return mContext.createContextAsUser(UserHandle.of(userId), /* flags= */
                    0).getPackageManager().getInstallSourceInfo(
                    packageName).getInstallingPackageName();
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

    private void logAppLocalesMetric(@NonNull AppLocaleChangedAtomRecord atomRecordForMetrics) {
        FrameworkStatsLog.write(FrameworkStatsLog.APPLICATION_LOCALES_CHANGED,
                atomRecordForMetrics.mCallingUid,
                atomRecordForMetrics.mTargetUid,
                atomRecordForMetrics.mNewLocales,
                atomRecordForMetrics.mPrevLocales,
                atomRecordForMetrics.mStatus,
                atomRecordForMetrics.mCaller);
    }

    /**
     * Storing an override {@link LocaleConfig} for a specified app.
     */
    public void setOverrideLocaleConfig(@NonNull String appPackageName, @UserIdInt int userId,
            @Nullable LocaleConfig localeConfig) throws IllegalArgumentException {
        if (!SystemProperties.getBoolean(PROP_DYNAMIC_LOCALES_CHANGE, true)) {
            return;
        }

        AppSupportedLocalesChangedAtomRecord atomRecord = new AppSupportedLocalesChangedAtomRecord(
                Binder.getCallingUid());
        try {
            requireNonNull(appPackageName);

            //Allow apps with INTERACT_ACROSS_USERS permission to set locales for different user.
            userId = mActivityManagerInternal.handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId,
                    false /* allowAll */, ActivityManagerInternal.ALLOW_NON_FULL,
                    "setOverrideLocaleConfig", /* callerPackage= */ null);

            // This function handles two types of set operations:
            // 1.) A normal, an app overrides its own LocaleConfig.
            // 2.) A privileged system application or service is granted the necessary permission to
            // override a LocaleConfig of another package.
            if (!isPackageOwnedByCaller(appPackageName, userId, null, atomRecord)) {
                enforceSetAppSpecificLocaleConfigPermission(atomRecord);
            }

            final long token = Binder.clearCallingIdentity();
            try {
                setOverrideLocaleConfigUnchecked(appPackageName, userId, localeConfig, atomRecord);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            logAppSupportedLocalesChangedMetric(atomRecord);
        }
    }

    private void setOverrideLocaleConfigUnchecked(@NonNull String appPackageName,
            @UserIdInt int userId, @Nullable LocaleConfig overrideLocaleConfig,
            @NonNull AppSupportedLocalesChangedAtomRecord atomRecord) {
        synchronized (mWriteLock) {
            if (DEBUG) {
                Slog.d(TAG,
                        "set the override LocaleConfig for package " + appPackageName + " and user "
                                + userId);
            }
            LocaleConfig resLocaleConfig = null;
            try {
                resLocaleConfig = LocaleConfig.fromContextIgnoringOverride(
                        mContext.createPackageContext(appPackageName, 0));
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Unknown package name " + appPackageName);
                return;
            }
            final File file = getXmlFileNameForUser(appPackageName, userId);

            if (overrideLocaleConfig == null) {
                if (file.exists()) {
                    Slog.d(TAG, "remove the override LocaleConfig");
                    file.delete();
                }
                removeUnsupportedAppLocales(appPackageName, userId, resLocaleConfig,
                        FrameworkStatsLog
                                .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_DYNAMIC_LOCALES_CHANGE
                );
                atomRecord.setOverrideRemoved(true);
                atomRecord.setStatus(FrameworkStatsLog
                        .APP_SUPPORTED_LOCALES_CHANGED__STATUS__SUCCESS);
                return;
            } else {
                if (overrideLocaleConfig.isSameLocaleConfig(
                        getOverrideLocaleConfig(appPackageName, userId))) {
                    Slog.d(TAG, "the same override, ignore it");
                    atomRecord.setSameAsPrevConfig(true);
                    return;
                }

                LocaleList localeList = overrideLocaleConfig.getSupportedLocales();
                // Normally the LocaleList object should not be null. However we reassign it as the
                // empty list in case it happens.
                if (localeList == null) {
                    localeList = LocaleList.getEmptyLocaleList();
                }
                if (DEBUG) {
                    Slog.d(TAG,
                            "setOverrideLocaleConfig, localeList: " + localeList.toLanguageTags());
                }
                atomRecord.setNumLocales(localeList.size());

                // Store the override LocaleConfig to the file storage.
                final AtomicFile atomicFile = new AtomicFile(file);
                FileOutputStream stream = null;
                try {
                    stream = atomicFile.startWrite();
                    stream.write(toXmlByteArray(localeList));
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to write file " + atomicFile, e);
                    if (stream != null) {
                        atomicFile.failWrite(stream);
                    }
                    atomRecord.setStatus(FrameworkStatsLog
                            .APP_SUPPORTED_LOCALES_CHANGED__STATUS__FAILURE_WRITE_TO_STORAGE);
                    return;
                }
                atomicFile.finishWrite(stream);
                // Clear per-app locales if they are not in the override LocaleConfig.
                removeUnsupportedAppLocales(appPackageName, userId, overrideLocaleConfig,
                        FrameworkStatsLog
                                .APPLICATION_LOCALES_CHANGED__CALLER__CALLER_DYNAMIC_LOCALES_CHANGE
                );
                if (overrideLocaleConfig.isSameLocaleConfig(resLocaleConfig)) {
                    Slog.d(TAG, "setOverrideLocaleConfig, same as the app's LocaleConfig");
                    atomRecord.setSameAsResConfig(true);
                }
                atomRecord.setStatus(FrameworkStatsLog
                        .APP_SUPPORTED_LOCALES_CHANGED__STATUS__SUCCESS);
                if (DEBUG) {
                    Slog.i(TAG, "Successfully written to " + atomicFile);
                }
            }
        }
    }

    /**
     * Checks if the per-app locales are in the LocaleConfig. Per-app locales missing from the
     * LocaleConfig will be removed.
     *
     * <p><b>Note:</b> Check whether to remove the per-app locales when the app is upgraded or
     * the LocaleConfig is overridden.
     */
    void removeUnsupportedAppLocales(String appPackageName, int userId,
            LocaleConfig localeConfig, int caller) {
        LocaleList appLocales = getApplicationLocalesUnchecked(appPackageName, userId);
        // Remove the per-app locales from the locale list if they don't exist in the LocaleConfig.
        boolean resetAppLocales = false;
        List<Locale> newAppLocales = new ArrayList<Locale>();

        if (localeConfig == null) {
            //Reset the app locales to the system default
            Slog.i(TAG, "There is no LocaleConfig, reset app locales");
            resetAppLocales = true;
        } else {
            for (int i = 0; i < appLocales.size(); i++) {
                if (!localeConfig.containsLocale(appLocales.get(i))) {
                    Slog.i(TAG, "Missing from the LocaleConfig, reset app locales");
                    resetAppLocales = true;
                    continue;
                }
                newAppLocales.add(appLocales.get(i));
            }
        }

        if (resetAppLocales) {
            // Reset the app locales
            Locale[] locales = new Locale[newAppLocales.size()];
            try {
                setApplicationLocales(appPackageName, userId,
                        new LocaleList(newAppLocales.toArray(locales)),
                        mBackupHelper.areLocalesSetFromDelegate(userId, appPackageName), caller);
            } catch (RemoteException | IllegalArgumentException e) {
                Slog.e(TAG, "Could not set locales for " + appPackageName, e);
            }
        }
    }

    private void enforceSetAppSpecificLocaleConfigPermission(
            AppSupportedLocalesChangedAtomRecord atomRecord) {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_APP_SPECIFIC_LOCALECONFIG,
                    "setOverrideLocaleConfig");
        } catch (SecurityException e) {
            atomRecord.setStatus(FrameworkStatsLog
                    .APP_SUPPORTED_LOCALES_CHANGED__STATUS__FAILURE_PERMISSION_ABSENT);
            throw e;
        }
    }

    /**
     * Returns the override LocaleConfig for a specified app.
     */
    @Nullable
    public LocaleConfig getOverrideLocaleConfig(@NonNull String appPackageName,
            @UserIdInt int userId) {
        if (!SystemProperties.getBoolean(PROP_DYNAMIC_LOCALES_CHANGE, true)) {
            return null;
        }

        requireNonNull(appPackageName);

        // Allow apps with INTERACT_ACROSS_USERS permission to query the override LocaleConfig for
        // different user.
        userId = mActivityManagerInternal.handleIncomingUser(
                Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false /* allowAll */, ActivityManagerInternal.ALLOW_NON_FULL,
                "getOverrideLocaleConfig", /* callerPackage= */ null);

        final File file = getXmlFileNameForUser(appPackageName, userId);
        if (!file.exists()) {
            if (DEBUG) {
                Slog.i(TAG, "getOverrideLocaleConfig, the file is not existed.");
            }
            return null;
        }

        try (InputStream in = new FileInputStream(file)) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(in);
            List<String> overrideLocales = loadFromXml(parser);
            if (DEBUG) {
                Slog.i(TAG, "getOverrideLocaleConfig, Loaded locales: " + overrideLocales);
            }
            LocaleConfig storedLocaleConfig = new LocaleConfig(
                    LocaleList.forLanguageTags(String.join(",", overrideLocales)));

            return storedLocaleConfig;
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to parse XML configuration from " + file, e);
        }

        return null;
    }

    /**
     * Delete an override {@link LocaleConfig} for a specified app from the file storage.
     *
     * <p>Clear the override LocaleConfig from the storage when the app is uninstalled.
     */
    void deleteOverrideLocaleConfig(@NonNull String appPackageName, @UserIdInt int userId) {
        final File file = getXmlFileNameForUser(appPackageName, userId);

        if (file.exists()) {
            Slog.d(TAG, "Delete the override LocaleConfig.");
            file.delete();
        }
    }

    private byte[] toXmlByteArray(LocaleList localeList) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            TypedXmlSerializer out = Xml.newFastSerializer();
            out.setOutput(os, StandardCharsets.UTF_8.name());
            out.startDocument(/* encoding= */ null, /* standalone= */ true);
            out.startTag(/* namespace= */ null, LocaleConfig.TAG_LOCALE_CONFIG);

            List<String> locales = new ArrayList<String>(
                    Arrays.asList(localeList.toLanguageTags().split(",")));
            for (String locale : locales) {
                out.startTag(null, LocaleConfig.TAG_LOCALE);
                out.attribute(null, ATTR_NAME, locale);
                out.endTag(null, LocaleConfig.TAG_LOCALE);
            }

            out.endTag(/* namespace= */ null, LocaleConfig.TAG_LOCALE_CONFIG);
            out.endDocument();

            if (DEBUG) {
                Slog.d(TAG, "setOverrideLocaleConfig toXmlByteArray, output: " + os.toString());
            }
            return os.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @NonNull
    private List<String> loadFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        List<String> localeList = new ArrayList<>();

        XmlUtils.beginDocument(parser, LocaleConfig.TAG_LOCALE_CONFIG);
        int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            final String tagName = parser.getName();
            if (LocaleConfig.TAG_LOCALE.equals(tagName)) {
                String locale = parser.getAttributeValue(/* namespace= */ null, ATTR_NAME);
                localeList.add(locale);
            } else {
                Slog.w(TAG, "Unexpected tag name: " + tagName);
                XmlUtils.skipCurrentTag(parser);
            }
        }

        return localeList;
    }

    @NonNull
    private File getXmlFileNameForUser(@NonNull String appPackageName, @UserIdInt int userId) {
        final File dir = new File(Environment.getDataSystemCeDirectory(userId), LOCALE_CONFIGS);
        return new File(dir, appPackageName + SUFFIX_FILE_NAME);
    }

    private void logAppSupportedLocalesChangedMetric(
            @NonNull AppSupportedLocalesChangedAtomRecord atomRecord) {
        FrameworkStatsLog.write(FrameworkStatsLog.APP_SUPPORTED_LOCALES_CHANGED,
                atomRecord.mCallingUid,
                atomRecord.mTargetUid,
                atomRecord.mNumLocales,
                atomRecord.mOverrideRemoved,
                atomRecord.mSameAsResConfig,
                atomRecord.mSameAsPrevConfig,
                atomRecord.mStatus);
    }
}
