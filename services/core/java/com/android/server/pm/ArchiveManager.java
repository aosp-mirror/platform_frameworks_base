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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.ArchiveState.ArchiveActivityInfo;
import com.android.server.pm.pkg.PackageStateInternal;

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
final class ArchiveManager {

    private final Context mContext;
    private final PackageManagerService mPm;

    @Nullable
    private LauncherApps mLauncherApps;

    ArchiveManager(Context context, PackageManagerService mPm) {
        this.mContext = context;
        this.mPm = mPm;
    }

    void archiveApp(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull UserHandle user,
            @NonNull IntentSender intentSender) throws PackageManager.NameNotFoundException {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(user);
        Objects.requireNonNull(intentSender);

        Computer snapshot = mPm.snapshotComputer();
        int callingUid = Binder.getCallingUid();
        int userId = user.getIdentifier();
        String callingPackageName = snapshot.getNameForUid(callingUid);
        snapshot.enforceCrossUserPermission(callingUid, userId, true, true,
                "archiveApp");
        verifyCaller(callerPackageName, callingPackageName);
        PackageStateInternal ps = getPackageState(packageName, snapshot, callingUid, user);
        verifyInstaller(packageName, ps.getInstallSource());

        List<LauncherActivityInfo> mainActivities = getLauncherApps().getActivityList(
                ps.getPackageName(),
                new UserHandle(userId));
        // TODO(b/291569242) Verify that this list is not empty and return failure with intentsender

        storeArchiveState(ps, mainActivities, userId);

        // TODO(b/278553670) Add special strings for the delete dialog
        mPm.mInstallerService.uninstall(
                new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                callerPackageName, DELETE_KEEP_DATA, intentSender, userId);
    }

    @NonNull
    private static PackageStateInternal getPackageState(String packageName,
            Computer snapshot, int callingUid, UserHandle user)
            throws PackageManager.NameNotFoundException {
        PackageStateInternal ps = snapshot.getPackageStateFiltered(packageName, callingUid,
                user.getIdentifier());
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

    private void storeArchiveState(PackageStateInternal ps,
            List<LauncherActivityInfo> mainActivities, int userId)
            throws PackageManager.NameNotFoundException {
        List<ArchiveActivityInfo> activityInfos = new ArrayList<>();
        for (int i = 0; i < mainActivities.size(); i++) {
            // TODO(b/278553670) Extract and store launcher icons
            ArchiveActivityInfo activityInfo = new ArchiveActivityInfo(
                    mainActivities.get(i).getLabel().toString(),
                    Path.of("/TODO"), null);
            activityInfos.add(activityInfo);
        }

        InstallSource installSource = ps.getInstallSource();
        String installerPackageName = installSource.mUpdateOwnerPackageName != null
                ? installSource.mUpdateOwnerPackageName : installSource.mInstallerPackageName;

        synchronized (mPm.mLock) {
            getPackageSetting(ps.getPackageName(), userId).modifyUserState(userId).setArchiveState(
                    new ArchiveState(activityInfos, installerPackageName));
        }
    }

    @NonNull
    @GuardedBy("mPm.mLock")
    private PackageSetting getPackageSetting(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
        if (ps == null || !ps.getUserStateOrDefault(userId).isInstalled()) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("Package %s not found.", packageName));
        }
        return ps;
    }

    private static void verifyCaller(String callerPackageName, String callingPackageName) {
        if (!TextUtils.equals(callingPackageName, callerPackageName)) {
            throw new SecurityException(
                    TextUtils.formatSimple(
                            "The callerPackageName %s set by the caller doesn't match the "
                                    + "caller's own package name %s.",
                            callerPackageName,
                            callingPackageName));
        }
    }

    private static void verifyInstaller(String packageName, InstallSource installSource) {
        // TODO(b/291060290) Verify installer supports unarchiving
        if (installSource.mUpdateOwnerPackageName == null
                && installSource.mInstallerPackageName == null) {
            throw new SecurityException(
                    TextUtils.formatSimple("No installer found to archive app %s.",
                            packageName));
        }
    }
}
