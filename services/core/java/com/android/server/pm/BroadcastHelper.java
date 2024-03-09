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

package com.android.server.pm;

import static android.os.PowerExemptionManager.REASON_LOCKED_BOOT_COMPLETED;
import static android.os.PowerExemptionManager.REASON_PACKAGE_REPLACED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Process.SYSTEM_UID;
import static android.safetylabel.SafetyLabelConstants.SAFETY_LABEL_CHANGE_NOTIFICATIONS_ENABLED;

import static com.android.server.pm.PackageManagerService.DEBUG_BACKUP;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.EMPTY_INT_ARRAY;
import static com.android.server.pm.PackageManagerService.PACKAGE_SCHEME;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.multiuser.Flags;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DeviceConfig;
import android.stats.storage.StorageEnums;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiFunction;

/**
 * Helper class to send broadcasts for various situations.
 */
public final class BroadcastHelper {
    private static final boolean DEBUG_BROADCASTS = false;
    /**
     * Permissions required in order to receive instant application lifecycle broadcasts.
     */
    private static final String[] INSTANT_APP_BROADCAST_PERMISSION =
            new String[]{android.Manifest.permission.ACCESS_INSTANT_APPS};

    private final UserManagerInternal mUmInternal;
    private final ActivityManagerInternal mAmInternal;
    private final Context mContext;
    private final Handler mHandler;
    private final PackageMonitorCallbackHelper mPackageMonitorCallbackHelper;
    private final AppsFilterSnapshot mAppsFilter;

    BroadcastHelper(PackageManagerServiceInjector injector) {
        mUmInternal = injector.getUserManagerInternal();
        mAmInternal = injector.getActivityManagerInternal();
        mContext = injector.getContext();
        mHandler = injector.getHandler();
        mPackageMonitorCallbackHelper = injector.getPackageMonitorCallbackHelper();
        mAppsFilter = injector.getAppsFilter();
    }

    /**
     * Sends a broadcast to registered clients on userId for the given Intent.
     */
    void sendPackageBroadcastWithIntent(Intent intent, int userId, boolean isInstantApp,
            @Intent.Flags int flags,
            int[] visibilityAllowList,
            final IIntentReceiver finishedReceiver,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT | flags);
        SparseArray<int[]> broadcastAllowList = new SparseArray<>();
        broadcastAllowList.put(userId, visibilityAllowList);
        broadcastIntent(intent, finishedReceiver, isInstantApp, userId, broadcastAllowList,
                filterExtrasForReceiver, bOptions);
    }

    void sendPackageBroadcast(final String action, final String pkg, final Bundle extras,
            final int flags, final String targetPkg, final IIntentReceiver finishedReceiver,
            final int[] userIds, int[] instantUserIds,
            @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        try {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) return;
            final int[] resolvedUserIds;
            if (userIds == null) {
                resolvedUserIds = am.getRunningUserIds();
            } else {
                resolvedUserIds = userIds;
            }

            if (ArrayUtils.isEmpty(instantUserIds)) {
                doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                        resolvedUserIds, false /* isInstantApp */, broadcastAllowList,
                        filterExtrasForReceiver, bOptions);
            } else {
                // send restricted broadcasts for instant apps
                doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                        instantUserIds, true /* isInstantApp */, null,
                        null /* filterExtrasForReceiver */, bOptions);
            }
        } catch (RemoteException ex) {
        }
    }

    /**
     * Sends a broadcast for the given action.
     * <p>If {@code isInstantApp} is {@code true}, then the broadcast is protected with
     * the {@link android.Manifest.permission#ACCESS_INSTANT_APPS} permission. This allows
     * the system and applications allowed to see instant applications to receive package
     * lifecycle events for instant applications.
     */
    private void doSendBroadcast(
            @NonNull String action,
            @Nullable String pkg,
            @Nullable Bundle extras,
            int flags,
            @Nullable String targetPkg,
            @Nullable IIntentReceiver finishedReceiver,
            @NonNull int[] userIds,
            boolean isInstantApp,
            @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        for (int userId : userIds) {
            final Intent intent = new Intent(action,
                    pkg != null ? Uri.fromParts(PACKAGE_SCHEME, pkg, null) : null);
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (targetPkg != null) {
                intent.setPackage(targetPkg);
            }
            // Modify the UID when posting to other users
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid >= 0 && UserHandle.getUserId(uid) != userId) {
                uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));
                intent.putExtra(Intent.EXTRA_UID, uid);
            }
            if (broadcastAllowList != null && PLATFORM_PACKAGE_NAME.equals(targetPkg)) {
                intent.putExtra(Intent.EXTRA_VISIBILITY_ALLOW_LIST,
                         broadcastAllowList.get(userId));
            }
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT | flags);
            broadcastIntent(intent, finishedReceiver, isInstantApp, userId, broadcastAllowList,
                    filterExtrasForReceiver, bOptions);
        }
    }


    private void broadcastIntent(Intent intent, IIntentReceiver finishedReceiver,
            boolean isInstantApp, int userId, @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
            @Nullable Bundle bOptions) {
        final String[] requiredPermissions =
                isInstantApp ? INSTANT_APP_BROADCAST_PERMISSION : null;
        if (DEBUG_BROADCASTS) {
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.d(TAG, "Sending to user " + userId + ": "
                    + intent.toShortString(false, true, false, false)
                    + " " + intent.getExtras(), here);
        }
        mAmInternal.broadcastIntentWithCallback(
                intent, finishedReceiver, requiredPermissions, userId,
                broadcastAllowList == null ? null : broadcastAllowList.get(userId),
                filterExtrasForReceiver, bOptions);
    }

    void sendResourcesChangedBroadcast(@NonNull Computer snapshot,
                                       boolean mediaStatus,
                                       boolean replacing,
                                       @NonNull String[] pkgNames,
                                       @NonNull int[] uids) {
        if (ArrayUtils.isEmpty(pkgNames) || ArrayUtils.isEmpty(uids)) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgNames);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uids);
        if (replacing) {
            extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
        }
        String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
        sendPackageBroadcast(action, null /* pkg */, extras, 0 /* flags */,
                null /* targetPkg */, null /* finishedReceiver */, null /* userIds */,
                null /* instantUserIds */, null /* broadcastAllowList */,
                (callingUid, intentExtras) -> filterExtrasChangedPackageList(
                        snapshot, callingUid, intentExtras),
                null /* bOptions */);
    }

    /**
     * The just-installed/enabled app is bundled on the system, so presumed to be able to run
     * automatically without needing an explicit launch.
     * Send it a LOCKED_BOOT_COMPLETED/BOOT_COMPLETED if it would ordinarily have gotten ones.
     */
    private void sendBootCompletedBroadcastToSystemApp(@NonNull String packageName,
                                                       boolean includeStopped,
                                                       int userId) {
        // If user is not running, the app didn't miss any broadcast
        if (!mUmInternal.isUserRunning(userId)) {
            return;
        }
        final IActivityManager am = ActivityManager.getService();
        try {
            // Deliver LOCKED_BOOT_COMPLETED first
            Intent lockedBcIntent = new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    .setPackage(packageName);
            lockedBcIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            if (includeStopped) {
                lockedBcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            }
            final String[] requiredPermissions = {Manifest.permission.RECEIVE_BOOT_COMPLETED};
            final BroadcastOptions bOptions = getTemporaryAppAllowlistBroadcastOptions(
                    REASON_LOCKED_BOOT_COMPLETED);
            am.broadcastIntentWithFeature(null, null, lockedBcIntent, null, null, 0, null, null,
                    requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE,
                    bOptions.toBundle(), false, false, userId);

            // Deliver BOOT_COMPLETED only if user is unlocked
            if (mUmInternal.isUserUnlockingOrUnlocked(userId)) {
                Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(packageName);
                bcIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                if (includeStopped) {
                    bcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                }
                am.broadcastIntentWithFeature(null, null, bcIntent, null, null, 0, null, null,
                        requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE,
                        bOptions.toBundle(), false, false, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private @NonNull BroadcastOptions getTemporaryAppAllowlistBroadcastOptions(
            @PowerExemptionManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        if (mAmInternal != null) {
            duration = mAmInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                reasonCode, "");
        return bOptions;
    }

    private void sendPackageChangedBroadcast(@NonNull String packageName,
                                             boolean dontKillApp,
                                             @NonNull ArrayList<String> componentNames,
                                             int packageUid,
                                             @Nullable String reason,
                                             @Nullable int[] userIds,
                                             @Nullable int[] instantUserIds,
                                             @Nullable SparseArray<int[]> broadcastAllowList) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "Sending package changed: package=" + packageName + " components="
                    + componentNames);
        }
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, dontKillApp);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        if (reason != null) {
            extras.putString(Intent.EXTRA_REASON, reason);
        }
        // If this is not reporting a change of the overall package, then only send it
        // to registered receivers.  We don't want to launch a swath of apps for every
        // little component state change.
        final int flags = !componentNames.contains(packageName)
                ? Intent.FLAG_RECEIVER_REGISTERED_ONLY : 0;
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED, packageName, extras, flags, null, null,
                userIds, instantUserIds, broadcastAllowList, null /* filterExtrasForReceiver */,
                null /* bOptions */);
    }

    static void sendDeviceCustomizationReadyBroadcast() {
        final Intent intent = new Intent(Intent.ACTION_DEVICE_CUSTOMIZATION_READY);
        intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        final IActivityManager am = ActivityManager.getService();
        final String[] requiredPermissions = {
                Manifest.permission.RECEIVE_DEVICE_CUSTOMIZATION_READY,
        };
        try {
            am.broadcastIntentWithFeature(null, null, intent, null, null, 0, null, null,
                    requiredPermissions, null, null, android.app.AppOpsManager.OP_NONE, null, false,
                    false, UserHandle.USER_ALL);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void sendSessionCommitBroadcast(@NonNull Computer snapshot,
                                    @NonNull PackageInstaller.SessionInfo sessionInfo,
                                    int userId,
                                    @Nullable String appPredictionServicePackage) {
        UserManagerService ums = UserManagerService.getInstance();
        if (ums == null || sessionInfo.isStaged()) {
            return;
        }
        final UserInfo parent = ums.getProfileParent(userId);
        final int launcherUserId = (parent != null) ? parent.id : userId;
        final ComponentName launcherComponent = snapshot.getDefaultHomeActivity(launcherUserId);
        if (launcherComponent != null && canLauncherAccessProfile(launcherComponent, userId)) {
            Intent launcherIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                    .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                    .setPackage(launcherComponent.getPackageName());
            mContext.sendBroadcastAsUser(launcherIntent, UserHandle.of(launcherUserId));
        }
        if (appPredictionServicePackage != null) {
            Intent predictorIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                    .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                    .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                    .setPackage(appPredictionServicePackage);
            mContext.sendBroadcastAsUser(predictorIntent, UserHandle.of(launcherUserId));
        }
    }

    /**
     * A Profile is accessible to launcher in question if:
     * - It's not hidden for API visibility.
     * - Hidden, but launcher application has either
     *      {@link Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL} or
     *      {@link Manifest.permission.ACCESS_HIDDEN_PROFILES}
     *   granted.
     */
    boolean canLauncherAccessProfile(ComponentName launcherComponent, int userId) {
        if (android.os.Flags.allowPrivateProfile()
                && Flags.enablePermissionToAccessHiddenProfiles()
                && Flags.enablePrivateSpaceFeatures()) {
            if (mUmInternal.getUserProperties(userId).getProfileApiVisibility()
                    != UserProperties.PROFILE_API_VISIBILITY_HIDDEN) {
                return true;
            }
            if (mContext.getPackageManager().checkPermission(
                            Manifest.permission.ACCESS_HIDDEN_PROFILES_FULL,
                            launcherComponent.getPackageName())
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            // TODO(b/122900055) Change/Remove this and replace with new permission role.
            return mContext.getPackageManager().checkPermission(
                            Manifest.permission.ACCESS_HIDDEN_PROFILES,
                            launcherComponent.getPackageName())
                        == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    void sendPreferredActivityChangedBroadcast(int userId) {
        mHandler.post(() -> {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) {
                return;
            }

            final Intent intent = new Intent(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            try {
                am.broadcastIntentWithFeature(null, null, intent, null, null,
                        0, null, null, null, null, null, android.app.AppOpsManager.OP_NONE,
                        null, false, false, userId);
            } catch (RemoteException e) {
            }
        });
    }

    void sendPostInstallBroadcasts(@NonNull Computer snapshot,
                                   @NonNull InstallRequest request,
                                   @NonNull String packageName,
                                   @NonNull String requiredPermissionControllerPackage,
                                   @NonNull String[] requiredVerifierPackages,
                                   @NonNull String requiredInstallerPackage,
                                   @NonNull PackageSender packageSender,
                                   boolean isLaunchedForRestore,
                                   boolean isKillApp,
                                   boolean isUpdate,
                                   boolean isArchived) {
        // Send the removed broadcasts
        if (request.getRemovedInfo() != null) {
            if (request.getRemovedInfo().mIsExternal) {
                if (DEBUG_INSTALL) {
                    Slog.i(TAG, "upgrading pkg " + request.getRemovedInfo().mRemovedPackage
                            + " is ASEC-hosted -> UNAVAILABLE");
                }
                final String[] pkgNames = new String[]{
                        request.getRemovedInfo().mRemovedPackage};
                final int[] uids = new int[]{request.getRemovedInfo().mUid};
                notifyResourcesChanged(
                        false /* mediaStatus */, true /* replacing */, pkgNames, uids);
                sendResourcesChangedBroadcast(
                        snapshot, false /* mediaStatus */, true /* replacing */, pkgNames, uids);
            }
            sendPackageRemovedBroadcasts(
                    request.getRemovedInfo(), packageSender, isKillApp, false /*removedBySystem*/,
                    false /*isArchived*/);
        }

        final int[] firstUserIds = request.getFirstTimeBroadcastUserIds();
        final int[] firstInstantUserIds = request.getFirstTimeBroadcastInstantUserIds();
        final int[] updateUserIds = request.getUpdateBroadcastUserIds();
        final int[] instantUserIds = request.getUpdateBroadcastInstantUserIds();

        final String installerPackageName =
                request.getInstallerPackageName() != null
                        ? request.getInstallerPackageName()
                        : request.getRemovedInfo() != null
                        ? request.getRemovedInfo().mInstallerPackageName
                        : null;

        Bundle extras = new Bundle();
        extras.putInt(Intent.EXTRA_UID, request.getAppId());
        if (isUpdate) {
            extras.putBoolean(Intent.EXTRA_REPLACING, true);
        }
        if (isArchived) {
            extras.putBoolean(Intent.EXTRA_ARCHIVAL, true);
        }
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, request.getDataLoaderType());

        final String staticSharedLibraryName = request.getPkg().getStaticSharedLibraryName();
        // If a package is a static shared library, then only the installer of the package
        // should get the broadcast.
        if (installerPackageName != null && staticSharedLibraryName != null) {
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, packageName,
                    extras, 0 /*flags*/,
                    installerPackageName, null /*finishedReceiver*/,
                    request.getNewUsers(), null /* instantUserIds*/,
                    null /* broadcastAllowList */, null);
        }

        // Send installed broadcasts if the package is not a static shared lib.
        if (staticSharedLibraryName == null) {
            // Send PACKAGE_ADDED broadcast for users that see the package for the first time
            // sendPackageAddedForNewUsers also deals with system apps
            final int appId = UserHandle.getAppId(request.getAppId());
            final boolean isSystem = request.isInstallSystem();
            final boolean isVirtualPreload =
                    ((request.getInstallFlags() & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);
            sendPackageAddedForNewUsers(snapshot, packageName,
                    isSystem || isVirtualPreload,
                    isVirtualPreload /*startReceiver*/, appId,
                    firstUserIds, firstInstantUserIds, isArchived, request.getDataLoaderType());

            // Send PACKAGE_ADDED broadcast for users that don't see
            // the package for the first time

            // Send to all running apps.
            final SparseArray<int[]> newBroadcastAllowList =
                    mAppsFilter.getVisibilityAllowList(snapshot,
                            snapshot.getPackageStateInternal(packageName, Process.SYSTEM_UID),
                            updateUserIds, snapshot.getPackageStates());
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, packageName,
                    extras, 0 /*flags*/,
                    null /*targetPackage*/, null /*finishedReceiver*/,
                    updateUserIds, instantUserIds, newBroadcastAllowList, null);
            // Send to the installer, even if it's not running.
            if (installerPackageName != null) {
                sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, 0 /*flags*/,
                        installerPackageName, null /*finishedReceiver*/,
                        updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
            }
            // Send to PermissionController for all update users, even if it may not be running
            // for some users
            if (isPrivacySafetyLabelChangeNotificationsEnabled(mContext)) {
                sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, 0 /*flags*/,
                        requiredPermissionControllerPackage, null /*finishedReceiver*/,
                        updateUserIds, instantUserIds, null /* broadcastAllowList */, null);
            }
            // Notify required verifier(s) that are not the installer of record for the package.
            for (String verifierPackageName : requiredVerifierPackages) {
                if (verifierPackageName != null && !verifierPackageName.equals(
                        installerPackageName)) {
                    sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED,
                            packageName,
                            extras, 0 /*flags*/,
                            verifierPackageName, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /* broadcastAllowList */,
                            null);
                }
            }
            // If package installer is defined, notify package installer about new
            // app installed
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, packageName,
                    extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND /*flags*/,
                    requiredInstallerPackage, null /*finishedReceiver*/,
                    firstUserIds, instantUserIds, null /* broadcastAllowList */, null);

            // Send replaced for users that don't see the package for the first time
            if (isUpdate) {
                sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REPLACED,
                        packageName, extras, 0 /*flags*/,
                        null /*targetPackage*/, null /*finishedReceiver*/,
                        updateUserIds, instantUserIds,
                        request.getRemovedInfo().mBroadcastAllowList, null);
                if (installerPackageName != null) {
                    sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REPLACED, packageName,
                            extras, 0 /*flags*/,
                            installerPackageName, null /*finishedReceiver*/,
                            updateUserIds, instantUserIds, null /*broadcastAllowList*/,
                            null);
                }
                for (String verifierPackageName : requiredVerifierPackages) {
                    if (verifierPackageName != null && !verifierPackageName.equals(
                            installerPackageName)) {
                        sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REPLACED,
                                packageName, extras, 0 /*flags*/, verifierPackageName,
                                null /*finishedReceiver*/, updateUserIds, instantUserIds,
                                null /*broadcastAllowList*/, null);
                    }
                }
                sendPackageBroadcastAndNotify(Intent.ACTION_MY_PACKAGE_REPLACED,
                        null /*package*/, null /*extras*/, 0 /*flags*/,
                        packageName /*targetPackage*/,
                        null /*finishedReceiver*/, updateUserIds, instantUserIds,
                        null /*broadcastAllowList*/,
                        getTemporaryAppAllowlistBroadcastOptions(
                                REASON_PACKAGE_REPLACED).toBundle());
            } else if (isLaunchedForRestore && !request.isInstallSystem()) {
                // First-install and we did a restore, so we're responsible for the
                // first-launch broadcast.
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "Post-restore of " + packageName
                            + " sending FIRST_LAUNCH in " + Arrays.toString(firstUserIds));
                }
                sendFirstLaunchBroadcast(packageName, installerPackageName,
                        firstUserIds, firstInstantUserIds);
            }

            // Send broadcast package appeared if external for all users
            if (request.getPkg().isExternalStorage()) {
                if (!isUpdate) {
                    final StorageManager storage = mContext.getSystemService(StorageManager.class);
                    VolumeInfo volume =
                            storage.findVolumeByUuid(
                                    StorageManager.convert(
                                            request.getPkg().getVolumeUuid()).toString());
                    int packageExternalStorageType =
                            PackageManagerServiceUtils.getPackageExternalStorageType(volume,
                                    /* isExternalStorage */ true);
                    // If the package was installed externally, log it.
                    if (packageExternalStorageType != StorageEnums.UNKNOWN) {
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.APP_INSTALL_ON_EXTERNAL_STORAGE_REPORTED,
                                packageExternalStorageType, packageName);
                    }
                }
                if (DEBUG_INSTALL) {
                    Slog.i(TAG, "upgrading pkg " + packageName + " is external");
                }
                if (!isArchived) {
                    final String[] pkgNames = new String[]{packageName};
                    final int[] uids = new int[]{request.getPkg().getUid()};
                    sendResourcesChangedBroadcast(snapshot,
                            true /* mediaStatus */, true /* replacing */, pkgNames, uids);
                    notifyResourcesChanged(true /* mediaStatus */,
                            true /* replacing */, pkgNames, uids);
                }
            }
        } else { // if static shared lib
            final ArrayList<AndroidPackage> libraryConsumers = request.getLibraryConsumers();
            if (!ArrayUtils.isEmpty(libraryConsumers)) {
                // No need to kill consumers if it's installation of new version static shared lib.
                final boolean dontKillApp = !isUpdate;
                for (int i = 0; i < libraryConsumers.size(); i++) {
                    AndroidPackage pkg = libraryConsumers.get(i);
                    // send broadcast that all consumers of the static shared library have changed
                    sendPackageChangedBroadcast(snapshot, pkg.getPackageName(),
                            dontKillApp,
                            new ArrayList<>(Collections.singletonList(pkg.getPackageName())),
                            pkg.getUid(), null);
                }
            }
        }
    }

    private void sendPackageAddedForNewUsers(@NonNull Computer snapshot,
                                             @NonNull String packageName,
                                             boolean sendBootCompleted,
                                             boolean includeStopped,
                                             @AppIdInt int appId,
                                             int[] userIds,
                                             int[] instantUserIds,
                                             boolean isArchived,
                                             int dataLoaderType) {
        if (ArrayUtils.isEmpty(userIds) && ArrayUtils.isEmpty(instantUserIds)) {
            return;
        }
        SparseArray<int[]> broadcastAllowList = mAppsFilter.getVisibilityAllowList(snapshot,
                snapshot.getPackageStateInternal(packageName, Process.SYSTEM_UID),
                userIds, snapshot.getPackageStates());
        mHandler.post(
                () -> sendPackageAddedForNewUsers(packageName, appId, userIds,
                        instantUserIds, isArchived, dataLoaderType, broadcastAllowList));
        mPackageMonitorCallbackHelper.notifyPackageAddedForNewUsers(packageName, appId, userIds,
                instantUserIds, isArchived, dataLoaderType, broadcastAllowList, mHandler);
        if (sendBootCompleted && !ArrayUtils.isEmpty(userIds)) {
            mHandler.post(() -> {
                        for (int userId : userIds) {
                            sendBootCompletedBroadcastToSystemApp(
                                    packageName, includeStopped, userId);
                        }
                    }
            );
        }
    }

    private void sendPackageAddedForNewUsers(@NonNull String packageName,
                                             @AppIdInt int appId,
                                             int[] userIds,
                                             int[] instantUserIds,
                                             boolean isArchived,
                                             int dataLoaderType,
                                             @NonNull SparseArray<int[]> broadcastAllowlist) {
        Bundle extras = new Bundle(1);
        // Set to UID of the first user, EXTRA_UID is automatically updated in sendPackageBroadcast
        final int uid = UserHandle.getUid(
                (ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0]), appId);
        extras.putInt(Intent.EXTRA_UID, uid);
        if (isArchived) {
            extras.putBoolean(Intent.EXTRA_ARCHIVAL, true);
        }
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);

        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                packageName, extras, 0, null, null, userIds, instantUserIds,
                broadcastAllowlist, null /* filterExtrasForReceiver */, null);
        // Send to PermissionController for all new users, even if it may not be running for some
        // users
        if (isPrivacySafetyLabelChangeNotificationsEnabled(mContext)) {
            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                    packageName, extras, 0,
                    mContext.getPackageManager().getPermissionControllerPackageName(),
                    null, userIds, instantUserIds,
                    broadcastAllowlist, null /* filterExtrasForReceiver */, null);
        }
    }

    void sendPackageAddedForUser(@NonNull Computer snapshot,
                                 @NonNull String packageName,
                                 @NonNull PackageStateInternal packageState,
                                 int userId,
                                 boolean isArchived,
                                 int dataLoaderType,
                                 @Nullable String appPredictionServicePackage) {
        final PackageUserStateInternal userState = packageState.getUserStateOrDefault(userId);
        final boolean isSystem = packageState.isSystem();
        final boolean isInstantApp = userState.isInstantApp();
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        sendPackageAddedForNewUsers(snapshot, packageName, isSystem /*sendBootCompleted*/,
                false /*startReceiver*/, packageState.getAppId(), userIds, instantUserIds,
                isArchived, dataLoaderType);

        // Send a session commit broadcast
        final PackageInstaller.SessionInfo info = new PackageInstaller.SessionInfo();
        info.installReason = userState.getInstallReason();
        info.appPackageName = packageName;
        sendSessionCommitBroadcast(snapshot, info, userId, appPredictionServicePackage);
    }

    void sendFirstLaunchBroadcast(String pkgName, String installerPkg,
            int[] userIds, int[] instantUserIds) {
        sendPackageBroadcast(Intent.ACTION_PACKAGE_FIRST_LAUNCH, pkgName, null, 0,
                installerPkg, null, userIds, instantUserIds, null /* broadcastAllowList */,
                null /* filterExtrasForReceiver */, null);
    }

    /**
     * Filter package names for the intent extras {@link Intent#EXTRA_CHANGED_PACKAGE_LIST} and
     * {@link Intent#EXTRA_CHANGED_UID_LIST} by using the rules of the package visibility.
     *
     * @param callingUid The uid that is going to access the intent extras.
     * @param extras The intent extras to filter
     * @return An extras that have been filtered, or {@code null} if the given uid is unable to
     * access all the packages in the extras.
     */
    @Nullable
    private static Bundle filterExtrasChangedPackageList(@NonNull Computer snapshot, int callingUid,
            @NonNull Bundle extras) {
        if (UserHandle.isCore(callingUid)) {
            // see all
            return extras;
        }
        final String[] pkgs = extras.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        if (ArrayUtils.isEmpty(pkgs)) {
            return extras;
        }
        final int userId = extras.getInt(
                Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(callingUid));
        final int[] uids = extras.getIntArray(Intent.EXTRA_CHANGED_UID_LIST);
        final Pair<String[], int[]> filteredPkgs =
                filterPackages(snapshot, pkgs, uids, callingUid, userId);
        if (ArrayUtils.isEmpty(filteredPkgs.first)) {
            // caller is unable to access this intent
            return null;
        }
        final Bundle filteredExtras = new Bundle(extras);
        filteredExtras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, filteredPkgs.first);
        filteredExtras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, filteredPkgs.second);
        return filteredExtras;
    }

    /** Returns whether the Safety Label Change notification, a privacy feature, is enabled. */
    private static boolean isPrivacySafetyLabelChangeNotificationsEnabled(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                SAFETY_LABEL_CHANGE_NOTIFICATIONS_ENABLED, true)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            && !packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    @NonNull
    private static Pair<String[], int[]> filterPackages(@NonNull Computer snapshot,
            @NonNull String[] pkgs, @Nullable int[] uids, int callingUid, int userId) {
        final int pkgSize = pkgs.length;
        final int uidSize = !ArrayUtils.isEmpty(uids) ? uids.length : 0;

        final ArrayList<String> pkgList = new ArrayList<>(pkgSize);
        final IntArray uidList = uidSize > 0 ? new IntArray(uidSize) : null;
        for (int i = 0; i < pkgSize; i++) {
            final String packageName = pkgs[i];
            if (snapshot.shouldFilterApplication(
                    snapshot.getPackageStateInternal(packageName), callingUid, userId)) {
                continue;
            }
            pkgList.add(packageName);
            if (uidList != null && i < uidSize) {
                uidList.add(uids[i]);
            }
        }
        return new Pair<>(
                pkgList.size() > 0 ? pkgList.toArray(new String[pkgList.size()]) : null,
                uidList != null && uidList.size() > 0 ? uidList.toArray() : null);
    }

    void sendApplicationHiddenForUser(@NonNull String packageName,
                                      @NonNull PackageStateInternal packageState,
                                      int userId,
                                      @NonNull PackageSender packageSender) {
        final PackageRemovedInfo info = new PackageRemovedInfo();
        info.mRemovedPackage = packageName;
        info.mInstallerPackageName = packageState.getInstallSource().mInstallerPackageName;
        info.mRemovedUsers = new int[] {userId};
        info.mBroadcastUsers = new int[] {userId};
        info.mUid = UserHandle.getUid(userId, packageState.getAppId());
        info.mRemovedPackageVersionCode = packageState.getVersionCode();
        sendPackageRemovedBroadcasts(info, packageSender, true /*killApp*/,
                false /*removedBySystem*/, false /*isArchived*/);
    }

    void sendPackageChangedBroadcast(@NonNull Computer snapshot,
                                     @NonNull String packageName,
                                     boolean dontKillApp,
                                     @NonNull ArrayList<String> componentNames,
                                     int packageUid,
                                     @NonNull String reason) {
        PackageStateInternal setting = snapshot.getPackageStateInternal(packageName,
                Process.SYSTEM_UID);
        if (setting == null) {
            return;
        }
        final int userId = UserHandle.getUserId(packageUid);
        final boolean isInstantApp =
                snapshot.isInstantAppInternal(packageName, userId, Process.SYSTEM_UID);
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        final SparseArray<int[]> broadcastAllowList =
                isInstantApp ? null : snapshot.getVisibilityAllowLists(packageName, userIds);
        mHandler.post(() -> sendPackageChangedBroadcast(
                packageName, dontKillApp, componentNames, packageUid, reason, userIds,
                instantUserIds, broadcastAllowList));
        mPackageMonitorCallbackHelper.notifyPackageChanged(packageName, dontKillApp, componentNames,
                packageUid, reason, userIds, instantUserIds, broadcastAllowList, mHandler);
    }

    private void sendPackageBroadcastAndNotify(@NonNull String action,
                                               @NonNull  String pkg,
                                               @NonNull  Bundle extras,
                                               int flags,
                                               @Nullable String targetPkg,
                                               @Nullable IIntentReceiver finishedReceiver,
                                               @NonNull int[] userIds,
                                               @NonNull int[] instantUserIds,
                                               @Nullable SparseArray<int[]> broadcastAllowList,
                                               @Nullable Bundle bOptions) {
        mHandler.post(() -> sendPackageBroadcast(action, pkg, extras, flags,
                targetPkg, finishedReceiver, userIds, instantUserIds, broadcastAllowList,
                null /* filterExtrasForReceiver */, bOptions));
        if (targetPkg == null) {
            // For some broadcast action, e.g. ACTION_PACKAGE_ADDED, this method will be called
            // many times to different targets, e.g. installer app, permission controller, other
            // registered apps. We should filter it to avoid calling back many times for the same
            // action. When the targetPkg is set, it sends the broadcast to specific app, e.g.
            // installer app or null for registered apps. The callback only need to send back to the
            // registered apps so we check the null condition here.
            notifyPackageMonitor(action, pkg, extras, userIds, instantUserIds, broadcastAllowList,
                    null /* filterExtras */);
        }
    }

    void sendSystemPackageUpdatedBroadcasts(@NonNull PackageRemovedInfo packageRemovedInfo) {
        if (!packageRemovedInfo.mIsRemovedPackageSystemUpdate) {
            return;
        }

        final String removedPackage = packageRemovedInfo.mRemovedPackage;
        final String installerPackageName = packageRemovedInfo.mInstallerPackageName;
        final SparseArray<int[]> broadcastAllowList = packageRemovedInfo.mBroadcastAllowList;

        Bundle extras = new Bundle(2);
        extras.putInt(Intent.EXTRA_UID, packageRemovedInfo.mUid);
        extras.putBoolean(Intent.EXTRA_REPLACING, true);
        sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED, removedPackage, extras,
                0, null /*targetPackage*/, null, null, null, broadcastAllowList, null);

        if (installerPackageName != null) {
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_ADDED,
                    removedPackage, extras, 0 /*flags*/,
                    installerPackageName, null, null, null, null /* broadcastAllowList */,
                    null);
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REPLACED,
                    removedPackage, extras, 0 /*flags*/,
                    installerPackageName, null, null, null, null /* broadcastAllowList */,
                    null);
        }
        sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REPLACED, removedPackage,
                extras, 0, null /*targetPackage*/, null, null, null, broadcastAllowList, null);
        sendPackageBroadcastAndNotify(Intent.ACTION_MY_PACKAGE_REPLACED, null, null, 0,
                removedPackage, null, null, null, null /* broadcastAllowList */,
                getTemporaryBroadcastOptionsForSystemPackageUpdate(REASON_PACKAGE_REPLACED)
                        .toBundle());
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private @NonNull BroadcastOptions getTemporaryBroadcastOptionsForSystemPackageUpdate(
            @PowerExemptionManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        if (mAmInternal != null) {
            duration = mAmInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                reasonCode, "");
        return bOptions;
    }


    void sendPackageRemovedBroadcasts(
            @NonNull PackageRemovedInfo packageRemovedInfo,
            @NonNull PackageSender packageSender,
            boolean killApp,
            boolean removedBySystem,
            boolean isArchived) {
        final String removedPackage = packageRemovedInfo.mRemovedPackage;
        final String installerPackageName = packageRemovedInfo.mInstallerPackageName;
        final int[] broadcastUserIds = packageRemovedInfo.mBroadcastUsers;
        final int[] instantUserIds = packageRemovedInfo.mInstantUserIds;
        final SparseArray<int[]> broadcastAllowList = packageRemovedInfo.mBroadcastAllowList;
        final boolean dataRemoved = packageRemovedInfo.mDataRemoved;
        final boolean isUpdate = packageRemovedInfo.mIsUpdate;
        final boolean isRemovedPackageSystemUpdate =
                packageRemovedInfo.mIsRemovedPackageSystemUpdate;
        final boolean isRemovedForAllUsers = packageRemovedInfo.mRemovedForAllUsers;
        final boolean isStaticSharedLib = packageRemovedInfo.mIsStaticSharedLib;

        Bundle extras = new Bundle();
        extras.putInt(Intent.EXTRA_UID, packageRemovedInfo.mUid);
        extras.putBoolean(Intent.EXTRA_DATA_REMOVED, dataRemoved);
        extras.putBoolean(Intent.EXTRA_SYSTEM_UPDATE_UNINSTALL, isRemovedPackageSystemUpdate);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, !killApp);
        extras.putBoolean(Intent.EXTRA_USER_INITIATED, !removedBySystem);
        final boolean isReplace = isUpdate || isRemovedPackageSystemUpdate;
        if (isReplace || isArchived) {
            extras.putBoolean(Intent.EXTRA_REPLACING, true);
        }
        if (isArchived) {
            extras.putBoolean(Intent.EXTRA_ARCHIVAL, true);
        }
        extras.putBoolean(Intent.EXTRA_REMOVED_FOR_ALL_USERS, isRemovedForAllUsers);

        // Send PACKAGE_REMOVED broadcast to the respective installer.
        if (removedPackage != null && installerPackageName != null) {
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REMOVED,
                    removedPackage, extras, 0 /*flags*/,
                    installerPackageName, null, broadcastUserIds, instantUserIds, null, null);
        }
        if (isStaticSharedLib) {
            // When uninstalling static shared libraries, only the package's installer needs to be
            // sent a PACKAGE_REMOVED broadcast. There are no other intended recipients.
            return;
        }
        if (removedPackage != null) {
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REMOVED,
                    removedPackage, extras, 0, null /*targetPackage*/, null,
                    broadcastUserIds, instantUserIds, broadcastAllowList, null);
            sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_REMOVED_INTERNAL,
                    removedPackage, extras, 0 /*flags*/, PLATFORM_PACKAGE_NAME,
                    null /*finishedReceiver*/, broadcastUserIds, instantUserIds,
                    broadcastAllowList, null /*bOptions*/);
            if (dataRemoved && !isRemovedPackageSystemUpdate) {
                sendPackageBroadcastAndNotify(Intent.ACTION_PACKAGE_FULLY_REMOVED,
                        removedPackage, extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, null,
                        null, broadcastUserIds, instantUserIds, broadcastAllowList, null);
                packageSender.notifyPackageRemoved(removedPackage, packageRemovedInfo.mUid);
            }
        }
        if (packageRemovedInfo.mIsAppIdRemoved) {
            // If a system app's updates are uninstalled the UID is not actually removed. Some
            // services need to know the package name affected.
            if (isReplace) {
                extras.putString(Intent.EXTRA_PACKAGE_NAME, removedPackage);
            }

            sendPackageBroadcastAndNotify(Intent.ACTION_UID_REMOVED,
                    null, extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND,
                    null, null, broadcastUserIds, instantUserIds, broadcastAllowList, null);
        }
    }

    /**
     * Send broadcast intents for packages suspension changes.
     *
     * @param intent The action name of the suspension intent.
     * @param pkgList The names of packages which have suspension changes.
     * @param uidList The uids of packages which have suspension changes.
     * @param userId The user where packages reside.
     */
    void sendPackagesSuspendedOrUnsuspendedForUser(@NonNull Computer snapshot,
                                                   @NonNull String intent,
                                                   @NonNull String[] pkgList,
                                                   @NonNull int[] uidList,
                                                   boolean quarantined,
                                                   int userId) {
        final Bundle extras = new Bundle(3);
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidList);
        if (quarantined) {
            extras.putBoolean(Intent.EXTRA_QUARANTINED, true);
        }
        final int flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND;
        final Bundle options = new BroadcastOptions()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle();
        BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver =
                (callingUid, intentExtras) -> BroadcastHelper.filterExtrasChangedPackageList(
                        snapshot, callingUid, intentExtras);
        mHandler.post(() -> sendPackageBroadcast(intent, null /* pkg */,
                extras, flags, null /* targetPkg */, null /* finishedReceiver */,
                new int[]{userId}, null /* instantUserIds */, null /* broadcastAllowList */,
                filterExtrasForReceiver,
                options));
        notifyPackageMonitor(intent, null /* pkg */, extras, new int[]{userId},
                null /* instantUserIds */, null /* broadcastAllowList */, filterExtrasForReceiver);
    }

    void sendMyPackageSuspendedOrUnsuspended(@NonNull Computer snapshot,
                                             @NonNull String[] affectedPackages,
                                             boolean suspended,
                                             int userId) {
        final String action = suspended
                ? Intent.ACTION_MY_PACKAGE_SUSPENDED
                : Intent.ACTION_MY_PACKAGE_UNSUSPENDED;
        mHandler.post(() -> {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Slog.wtf(TAG, "IActivityManager null. Cannot send MY_PACKAGE_ "
                        + (suspended ? "" : "UN") + "SUSPENDED broadcasts");
                return;
            }
            final int[] targetUserIds = new int[] {userId};
            for (String packageName : affectedPackages) {
                final Bundle appExtras = suspended
                        ? SuspendPackageHelper.getSuspendedPackageAppExtras(
                                snapshot, packageName, userId, SYSTEM_UID)
                        : null;
                final Bundle intentExtras;
                if (appExtras != null) {
                    intentExtras = new Bundle(1);
                    intentExtras.putBundle(Intent.EXTRA_SUSPENDED_PACKAGE_EXTRAS, appExtras);
                } else {
                    intentExtras = null;
                }
                doSendBroadcast(action, null, intentExtras,
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, packageName, null,
                        targetUserIds, false, null, null, null);
            }
        });
    }

    /**
     * Send broadcast intents for packages distracting changes.
     *
     * @param pkgList The names of packages which have suspension changes.
     * @param uidList The uids of packages which have suspension changes.
     * @param userId The user where packages reside.
     */
    void sendDistractingPackagesChanged(@NonNull Computer snapshot,
                                        @NonNull String[] pkgList,
                                        @NonNull int[] uidList,
                                        int userId,
                                        int distractionFlags) {
        final Bundle extras = new Bundle();
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidList);
        extras.putInt(Intent.EXTRA_DISTRACTION_RESTRICTIONS, distractionFlags);

        mHandler.post(() -> sendPackageBroadcast(
                Intent.ACTION_DISTRACTING_PACKAGES_CHANGED, null /* pkg */,
                extras, Intent.FLAG_RECEIVER_REGISTERED_ONLY, null /* targetPkg */,
                null /* finishedReceiver */, new int[]{userId}, null /* instantUserIds */,
                null /* broadcastAllowList */,
                (callingUid, intentExtras) -> filterExtrasChangedPackageList(
                        snapshot, callingUid, intentExtras),
                null /* bOptions */));
    }

    void sendResourcesChangedBroadcastAndNotify(@NonNull Computer snapshot,
                                                boolean mediaStatus,
                                                boolean replacing,
                                                @NonNull ArrayList<AndroidPackage> packages) {
        final int size = packages.size();
        final String[] packageNames = new String[size];
        final int[] packageUids = new int[size];
        for (int i = 0; i < size; i++) {
            final AndroidPackage pkg = packages.get(i);
            packageNames[i] = pkg.getPackageName();
            packageUids[i] = pkg.getUid();
        }
        sendResourcesChangedBroadcast(snapshot, mediaStatus,
                replacing, packageNames, packageUids);
        notifyResourcesChanged(mediaStatus, replacing, packageNames, packageUids);
    }

    private void notifyPackageMonitor(@NonNull String action,
                                      @NonNull String pkg,
                                      @Nullable Bundle extras,
                                      @NonNull int[] userIds,
                                      @NonNull int[] instantUserIds,
                                      @Nullable SparseArray<int[]> broadcastAllowList,
                                      @Nullable BiFunction<Integer, Bundle, Bundle> filterExtras) {
        mPackageMonitorCallbackHelper.notifyPackageMonitor(action, pkg, extras, userIds,
                instantUserIds, broadcastAllowList, mHandler, filterExtras);
    }

    private void notifyResourcesChanged(boolean mediaStatus,
                                boolean replacing,
                                @NonNull String[] pkgNames,
                                @NonNull int[] uids) {
        mPackageMonitorCallbackHelper.notifyResourcesChanged(mediaStatus, replacing, pkgNames,
                uids, mHandler);
    }
}
