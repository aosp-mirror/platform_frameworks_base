/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.content.pm.PackageManager.DELETE_KEEP_DATA;
import static android.os.PowerExemptionManager.REASON_PACKAGE_UNARCHIVE;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageArchiverService;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageArchiver;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.ArchiveState.ArchiveActivityInfo;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Responsible archiving apps and returning information about archived apps.
 *
 * <p> An archived app is in a state where the app is not fully on the device. APKs are removed
 * while the data directory is kept. Archived apps are included in the list of launcher apps where
 * tapping them re-installs the full app.
 */
public class PackageArchiverService extends IPackageArchiverService.Stub {

    /**
     * The maximum time granted for an app store to start a foreground service when unarchival
     * is requested.
     */
    // TODO(b/297358628) Make this configurable through a flag.
    private static final int DEFAULT_UNARCHIVE_FOREGROUND_TIMEOUT_MS = 120 * 1000;

    private final Context mContext;
    private final PackageManagerService mPm;

    @Nullable
    private LauncherApps mLauncherApps;

    public PackageArchiverService(Context context, PackageManagerService mPm) {
        this.mContext = context;
        this.mPm = mPm;
    }

    @Override
    public void requestArchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull IntentSender intentSender,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(intentSender);
        Objects.requireNonNull(userHandle);

        Computer snapshot = mPm.snapshotComputer();
        int userId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();
        int providedUid = snapshot.getPackageUid(callerPackageName, 0, userId);
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "archiveApp");
        verifyCaller(providedUid, binderUid);
        ArchiveState archiveState;
        try {
            archiveState = createArchiveState(packageName, userId);
            // TODO(b/282952870) Should be reverted if uninstall fails/cancels
            storeArchiveState(packageName, archiveState, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ParcelableException(e);
        }

        // TODO(b/278553670) Add special strings for the delete dialog
        mPm.mInstallerService.uninstall(
                new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                callerPackageName, DELETE_KEEP_DATA, intentSender, userId);
    }

    private ArchiveState createArchiveState(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        PackageStateInternal ps = getPackageState(packageName, mPm.snapshotComputer(),
                Binder.getCallingUid(), userId);
        String responsibleInstallerPackage = getResponsibleInstallerPackage(ps);
        if (responsibleInstallerPackage == null) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("No installer found to archive app %s.",
                            packageName));
        }

        List<LauncherActivityInfo> mainActivities = getLauncherActivityInfos(ps, userId);
        List<ArchiveActivityInfo> archiveActivityInfos = new ArrayList<>();
        for (int i = 0; i < mainActivities.size(); i++) {
            // TODO(b/278553670) Extract and store launcher icons
            ArchiveActivityInfo activityInfo = new ArchiveActivityInfo(
                    mainActivities.get(i).getLabel().toString(),
                    Path.of("/TODO"), null);
            archiveActivityInfos.add(activityInfo);
        }

        return new ArchiveState(archiveActivityInfos, responsibleInstallerPackage);
    }

    @Override
    public void requestUnarchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(userHandle);

        Computer snapshot = mPm.snapshotComputer();
        int userId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();
        int providedUid = snapshot.getPackageUid(callerPackageName, 0, userId);
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "unarchiveApp");
        verifyCaller(providedUid, binderUid);
        PackageStateInternal ps;
        try {
            ps = getPackageState(packageName, snapshot, binderUid, userId);
            verifyArchived(ps, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ParcelableException(e);
        }
        String installerPackage = getResponsibleInstallerPackage(ps);
        if (installerPackage == null) {
            throw new ParcelableException(
                    new PackageManager.NameNotFoundException(
                            TextUtils.formatSimple("No installer found to unarchive app %s.",
                                    packageName)));
        }

        mPm.mHandler.post(() -> unarchiveInternal(packageName, userHandle, installerPackage));
    }

    private void verifyArchived(PackageStateInternal ps, int userId)
            throws PackageManager.NameNotFoundException {
        PackageUserStateInternal userState = ps.getUserStateOrDefault(userId);
        // TODO(b/288142708) Check for isInstalled false here too.
        if (userState.getArchiveState() == null) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("Package %s is not currently archived.",
                            ps.getPackageName()));
        }
    }

    @RequiresPermission(
            allOf = {
                    Manifest.permission.INTERACT_ACROSS_USERS,
                    android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                    android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                    android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND},
            conditional = true)
    private void unarchiveInternal(String packageName, UserHandle userHandle,
            String installerPackage) {
        int userId = userHandle.getIdentifier();
        Intent unarchiveIntent = new Intent(Intent.ACTION_UNARCHIVE_PACKAGE);
        unarchiveIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        unarchiveIntent.putExtra(PackageArchiver.EXTRA_UNARCHIVE_PACKAGE_NAME, packageName);
        unarchiveIntent.putExtra(PackageArchiver.EXTRA_UNARCHIVE_ALL_USERS,
                userId == UserHandle.USER_ALL);
        unarchiveIntent.setPackage(installerPackage);

        // If the unarchival is requested for all users, the current user is used for unarchival.
        UserHandle userForUnarchival = userId == UserHandle.USER_ALL
                ? UserHandle.of(mPm.mUserManager.getCurrentUserId())
                : userHandle;
        mContext.sendOrderedBroadcastAsUser(
                unarchiveIntent,
                userForUnarchival,
                /* receiverPermission = */ null,
                AppOpsManager.OP_NONE,
                createUnarchiveOptions(),
                /* resultReceiver= */ null,
                /* scheduler= */ null,
                /* initialCode= */ 0,
                /* initialData= */ null,
                /* initialExtras= */ null);
    }

    private List<LauncherActivityInfo> getLauncherActivityInfos(PackageStateInternal ps,
            int userId) throws PackageManager.NameNotFoundException {
        List<LauncherActivityInfo> mainActivities =
                Binder.withCleanCallingIdentity(() -> getLauncherApps().getActivityList(
                        ps.getPackageName(),
                        new UserHandle(userId)));
        if (mainActivities.isEmpty()) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("The app %s does not have a main activity.",
                            ps.getPackageName()));
        }

        return mainActivities;
    }

    @RequiresPermission(anyOf = {android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
            android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
            android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND})
    private Bundle createUnarchiveOptions() {
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppAllowlist(getUnarchiveForegroundTimeout(),
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                REASON_PACKAGE_UNARCHIVE, "");
        return options.toBundle();
    }

    private static int getUnarchiveForegroundTimeout() {
        return DEFAULT_UNARCHIVE_FOREGROUND_TIMEOUT_MS;
    }

    private String getResponsibleInstallerPackage(PackageStateInternal ps) {
        return ps.getInstallSource().mUpdateOwnerPackageName == null
                ? ps.getInstallSource().mInstallerPackageName
                : ps.getInstallSource().mUpdateOwnerPackageName;
    }

    @NonNull
    private static PackageStateInternal getPackageState(String packageName,
            Computer snapshot, int callingUid, int userId)
            throws PackageManager.NameNotFoundException {
        PackageStateInternal ps = snapshot.getPackageStateFiltered(packageName, callingUid,
                userId);
        if (ps == null) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("Package %s not found.", packageName));
        }
        return ps;
    }

    private LauncherApps getLauncherApps() {
        if (mLauncherApps == null) {
            mLauncherApps = mContext.getSystemService(LauncherApps.class);
        }
        return mLauncherApps;
    }

    private void storeArchiveState(String packageName, ArchiveState archiveState, int userId)
            throws PackageManager.NameNotFoundException {
        synchronized (mPm.mLock) {
            PackageSetting packageSetting = getPackageSettingLocked(packageName, userId);
            packageSetting
                    .modifyUserState(userId)
                    .setArchiveState(archiveState);
        }
    }

    @NonNull
    @GuardedBy("mPm.mLock")
    private PackageSetting getPackageSettingLocked(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
        // Shouldn't happen, we already verify presence of the package in getPackageState()
        if (ps == null || !ps.getUserStateOrDefault(userId).isInstalled()) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("Package %s not found.", packageName));
        }
        return ps;
    }

    private static void verifyCaller(int providedUid, int binderUid) {
        if (providedUid != binderUid) {
            throw new SecurityException(
                    TextUtils.formatSimple(
                            "The UID %s of callerPackageName set by the caller doesn't match the "
                                    + "caller's actual UID %s.",
                            providedUid,
                            binderUid));
        }
    }
}
