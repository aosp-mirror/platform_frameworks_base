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

import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.ComponentOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.content.pm.ArchivedActivityInfo.bytesFromBitmap;
import static android.content.pm.ArchivedActivityInfo.drawableToBitmap;
import static android.content.pm.PackageManager.DELETE_ARCHIVE;
import static android.content.pm.PackageManager.DELETE_KEEP_DATA;
import static android.content.pm.PackageManager.INSTALL_UNARCHIVE_DRAFT;
import static android.os.PowerExemptionManager.REASON_PACKAGE_UNARCHIVE;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ArchivedActivityParcel;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelableException;
import android.os.Process;
import android.os.SELinux;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.ArchiveState.ArchiveActivityInfo;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Responsible archiving apps and returning information about archived apps.
 *
 * <p> An archived app is in a state where the app is not fully on the device. APKs are removed
 * while the data directory is kept. Archived apps are included in the list of launcher apps where
 * tapping them re-installs the full app.
 */
public class PackageArchiver {

    private static final String TAG = "PackageArchiverService";

    /**
     * The maximum time granted for an app store to start a foreground service when unarchival
     * is requested.
     */
    // TODO(b/297358628) Make this configurable through a flag.
    private static final int DEFAULT_UNARCHIVE_FOREGROUND_TIMEOUT_MS = 120 * 1000;

    private static final String ARCHIVE_ICONS_DIR = "package_archiver";

    private final Context mContext;
    private final PackageManagerService mPm;

    @Nullable
    private LauncherApps mLauncherApps;

    @Nullable
    private AppOpsManager mAppOpsManager;

    PackageArchiver(Context context, PackageManagerService mPm) {
        this.mContext = context;
        this.mPm = mPm;
    }

    /** Returns whether a package is archived for a user. */
    public static boolean isArchived(PackageUserState userState) {
        return userState.getArchiveState() != null && !userState.isInstalled();
    }

    void requestArchive(
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
        if (!PackageManagerServiceUtils.isRootOrShell(binderUid)) {
            verifyCaller(snapshot.getPackageUid(callerPackageName, 0, userId), binderUid);
        }
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "archiveApp");
        CompletableFuture<ArchiveState> archiveStateFuture;
        try {
            archiveStateFuture = createArchiveState(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, TextUtils.formatSimple("Failed to archive %s with message %s",
                    packageName, e.getMessage()));
            throw new ParcelableException(e);
        }

        archiveStateFuture
                .thenAccept(
                        archiveState -> {
                            // TODO(b/282952870) Should be reverted if uninstall fails/cancels
                            try {
                                storeArchiveState(packageName, archiveState, userId);
                            } catch (PackageManager.NameNotFoundException e) {
                                sendFailureStatus(intentSender, packageName, e.getMessage());
                                return;
                            }

                            // TODO(b/278553670) Add special strings for the delete dialog
                            mPm.mInstallerService.uninstall(
                                    new VersionedPackage(packageName,
                                            PackageManager.VERSION_CODE_HIGHEST),
                                    callerPackageName,
                                    DELETE_ARCHIVE | DELETE_KEEP_DATA,
                                    intentSender,
                                    userId,
                                    binderUid);
                        })
                .exceptionally(
                        e -> {
                            sendFailureStatus(intentSender, packageName, e.getMessage());
                            return null;
                        });
    }

    /** Creates archived state for the package and user. */
    private CompletableFuture<ArchiveState> createArchiveState(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        PackageStateInternal ps = getPackageState(packageName, mPm.snapshotComputer(),
                Binder.getCallingUid(), userId);
        String responsibleInstallerPackage = getResponsibleInstallerPackage(ps);
        verifyInstaller(responsibleInstallerPackage, userId);
        verifyOptOutStatus(packageName,
                UserHandle.getUid(userId, UserHandle.getUid(userId, ps.getAppId())));

        List<LauncherActivityInfo> mainActivities = getLauncherActivityInfos(ps.getPackageName(),
                userId);
        final CompletableFuture<ArchiveState> archiveState = new CompletableFuture<>();
        mPm.mHandler.post(() -> {
            try {
                archiveState.complete(
                        createArchiveStateInternal(packageName, userId, mainActivities,
                                responsibleInstallerPackage));
            } catch (IOException e) {
                archiveState.completeExceptionally(e);
            }
        });
        return archiveState;
    }

    static ArchiveState createArchiveState(@NonNull ArchivedPackageParcel archivedPackage,
            int userId, String installerPackage) {
        try {
            var packageName = archivedPackage.packageName;
            var mainActivities = archivedPackage.archivedActivities;
            List<ArchiveActivityInfo> archiveActivityInfos = new ArrayList<>(mainActivities.length);
            for (int i = 0, size = mainActivities.length; i < size; ++i) {
                var mainActivity = mainActivities[i];
                Path iconPath = storeIconForParcel(packageName, mainActivity, userId, i);
                ArchiveActivityInfo activityInfo =
                        new ArchiveActivityInfo(
                                mainActivity.title,
                                mainActivity.originalComponentName,
                                iconPath,
                                null);
                archiveActivityInfos.add(activityInfo);
            }

            return new ArchiveState(archiveActivityInfos, installerPackage);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create archive state", e);
            return null;
        }
    }

    ArchiveState createArchiveStateInternal(String packageName, int userId,
            List<LauncherActivityInfo> mainActivities, String installerPackage)
            throws IOException {
        final int iconSize = mContext.getSystemService(
                ActivityManager.class).getLauncherLargeIconSize();

        List<ArchiveActivityInfo> archiveActivityInfos = new ArrayList<>(mainActivities.size());
        for (int i = 0, size = mainActivities.size(); i < size; i++) {
            LauncherActivityInfo mainActivity = mainActivities.get(i);
            Path iconPath = storeIcon(packageName, mainActivity, userId, i, iconSize);
            ArchiveActivityInfo activityInfo =
                    new ArchiveActivityInfo(
                            mainActivity.getLabel().toString(),
                            mainActivity.getComponentName(),
                            iconPath,
                            null);
            archiveActivityInfos.add(activityInfo);
        }

        return new ArchiveState(archiveActivityInfos, installerPackage);
    }

    // TODO(b/298452477) Handle monochrome icons.
    private static Path storeIconForParcel(String packageName, ArchivedActivityParcel mainActivity,
            @UserIdInt int userId, int index) throws IOException {
        if (mainActivity.iconBitmap == null) {
            return null;
        }
        File iconsDir = createIconsDir(userId);
        File iconFile = new File(iconsDir, packageName + "-" + index + ".png");
        try (FileOutputStream out = new FileOutputStream(iconFile)) {
            out.write(mainActivity.iconBitmap);
            out.flush();
        }
        return iconFile.toPath();
    }

    @VisibleForTesting
    Path storeIcon(String packageName, LauncherActivityInfo mainActivity,
            @UserIdInt int userId, int index, int iconSize) throws IOException {
        int iconResourceId = mainActivity.getActivityInfo().getIconResource();
        if (iconResourceId == 0) {
            // The app doesn't define an icon. No need to store anything.
            return null;
        }
        File iconsDir = createIconsDir(userId);
        File iconFile = new File(iconsDir, packageName + "-" + index + ".png");
        Bitmap icon = drawableToBitmap(mainActivity.getIcon(/* density= */ 0), iconSize);
        try (FileOutputStream out = new FileOutputStream(iconFile)) {
            // Note: Quality is ignored for PNGs.
            if (!icon.compress(Bitmap.CompressFormat.PNG, /* quality= */ 100, out)) {
                throw new IOException(TextUtils.formatSimple("Failure to store icon file %s",
                        iconFile.getName()));
            }
            out.flush();
        }
        return iconFile.toPath();
    }

    private void verifyInstaller(String installerPackage, int userId)
            throws PackageManager.NameNotFoundException {
        if (TextUtils.isEmpty(installerPackage)) {
            throw new PackageManager.NameNotFoundException("No installer found");
        }
        // Allow shell for easier development.
        if ((Binder.getCallingUid() != Process.SHELL_UID)
                && !verifySupportsUnarchival(installerPackage, userId)) {
            throw new PackageManager.NameNotFoundException("Installer does not support unarchival");
        }
    }

    /**
     * Returns true if {@code installerPackage} supports unarchival being able to handle
     * {@link Intent#ACTION_UNARCHIVE_PACKAGE}
     */
    public boolean verifySupportsUnarchival(String installerPackage, int userId) {
        if (TextUtils.isEmpty(installerPackage)) {
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_UNARCHIVE_PACKAGE).setPackage(installerPackage);

        ParceledListSlice<ResolveInfo> intentReceivers =
                Binder.withCleanCallingIdentity(
                        () -> mPm.queryIntentReceivers(mPm.snapshotComputer(),
                                intent, /* resolvedType= */ null, /* flags= */ 0, userId));
        return intentReceivers != null && !intentReceivers.getList().isEmpty();
    }

    /**
     * Returns true if the app is archivable.
     */
    // TODO(b/299299569) Exclude system apps
    public boolean isAppArchivable(@NonNull String packageName, @NonNull UserHandle user) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(user);

        Computer snapshot = mPm.snapshotComputer();
        int userId = user.getIdentifier();
        int binderUid = Binder.getCallingUid();
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "isAppArchivable");
        PackageStateInternal ps;
        try {
            ps = getPackageState(packageName, mPm.snapshotComputer(),
                    Binder.getCallingUid(), userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ParcelableException(e);
        }

        if (isAppOptedOutOfArchiving(packageName, ps.getAppId())) {
            return false;
        }

        try {
            verifyInstaller(getResponsibleInstallerPackage(ps), userId);
            getLauncherActivityInfos(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return true;
    }

    /**
     * Returns true if user has opted the app out of archiving through system settings.
     */
    // TODO(b/304256918) Switch this to a separate OP code for archiving.
    private boolean isAppOptedOutOfArchiving(String packageName, int uid) {
        return Binder.withCleanCallingIdentity(() ->
                getAppOpsManager().checkOp(AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                        uid, packageName) == MODE_IGNORED);
    }

    private void verifyOptOutStatus(String packageName, int uid)
            throws PackageManager.NameNotFoundException {
        if (isAppOptedOutOfArchiving(packageName, uid)) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("The app %s is opted out of archiving.", packageName));
        }
    }

    void requestUnarchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(userHandle);

        Computer snapshot = mPm.snapshotComputer();
        int userId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();
        if (!PackageManagerServiceUtils.isRootOrShell(binderUid)) {
            verifyCaller(snapshot.getPackageUid(callerPackageName, 0, userId), binderUid);
        }
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "unarchiveApp");
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

        int draftSessionId;
        try {
            draftSessionId = createDraftSession(packageName, installerPackage, userId);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw ExceptionUtils.wrap((IOException) e.getCause());
            } else {
                throw e;
            }
        }

        mPm.mHandler.post(
                () -> unarchiveInternal(packageName, userHandle, installerPackage, draftSessionId));
    }

    private int createDraftSession(String packageName, String installerPackage, int userId) {
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sessionParams.setAppPackageName(packageName);
        sessionParams.installFlags = INSTALL_UNARCHIVE_DRAFT;
        int installerUid = mPm.snapshotComputer().getPackageUid(installerPackage, 0, userId);
        // Handles case of repeated unarchival calls for the same package.
        int existingSessionId = mPm.mInstallerService.getExistingDraftSessionId(installerUid,
                sessionParams,
                userId);
        if (existingSessionId != PackageInstaller.SessionInfo.INVALID_ID) {
            return existingSessionId;
        }

        int sessionId = Binder.withCleanCallingIdentity(
                () -> mPm.mInstallerService.createSessionInternal(
                        sessionParams,
                        installerPackage, mContext.getAttributionTag(),
                        installerUid,
                        userId));
        // TODO(b/297358628) Also cleanup sessions upon device restart.
        mPm.mHandler.postDelayed(() -> mPm.mInstallerService.cleanupDraftIfUnclaimed(sessionId),
                getUnarchiveForegroundTimeout());
        return sessionId;
    }

    /**
     * Returns the icon of an archived app. This is the icon of the main activity of the app.
     *
     * <p> The icon is returned without any treatment/overlay. In the rare case the app had multiple
     * launcher activities, only one of the icons is returned arbitrarily.
     */
    public Bitmap getArchivedAppIcon(@NonNull String packageName, @NonNull UserHandle user) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(user);

        Computer snapshot = mPm.snapshotComputer();
        int callingUid = Binder.getCallingUid();
        int userId = user.getIdentifier();
        PackageStateInternal ps;
        try {
            ps = getPackageState(packageName, snapshot, callingUid, userId);
            snapshot.enforceCrossUserPermission(callingUid, userId, true, false,
                    "getArchivedAppIcon");
            verifyArchived(ps, userId);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ParcelableException(e);
        }

        List<ArchiveActivityInfo> activityInfos = ps.getUserStateOrDefault(
                userId).getArchiveState().getActivityInfos();
        if (activityInfos.size() == 0) {
            return null;
        }

        // TODO(b/298452477) Handle monochrome icons.
        // In the rare case the archived app defined more than two launcher activities, we choose
        // the first one arbitrarily.
        return includeCloudOverlay(decodeIcon(activityInfos.get(0)));
    }

    @VisibleForTesting
    Bitmap decodeIcon(ArchiveActivityInfo archiveActivityInfo) {
        return BitmapFactory.decodeFile(archiveActivityInfo.getIconBitmap().toString());
    }

    Bitmap includeCloudOverlay(Bitmap bitmap) {
        Drawable cloudDrawable =
                mContext.getResources()
                        .getDrawable(R.drawable.archived_app_cloud_overlay, mContext.getTheme());
        if (cloudDrawable == null) {
            Slog.e(TAG, "Unable to locate cloud overlay for archived app!");
            return bitmap;
        }
        BitmapDrawable appIconDrawable = new BitmapDrawable(mContext.getResources(), bitmap);
        PorterDuffColorFilter colorFilter =
                new PorterDuffColorFilter(
                        Color.argb(0.32f /* alpha */, 0f /* red */, 0f /* green */, 0f /* blue */),
                        PorterDuff.Mode.SRC_ATOP);
        appIconDrawable.setColorFilter(colorFilter);
        appIconDrawable.setBounds(
                0 /* left */,
                0 /* top */,
                cloudDrawable.getIntrinsicWidth(),
                cloudDrawable.getIntrinsicHeight());
        LayerDrawable layerDrawable =
                new LayerDrawable(new Drawable[]{appIconDrawable, cloudDrawable});
        final int iconSize = mContext.getSystemService(
                ActivityManager.class).getLauncherLargeIconSize();
        Bitmap appIconWithCloudOverlay = drawableToBitmap(layerDrawable, iconSize);
        bitmap.recycle();
        return appIconWithCloudOverlay;
    }

    private void verifyArchived(PackageStateInternal ps, int userId)
            throws PackageManager.NameNotFoundException {
        PackageUserStateInternal userState = ps.getUserStateOrDefault(userId);
        if (!isArchived(userState)) {
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
            String installerPackage, int unarchiveId) {
        int userId = userHandle.getIdentifier();
        Intent unarchiveIntent = new Intent(Intent.ACTION_UNARCHIVE_PACKAGE);
        unarchiveIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        unarchiveIntent.putExtra(PackageInstaller.EXTRA_UNARCHIVE_ID, unarchiveId);
        unarchiveIntent.putExtra(PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME, packageName);
        unarchiveIntent.putExtra(PackageInstaller.EXTRA_UNARCHIVE_ALL_USERS,
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

    List<LauncherActivityInfo> getLauncherActivityInfos(String packageName,
            int userId) throws PackageManager.NameNotFoundException {
        List<LauncherActivityInfo> mainActivities =
                Binder.withCleanCallingIdentity(() -> getLauncherApps().getActivityList(
                        packageName,
                        new UserHandle(userId)));
        if (mainActivities.isEmpty()) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("The app %s does not have a main activity.",
                            packageName));
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

    static String getResponsibleInstallerPackage(PackageStateInternal ps) {
        return TextUtils.isEmpty(ps.getInstallSource().mUpdateOwnerPackageName)
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

    private AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        }
        return mAppOpsManager;
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

    private void sendFailureStatus(IntentSender statusReceiver, String packageName,
            String message) {
        Slog.d(TAG, TextUtils.formatSimple("Failed to archive %s with message %s", packageName,
                message));
        final Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        try {
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    MODE_BACKGROUND_ACTIVITY_START_DENIED);
            statusReceiver.sendIntent(mContext, 0, fillIn, /* onFinished= */ null,
                    /* handler= */ null, /* requiredPermission= */ null, options.toBundle());
        } catch (IntentSender.SendIntentException e) {
            Slog.e(
                    TAG,
                    TextUtils.formatSimple("Failed to send failure status for %s with message %s",
                            packageName, message),
                    e);
        }
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

    private static File createIconsDir(@UserIdInt int userId) throws IOException {
        File iconsDir = getIconsDir(userId);
        if (!iconsDir.isDirectory()) {
            iconsDir.delete();
            iconsDir.mkdirs();
            if (!iconsDir.isDirectory()) {
                throw new IOException("Unable to create directory " + iconsDir);
            }
        }
        SELinux.restorecon(iconsDir);
        return iconsDir;
    }

    private static File getIconsDir(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), ARCHIVE_ICONS_DIR);
    }

    private static byte[] bytesFromBitmapFile(Path path) throws IOException {
        if (path == null) {
            return null;
        }
        // Technically we could just read the bytes, but we want to be sure we store the
        // right format.
        return bytesFromBitmap(BitmapFactory.decodeFile(path.toString()));
    }

    /**
     * Creates serializable archived activities from existing ArchiveState.
     */
    static ArchivedActivityParcel[] createArchivedActivities(ArchiveState archiveState)
            throws IOException {
        var infos = archiveState.getActivityInfos();
        if (infos == null || infos.isEmpty()) {
            throw new IllegalArgumentException("No activities in archive state");
        }

        List<ArchivedActivityParcel> activities = new ArrayList<>(infos.size());
        for (int i = 0, size = infos.size(); i < size; ++i) {
            var info = infos.get(i);
            if (info == null) {
                continue;
            }
            var archivedActivity = new ArchivedActivityParcel();
            archivedActivity.title = info.getTitle();
            archivedActivity.originalComponentName = info.getOriginalComponentName();
            archivedActivity.iconBitmap = bytesFromBitmapFile(info.getIconBitmap());
            archivedActivity.monochromeIconBitmap = bytesFromBitmapFile(
                    info.getMonochromeIconBitmap());
            activities.add(archivedActivity);
        }

        if (activities.isEmpty()) {
            throw new IllegalArgumentException(
                    "Failed to extract title and icon of main activities");
        }

        return activities.toArray(new ArchivedActivityParcel[activities.size()]);
    }

    /**
     * Creates serializable archived activities from launcher activities.
     */
    static ArchivedActivityParcel[] createArchivedActivities(List<LauncherActivityInfo> infos,
            int iconSize) throws IOException {
        if (infos == null || infos.isEmpty()) {
            throw new IllegalArgumentException("No launcher activities");
        }

        List<ArchivedActivityParcel> activities = new ArrayList<>(infos.size());
        for (int i = 0, size = infos.size(); i < size; ++i) {
            var info = infos.get(i);
            if (info == null) {
                continue;
            }
            var archivedActivity = new ArchivedActivityParcel();
            archivedActivity.title = info.getLabel().toString();
            archivedActivity.originalComponentName = info.getComponentName();
            archivedActivity.iconBitmap = info.getActivityInfo().getIconResource() == 0 ? null :
                    bytesFromBitmap(drawableToBitmap(info.getIcon(/* density= */ 0), iconSize));
            // TODO(b/298452477) Handle monochrome icons.
            archivedActivity.monochromeIconBitmap = null;
            activities.add(archivedActivity);
        }

        if (activities.isEmpty()) {
            throw new IllegalArgumentException(
                    "Failed to extract title and icon of main activities");
        }

        return activities.toArray(new ArchivedActivityParcel[activities.size()]);
    }
}
