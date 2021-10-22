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
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.ILocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The implementation of ILocaleManager.aidl.
 *
 * <p>This service is API entry point for storing app-specific UI locales
 */
public class LocaleManagerService extends SystemService {
    private static final String TAG = "LocaleManagerService";
    private final Context mContext;
    private final LocaleManagerService.LocaleManagerBinderService mBinderService;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private PackageManagerInternal mPackageManagerInternal;
    public static final boolean DEBUG = false;

    public LocaleManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new LocaleManagerBinderService();
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @VisibleForTesting
    LocaleManagerService(Context context, ActivityTaskManagerInternal activityTaskManagerInternal,
            ActivityManagerInternal activityManagerInternal,
            PackageManagerInternal packageManagerInternal) {
        super(context);
        mContext = context;
        mBinderService = new LocaleManagerBinderService();
        mActivityTaskManagerInternal = activityTaskManagerInternal;
        mActivityManagerInternal = activityManagerInternal;
        mPackageManagerInternal = packageManagerInternal;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LOCALE_SERVICE, mBinderService);
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
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            LocaleManagerService.this.dump(fd, pw, args);
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
        requireNonNull(appPackageName);
        requireNonNull(locales);

        //Allow apps with INTERACT_ACROSS_USERS permission to set locales for different user.
        userId = mActivityManagerInternal.handleIncomingUser(
                Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false /* allowAll */, ActivityManagerInternal.ALLOW_NON_FULL,
                "setApplicationLocales", appPackageName);

        // This function handles two types of set operations:
        // 1.) A normal, non-privileged app setting its own locale.
        // 2.) A privileged system service setting locales of another package.
        // The least privileged case is a normal app performing a set, so check that first and
        // set locales if the package name is owned by the app. Next, check if the caller has the
        // necessary permission and set locales.
        boolean isCallerOwner = isPackageOwnedByCaller(appPackageName, userId);
        if (!isCallerOwner) {
            enforceChangeConfigurationPermission();
        }

        final long token = Binder.clearCallingIdentity();
        try {
            setApplicationLocalesUnchecked(appPackageName, userId, locales);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void setApplicationLocalesUnchecked(@NonNull String appPackageName,
            @UserIdInt int userId, @NonNull LocaleList locales) {
        if (DEBUG) {
            Slog.d(TAG, "setApplicationLocales: setting locales for package " + appPackageName
                    + " and user " + userId);
        }
        final ActivityTaskManagerInternal.PackageConfigurationUpdater updater =
                mActivityTaskManagerInternal.createPackageConfigurationUpdater(appPackageName,
                        userId);
        boolean isSuccess = updater.setLocales(locales).commit();

        //We want to send the broadcasts only if config was actually updated on commit.
        if (isSuccess) {
            notifyAppWhoseLocaleChanged(appPackageName, userId, locales);
            notifyInstallerOfAppWhoseLocaleChanged(appPackageName, userId, locales);
            notifyRegisteredReceivers(appPackageName, userId, locales);
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
    private void notifyInstallerOfAppWhoseLocaleChanged(String appPackageName, int userId,
            LocaleList locales) {
        try {
            String installingPackageName = mContext.getPackageManager()
                    .getInstallSourceInfo(appPackageName).getInstallingPackageName();
            if (installingPackageName != null) {
                Intent intent = createBaseIntent(Intent.ACTION_APPLICATION_LOCALE_CHANGED,
                        appPackageName, locales);
                //Set package name to ensure that only installer of the app receives this intent.
                intent.setPackage(installingPackageName);
                mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package not found " + appPackageName);
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

    private static Intent createBaseIntent(String intentAction, String appPackageName,
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
    private boolean isPackageOwnedByCaller(String appPackageName, int userId) {
        final int uid = mPackageManagerInternal
                .getPackageUid(appPackageName, /* flags */ 0, userId);
        if (uid < 0) {
            Slog.w(TAG, "Unknown package " + appPackageName + " for user " + userId);
            throw new IllegalArgumentException("Unknown package: " + appPackageName
                    + " for user " + userId);
        }
        //Once valid package found, ignore the userId part for validating package ownership
        //as apps with INTERACT_ACROSS_USERS permission could be changing locale for different user.
        return UserHandle.isSameApp(Binder.getCallingUid(), uid);
    }

    private void enforceChangeConfigurationPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_CONFIGURATION, "setApplicationLocales");
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
                "getApplicationLocales", appPackageName);

        // This function handles two types of query operations:
        // 1.) A normal, non-privileged app querying its own locale.
        // 2.) A privileged system service querying locales of another package.
        // The least privileged case is a normal app performing a query, so check that first and
        // get locales if the package name is owned by the app. Next, check if the caller has the
        // necessary permission and get locales.
        if (!isPackageOwnedByCaller(appPackageName, userId)) {
            enforceReadAppSpecificLocalesPermission();
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return getApplicationLocalesUnchecked(appPackageName, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

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

    private void enforceReadAppSpecificLocalesPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.READ_APP_SPECIFIC_LOCALES,
                "getApplicationLocales");
    }

    /**
     * Dumps useful info related to service.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        // TODO(b/201766221): Implement when there is state.
    }
}
