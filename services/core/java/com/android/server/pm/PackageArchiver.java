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

import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_PERMISSION_DENIED;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.content.pm.ArchivedActivityInfo.bytesFromBitmap;
import static android.content.pm.ArchivedActivityInfo.drawableToBitmap;
import static android.content.pm.PackageInstaller.EXTRA_UNARCHIVE_STATUS;
import static android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION;
import static android.content.pm.PackageInstaller.UNARCHIVAL_OK;
import static android.content.pm.PackageInstaller.UNARCHIVAL_STATUS_UNSET;
import static android.content.pm.PackageManager.DELETE_ALL_USERS;
import static android.content.pm.PackageManager.DELETE_ARCHIVE;
import static android.content.pm.PackageManager.DELETE_KEEP_DATA;
import static android.content.pm.PackageManager.INSTALL_UNARCHIVE;
import static android.content.pm.PackageManager.INSTALL_UNARCHIVE_DRAFT;
import static android.graphics.drawable.AdaptiveIconDrawable.getExtraInsetFraction;
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
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ArchivedActivityParcel;
import android.content.pm.ArchivedPackageInfo;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.Flags;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ExceptionUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.ArchiveState.ArchiveActivityInfo;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final boolean DEBUG = true;

    public static final String EXTRA_UNARCHIVE_INTENT_SENDER =
            "android.content.pm.extra.UNARCHIVE_INTENT_SENDER";

    /**
     * The maximum time granted for an app store to start a foreground service when unarchival
     * is requested.
     */
    // TODO(b/297358628) Make this configurable through a flag.
    private static final int DEFAULT_UNARCHIVE_FOREGROUND_TIMEOUT_MS = 120 * 1000;

    private static final String ARCHIVE_ICONS_DIR = "package_archiver";

    private static final String ACTION_UNARCHIVE_DIALOG =
            "com.android.intent.action.UNARCHIVE_DIALOG";
    private static final String ACTION_UNARCHIVE_ERROR_DIALOG =
            "com.android.intent.action.UNARCHIVE_ERROR_DIALOG";

    private static final String EXTRA_REQUIRED_BYTES =
            "com.android.content.pm.extra.UNARCHIVE_EXTRA_REQUIRED_BYTES";
    private static final String EXTRA_INSTALLER_PACKAGE_NAME =
            "com.android.content.pm.extra.UNARCHIVE_INSTALLER_PACKAGE_NAME";
    private static final String EXTRA_INSTALLER_TITLE =
            "com.android.content.pm.extra.UNARCHIVE_INSTALLER_TITLE";

    private static final PorterDuffColorFilter OPACITY_LAYER_FILTER =
            new PorterDuffColorFilter(
                    Color.argb(0.5f /* alpha */, 0f /* red */, 0f /* green */, 0f /* blue */),
                    PorterDuff.Mode.SRC_ATOP);

    private final Context mContext;
    private final PackageManagerService mPm;

    private final AppStateHelper mAppStateHelper;

    @Nullable
    private LauncherApps mLauncherApps;

    @Nullable
    private AppOpsManager mAppOpsManager;

    @Nullable
    private UserManager mUserManager;

    /* IntentSender store that maps key: {userId, appPackageName} to respective existing attached
     unarchival intent sender. */
    private final Map<Pair<Integer, String>, IntentSender> mLauncherIntentSenders;

    PackageArchiver(Context context, PackageManagerService mPm) {
        this.mContext = context;
        this.mPm = mPm;
        this.mAppStateHelper = new AppStateHelper(mContext);
        this.mLauncherIntentSenders = new HashMap<>();
    }

    /** Returns whether a package is archived for a user. */
    public static boolean isArchived(PackageUserState userState) {
        return userState.getArchiveState() != null && !userState.isInstalled();
    }

    public static boolean isArchivingEnabled() {
        return Flags.archiving();
    }

    @VisibleForTesting
    void requestArchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull IntentSender intentSender,
            @NonNull UserHandle userHandle) {
        requestArchive(packageName, callerPackageName, /*flags=*/ 0, intentSender, userHandle);
    }

    void requestArchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            int flags,
            @NonNull IntentSender intentSender,
            @NonNull UserHandle userHandle) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(intentSender);
        Objects.requireNonNull(userHandle);

        Slog.i(TAG,
                TextUtils.formatSimple("Requested archival of package %s for user %s.", packageName,
                        userHandle.getIdentifier()));
        Computer snapshot = mPm.snapshotComputer();
        int binderUserId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();
        int binderPid = Binder.getCallingPid();
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(binderUid)) {
            verifyCaller(snapshot.getPackageUid(callerPackageName, 0, binderUserId), binderUid);
        }

        final boolean deleteAllUsers = (flags & PackageManager.DELETE_ALL_USERS) != 0;
        final int[] users = deleteAllUsers ? mPm.mInjector.getUserManagerInternal().getUserIds()
                : new int[]{binderUserId};
        for (int userId : users) {
            snapshot.enforceCrossUserPermission(binderUid, userId,
                    /*requireFullPermission=*/ true, /*checkShell=*/ true,
                    "archiveApp");
        }
        verifyUninstallPermissions();

        CompletableFuture<Void>[] archiveStateStored = new CompletableFuture[users.length];
        try {
            for (int i = 0, size = users.length; i < size; ++i) {
                archiveStateStored[i] = createAndStoreArchiveState(packageName, users[i]);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, TextUtils.formatSimple("Failed to archive %s with message %s",
                    packageName, e.getMessage()));
            throw new ParcelableException(e);
        }

        final int deleteFlags = DELETE_ARCHIVE | DELETE_KEEP_DATA
                | (deleteAllUsers ? DELETE_ALL_USERS : 0);

        CompletableFuture.allOf(archiveStateStored).thenAccept(ignored ->
                mPm.mInstallerService.uninstall(
                        new VersionedPackage(packageName,
                                PackageManager.VERSION_CODE_HIGHEST),
                        callerPackageName,
                        deleteFlags,
                        intentSender,
                        binderUserId,
                        binderUid,
                        binderPid)
        ).exceptionally(
                e -> {
                    Slog.e(TAG, TextUtils.formatSimple("Failed to archive %s with message %s",
                            packageName, e.getMessage()));
                    sendFailureStatus(intentSender, packageName, e.getMessage());
                    return null;
                }
        );
    }

    /**
     * Starts unarchival for the package corresponding to the startActivity intent. Note that this
     * will work only if the caller is the default/Home Launcher or if activity is started via Shell
     * identity.
     */
    @NonNull
    public int requestUnarchiveOnActivityStart(@Nullable Intent intent,
            @Nullable String callerPackageName, int userId, int callingUid) {
        String packageName = getPackageNameFromIntent(intent);
        if (packageName == null) {
            Slog.e(TAG, "packageName cannot be null for unarchival!");
            return START_CLASS_NOT_FOUND;
        }
        if (callerPackageName == null) {
            Slog.e(TAG, "callerPackageName cannot be null for unarchival!");
            return START_CLASS_NOT_FOUND;
        }

        if (!isCallerQualifiedForUnarchival(callerPackageName, callingUid, userId)) {
            Slog.e(TAG, TextUtils.formatSimple(
                    "callerPackageName: %s does not qualify for unarchival of package: " + "%s!",
                    callerPackageName, packageName));
            return START_PERMISSION_DENIED;
        }

        try {
            boolean openAppDetailsIfOngoingUnarchival = getAppOpsManager().checkOp(
                    AppOpsManager.OP_UNARCHIVAL_CONFIRMATION, callingUid, callerPackageName)
                    == MODE_ALLOWED;
            if (openAppDetailsIfOngoingUnarchival) {
                PackageInstaller.SessionInfo activeUnarchivalSession = getActiveUnarchivalSession(
                        packageName, userId);
                if (activeUnarchivalSession != null) {
                    mPm.mHandler.post(() -> {
                        Slog.i(TAG, "Opening app details page for ongoing unarchival of: "
                                + packageName);
                        getLauncherApps().startPackageInstallerSessionDetailsActivity(
                                activeUnarchivalSession, null, null);
                    });
                    return START_ABORTED;
                }
            }

            Slog.i(TAG, TextUtils.formatSimple("Unarchival is starting for: %s", packageName));

            requestUnarchive(packageName, callerPackageName,
                    getOrCreateLauncherListener(userId, packageName),
                    UserHandle.of(userId),
                    getAppOpsManager().checkOp(
                            AppOpsManager.OP_UNARCHIVAL_CONFIRMATION, callingUid, callerPackageName)
                            == MODE_ALLOWED);
        } catch (Throwable t) {
            Slog.e(TAG, TextUtils.formatSimple(
                    "Unexpected error occurred while unarchiving package %s: %s.", packageName,
                    t.getLocalizedMessage()));
        }

        // We return STATUS_ABORTED because:
        // 1. Archived App is not actually present during activity start. Hence the unarchival
        // start should be treated as an error code.
        // 2. STATUS_ABORTED is not visible to the end consumers. Hence, it will not change user
        // experience.
        // 3. Returning STATUS_ABORTED helps us avoid manually handling of different cases like
        // aborting activity options, animations etc in the Windows Manager.
        return START_ABORTED;
    }

    private boolean isCallerQualifiedForUnarchival(String callerPackageName, int callingUid,
            int userId) {
        // TODO(b/311619990): Remove dependency on SHELL_UID for testing
        if (callingUid == Process.SHELL_UID) {
            return true;
        }
        String currentLauncherPackageName = getCurrentLauncherPackageName(getParentUserId(userId));
        if (currentLauncherPackageName != null && TextUtils.equals(
                callerPackageName, currentLauncherPackageName)) {
            return true;
        }
        Slog.w(TAG, TextUtils.formatSimple(
                "Requester of unarchival: %s is not the default launcher package: %s.",
                callerPackageName, currentLauncherPackageName));
        // When the default launcher is not set, or when the current caller is not the default
        // launcher, allow the caller to directly request unarchive if it is a launcher app
        // that is a pre-installed system app.
        final Computer snapshot = mPm.snapshotComputer();
        final PackageStateInternal ps = snapshot.getPackageStateInternal(callerPackageName);
        final boolean isSystem = ps != null && ps.isSystem();
        return isSystem && isLauncherApp(snapshot, callerPackageName, userId);
    }

    private boolean isLauncherApp(Computer snapshot, String packageName, int userId) {
        final Intent intent = snapshot.getHomeIntent();
        intent.setPackage(packageName);
        List<ResolveInfo> launcherActivities = snapshot.queryIntentActivitiesInternal(
                intent, null /* resolvedType */, 0 /* flags */, userId);
        return !launcherActivities.isEmpty();
    }

    // Profiles share their UI and default apps, so we have to get the profile parent before
    // fetching the default launcher.
    private int getParentUserId(int userId) {
        UserInfo profileParent = getUserManager().getProfileParent(userId);
        return profileParent == null ? userId : profileParent.id;
    }

    /**
     * Returns true if the componentName targeted by the intent corresponds to that of an archived
     * app.
     */
    public boolean isIntentResolvedToArchivedApp(Intent intent, int userId) {
        String packageName = getPackageNameFromIntent(intent);
        if (packageName == null || intent.getComponent() == null) {
            return false;
        }
        PackageState packageState = mPm.snapshotComputer().getPackageStateInternal(packageName);
        if (packageState == null) {
            return false;
        }
        PackageUserState userState = packageState.getUserStateOrDefault(userId);
        if (!PackageArchiver.isArchived(userState)) {
            return false;
        }
        List<ArchiveState.ArchiveActivityInfo> archiveActivityInfoList =
                userState.getArchiveState().getActivityInfos();
        for (int i = 0; i < archiveActivityInfoList.size(); i++) {
            if (archiveActivityInfoList.get(i)
                    .getOriginalComponentName().equals(intent.getComponent())) {
                return true;
            }
        }
        Slog.e(TAG, TextUtils.formatSimple(
                "Package: %s is archived but component to start main activity"
                        + " cannot be found!", packageName));
        return false;
    }

    void clearArchiveState(String packageName, int userId) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(packageName);
        }
        clearArchiveState(ps, userId);
    }

    void clearArchiveState(PackageSetting ps, int userId) {
        synchronized (mPm.mLock) {
            if (ps == null || ps.getUserStateOrDefault(userId).getArchiveState() == null) {
                // No archive states to clear
                return;
            }
            if (DEBUG) {
                Slog.e(TAG, "Clearing archive states for " + ps.getPackageName());
            }
            ps.setArchiveState(/* archiveState= */ null, userId);
        }
        File iconsDir = getIconsDir(ps.getPackageName(), userId);
        if (!iconsDir.exists()) {
            if (DEBUG) {
                Slog.e(TAG, "Icons are already deleted at " + iconsDir.getAbsolutePath());
            }
            return;
        }
        // TODO(b/319238030) Move this into installd.
        if (!FileUtils.deleteContentsAndDir(iconsDir)) {
            Slog.e(TAG, "Failed to clean up archive files for " + ps.getPackageName());
        } else {
            if (DEBUG) {
                Slog.e(TAG, "Deleted icons at " + iconsDir.getAbsolutePath());
            }
        }
    }

    @Nullable
    private String getCurrentLauncherPackageName(int userId) {
        ComponentName defaultLauncherComponent = mPm.snapshotComputer().getDefaultHomeActivity(
                userId);
        if (defaultLauncherComponent != null) {
            return defaultLauncherComponent.getPackageName();
        }
        return null;
    }

    private boolean isCallingPackageValid(String callingPackage, int callingUid, int userId) {
        int packageUid;
        packageUid = mPm.snapshotComputer().getPackageUid(callingPackage, 0L, userId);
        if (packageUid != callingUid) {
            Slog.w(TAG, TextUtils.formatSimple("Calling package: %s does not belong to uid: %d",
                    callingPackage, callingUid));
            return false;
        }
        return true;
    }

    private IntentSender getOrCreateLauncherListener(int userId, String packageName) {
        Pair<Integer, String> key = Pair.create(userId, packageName);
        synchronized (mLauncherIntentSenders) {
            IntentSender intentSender = mLauncherIntentSenders.get(key);
            if (intentSender != null) {
                return intentSender;
            }
            IntentSender unarchiveIntentSender = new IntentSender(
                    (IIntentSender) new UnarchiveIntentSender());
            mLauncherIntentSenders.put(key, unarchiveIntentSender);
            return unarchiveIntentSender;
        }
    }

    /** Creates archived state for the package and user. */
    private CompletableFuture<Void> createAndStoreArchiveState(String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        Computer snapshot = mPm.snapshotComputer();
        PackageStateInternal ps = getPackageState(packageName, snapshot,
                Binder.getCallingUid(), userId);
        verifyNotSystemApp(ps.getFlags());
        verifyInstalled(ps, userId);
        String responsibleInstallerPackage = getResponsibleInstallerPackage(ps);
        ApplicationInfo installerInfo = verifyInstaller(
                snapshot, responsibleInstallerPackage, userId);
        verifyOptOutStatus(packageName,
                UserHandle.getUid(userId, UserHandle.getUid(userId, ps.getAppId())));

        List<LauncherActivityInfo> mainActivities = getLauncherActivityInfos(ps.getPackageName(),
                userId);
        final CompletableFuture<Void> archiveStateStored = new CompletableFuture<>();
        mPm.mHandler.post(() -> {
            try {
                final String installerTitle = getResponsibleInstallerTitle(
                        mContext, installerInfo, responsibleInstallerPackage, userId);
                var archiveState = createArchiveStateInternal(packageName, userId, mainActivities,
                        installerTitle);
                storeArchiveState(packageName, archiveState, userId);
                archiveStateStored.complete(null);
            } catch (IOException | PackageManager.NameNotFoundException e) {
                archiveStateStored.completeExceptionally(e);
            }
        });
        return archiveStateStored;
    }

    @Nullable
    ArchiveState createArchiveState(@NonNull ArchivedPackageParcel archivedPackage,
            int userId, String installerPackage, String responsibleInstallerTitle) {
        ApplicationInfo installerInfo = mPm.snapshotComputer().getApplicationInfo(
                installerPackage, /* flags= */ 0, userId);
        if (installerInfo == null) {
            // Should never happen because we just fetched the installerInfo.
            Slog.e(TAG, "Couldn't find installer " + installerPackage);
            return null;
        }
        if (responsibleInstallerTitle == null) {
            Slog.e(TAG, "Couldn't get the title of the installer");
            return null;
        }

        final int iconSize = mContext.getSystemService(
                ActivityManager.class).getLauncherLargeIconSize();

        var info = new ArchivedPackageInfo(archivedPackage);

        try {
            var packageName = info.getPackageName();
            var mainActivities = info.getLauncherActivities();
            List<ArchiveActivityInfo> archiveActivityInfos = new ArrayList<>(mainActivities.size());
            for (int i = 0, size = mainActivities.size(); i < size; ++i) {
                var mainActivity = mainActivities.get(i);
                Path iconPath = storeAdaptiveDrawable(
                        packageName, mainActivity.getIcon(), userId, i * 2 + 0, iconSize);
                Path monochromePath = storeAdaptiveDrawable(
                        packageName, mainActivity.getMonochromeIcon(), userId, i * 2 + 1, iconSize);
                ArchiveActivityInfo activityInfo =
                        new ArchiveActivityInfo(
                                mainActivity.getLabel().toString(),
                                mainActivity.getComponentName(),
                                iconPath,
                                monochromePath);
                archiveActivityInfos.add(activityInfo);
            }

            return new ArchiveState(archiveActivityInfos, responsibleInstallerTitle);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to create archive state", e);
            return null;
        }
    }

    ArchiveState createArchiveStateInternal(String packageName, int userId,
            List<LauncherActivityInfo> mainActivities, String installerTitle)
            throws IOException {
        final int iconSize = mContext.getSystemService(
                ActivityManager.class).getLauncherLargeIconSize();

        List<ArchiveActivityInfo> archiveActivityInfos = new ArrayList<>(mainActivities.size());
        for (int i = 0, size = mainActivities.size(); i < size; i++) {
            LauncherActivityInfo mainActivity = mainActivities.get(i);
            Path iconPath = storeIcon(packageName, mainActivity, userId, i * 2 + 0, iconSize);
            // i * 2 + 1 reserved for monochromeIcon
            ArchiveActivityInfo activityInfo =
                    new ArchiveActivityInfo(
                            mainActivity.getLabel().toString(),
                            mainActivity.getComponentName(),
                            iconPath,
                            null);
            archiveActivityInfos.add(activityInfo);
        }

        return new ArchiveState(archiveActivityInfos, installerTitle);
    }

    @VisibleForTesting
    Path storeIcon(String packageName, LauncherActivityInfo mainActivity,
            @UserIdInt int userId, int index, int iconSize) throws IOException {
        int iconResourceId = mainActivity.getActivityInfo().getIconResource();
        if (iconResourceId == 0) {
            // The app doesn't define an icon. No need to store anything.
            return null;
        }
        return storeDrawable(packageName, mainActivity.getIcon(/* density= */ 0), userId, index,
                iconSize);
    }

    private static Path storeDrawable(String packageName, @Nullable Drawable iconDrawable,
            @UserIdInt int userId, int index, int iconSize) throws IOException {
        if (iconDrawable == null) {
            return null;
        }
        File iconsDir = createIconsDir(packageName, userId);
        File iconFile = new File(iconsDir, index + ".png");
        Bitmap icon = drawableToBitmap(iconDrawable, iconSize);
        try (FileOutputStream out = new FileOutputStream(iconFile)) {
            // Note: Quality is ignored for PNGs.
            if (!icon.compress(Bitmap.CompressFormat.PNG, /* quality= */ 100, out)) {
                throw new IOException(TextUtils.formatSimple("Failure to store icon file %s",
                        iconFile.getAbsolutePath()));
            }
            out.flush();
        }
        if (DEBUG && iconFile.exists()) {
            Slog.i(TAG, "Stored icon at " + iconFile.getAbsolutePath());
        }
        return iconFile.toPath();
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the actual bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        FixedSizeBitmapDrawable(@Nullable final Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }

    /**
     * Create an <a
     * href="https://developer.android.com/develop/ui/views/launch/icon_design_adaptive">
     * adaptive icon</a> from an icon.
     * This is necessary so the icon can be displayed properly by different launchers.
     */
    private static Path storeAdaptiveDrawable(String packageName, @Nullable Drawable iconDrawable,
            @UserIdInt int userId, int index, int iconSize) throws IOException {
        if (iconDrawable == null) {
            return null;
        }

        // see BaseIconFactory#createShapedIconBitmap
        if (iconDrawable instanceof BitmapDrawable) {
            var icon = ((BitmapDrawable) iconDrawable).getBitmap();
            iconDrawable = new FixedSizeBitmapDrawable(icon);
        }

        float inset = getExtraInsetFraction();
        inset = inset / (1 + 2 * inset);
        Drawable d = new AdaptiveIconDrawable(new ColorDrawable(Color.BLACK),
                new InsetDrawable(iconDrawable/*d*/, inset, inset, inset, inset));

        return storeDrawable(packageName, d, userId, index, iconSize);
    }


    private ApplicationInfo verifyInstaller(Computer snapshot, String installerPackageName,
            int userId) throws PackageManager.NameNotFoundException {
        if (TextUtils.isEmpty(installerPackageName)) {
            throw new PackageManager.NameNotFoundException("No installer found");
        }
        // Allow shell for easier development.
        if ((Binder.getCallingUid() != Process.SHELL_UID)
                && !verifySupportsUnarchival(installerPackageName, userId)) {
            throw new PackageManager.NameNotFoundException("Installer does not support unarchival");
        }
        ApplicationInfo appInfo = snapshot.getApplicationInfo(
                installerPackageName, /* flags=*/ 0, userId);
        if (appInfo == null) {
            throw new PackageManager.NameNotFoundException("Failed to obtain Installer info");
        }
        return appInfo;
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

    private void verifyNotSystemApp(int flags) throws PackageManager.NameNotFoundException {
        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0 || (
                (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)) {
            throw new PackageManager.NameNotFoundException("System apps cannot be archived.");
        }
    }

    private void verifyInstalled(PackageStateInternal ps, int userId)
            throws PackageManager.NameNotFoundException {
        if (!ps.getUserStateOrDefault(userId).isInstalled()) {
            throw new PackageManager.NameNotFoundException(
                    TextUtils.formatSimple("%s is not installed.", ps.getPackageName()));
        }
    }

    /**
     * Returns true if the app is archivable.
     */
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

        if ((ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0 || (
                (ps.getFlags() & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)) {
            return false;
        }

        if (isAppOptedOutOfArchiving(packageName,
                    UserHandle.getUid(userId, ps.getAppId()))) {
            return false;
        }

        try {
            verifyInstaller(snapshot, getResponsibleInstallerPackage(ps), userId);
            getLauncherActivityInfos(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return true;
    }

    /**
     * Returns true if user has opted the app out of archiving through system settings.
     */
    private boolean isAppOptedOutOfArchiving(String packageName, int uid) {
        return Binder.withCleanCallingIdentity(() ->
                getAppOpsManager().checkOpNoThrow(
                        AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, uid, packageName)
                        == MODE_IGNORED);
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
            @NonNull IntentSender statusReceiver,
            @NonNull UserHandle userHandle) {
        requestUnarchive(packageName, callerPackageName, statusReceiver, userHandle,
                false /* showUnarchivalConfirmation= */);
    }

    private void requestUnarchive(
            @NonNull String packageName,
            @NonNull String callerPackageName,
            @NonNull IntentSender statusReceiver,
            @NonNull UserHandle userHandle, boolean showUnarchivalConfirmation) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(statusReceiver);
        Objects.requireNonNull(userHandle);

        Computer snapshot = mPm.snapshotComputer();
        int userId = userHandle.getIdentifier();
        int binderUid = Binder.getCallingUid();
        if (!PackageManagerServiceUtils.isSystemOrRootOrShell(binderUid)) {
            verifyCaller(snapshot.getPackageUid(callerPackageName, 0, userId), binderUid);
        }
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "unarchiveApp");

        PackageStateInternal ps;
        PackageStateInternal callerPs;
        try {
            ps = getPackageState(packageName, snapshot, binderUid, userId);
            callerPs = getPackageState(callerPackageName, snapshot, binderUid, userId);
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

        boolean hasInstallPackages = mContext.checkCallingOrSelfPermission(
                Manifest.permission.INSTALL_PACKAGES)
                == PackageManager.PERMISSION_GRANTED;
        // We don't check the AppOpsManager here for REQUEST_INSTALL_PACKAGES because the requester
        // is not the source of the installation.
        boolean hasRequestInstallPackages = callerPs.getAndroidPackage().getRequestedPermissions()
                .contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES);
        if (!hasInstallPackages && !hasRequestInstallPackages) {
            throw new SecurityException("You need the com.android.permission.INSTALL_PACKAGES "
                    + "or com.android.permission.REQUEST_INSTALL_PACKAGES permission to request "
                    + "an unarchival.");
        }

        if (!hasInstallPackages || showUnarchivalConfirmation) {
            requestUnarchiveConfirmation(packageName, statusReceiver, userHandle);
            return;
        }

        // TODO(b/311709794) Check that the responsible installer has INSTALL_PACKAGES or
        // OPSTR_REQUEST_INSTALL_PACKAGES too. Edge case: In reality this should always be the case,
        // unless a user has disabled the permission after archiving an app.

        int draftSessionId;
        try {
            draftSessionId = Binder.withCleanCallingIdentity(
                    () -> createDraftSession(packageName, installerPackage, callerPackageName,
                            statusReceiver, userId));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw ExceptionUtils.wrap((IOException) e.getCause());
            } else {
                throw e;
            }
        }

        mPm.mHandler.post(() -> {
            Slog.i(TAG, "Starting app unarchival for: " + packageName);
            unarchiveInternal(packageName, userHandle, installerPackage,
                    draftSessionId);
        });
    }

    @Nullable
    private PackageInstaller.SessionInfo getActiveUnarchivalSession(String packageName,
            int userId) {
        List<PackageInstaller.SessionInfo> activeSessions =
                mPm.mInstallerService.getAllSessions(userId).getList();
        for (int idx = 0; idx < activeSessions.size(); idx++) {
            PackageInstaller.SessionInfo activeSession = activeSessions.get(idx);
            if (TextUtils.equals(activeSession.appPackageName, packageName)
                    && activeSession.userId == userId && activeSession.active
                    && activeSession.isUnarchival()) {
                return activeSession;
            }
        }
        return null;
    }

    private void requestUnarchiveConfirmation(String packageName, IntentSender statusReceiver,
            UserHandle user) {
        final Intent dialogIntent = new Intent(ACTION_UNARCHIVE_DIALOG);
        dialogIntent.putExtra(EXTRA_UNARCHIVE_INTENT_SENDER, statusReceiver);
        dialogIntent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName);

        final Intent broadcastIntent = new Intent();
        broadcastIntent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName);
        broadcastIntent.putExtra(EXTRA_UNARCHIVE_STATUS,
                PackageInstaller.STATUS_PENDING_USER_ACTION);
        broadcastIntent.putExtra(Intent.EXTRA_INTENT, dialogIntent);
        broadcastIntent.putExtra(Intent.EXTRA_USER, user);
        sendIntent(statusReceiver, packageName, /* message= */ "", broadcastIntent);
    }

    private void verifyUninstallPermissions() {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.DELETE_PACKAGES)
                != PackageManager.PERMISSION_GRANTED && mContext.checkCallingOrSelfPermission(
                Manifest.permission.REQUEST_DELETE_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need the com.android.permission.DELETE_PACKAGES "
                    + "or com.android.permission.REQUEST_DELETE_PACKAGES permission to request "
                    + "an archival.");
        }
    }

    private int createDraftSession(String packageName, String installerPackage,
            String callerPackageName,
            IntentSender statusReceiver, int userId) throws IOException {
        Computer snapshot = mPm.snapshotComputer();
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sessionParams.setAppPackageName(packageName);
        sessionParams.setAppLabel(
                mContext.getString(com.android.internal.R.string.unarchival_session_app_label));
        // The draft session's app icon is based on the current launcher's icon overlay appops mode
        String launcherPackageName = getCurrentLauncherPackageName(userId);
        int launcherUid = launcherPackageName != null
                ? snapshot.getPackageUid(launcherPackageName, 0, userId)
                : Process.SYSTEM_UID;
        sessionParams.setAppIcon(getArchivedAppIcon(packageName, UserHandle.of(userId),
                isOverlayEnabled(launcherUid,
                        launcherPackageName == null ? callerPackageName : launcherPackageName)));
        // To make sure SessionInfo::isUnarchival returns true for draft sessions,
        // INSTALL_UNARCHIVE is also set.
        sessionParams.installFlags = (INSTALL_UNARCHIVE_DRAFT | INSTALL_UNARCHIVE);

        int installerUid = snapshot.getPackageUid(installerPackage, 0, userId);
        // Handles case of repeated unarchival calls for the same package.
        int existingSessionId = mPm.mInstallerService.getExistingDraftSessionId(installerUid,
                sessionParams,
                userId);
        if (existingSessionId != PackageInstaller.SessionInfo.INVALID_ID) {
            attachListenerToSession(statusReceiver, existingSessionId, userId);
            return existingSessionId;
        }

        int sessionId = mPm.mInstallerService.createSessionInternal(
                sessionParams,
                installerPackage, mContext.getAttributionTag(),
                installerUid,
                userId);
        attachListenerToSession(statusReceiver, sessionId, userId);

        // TODO(b/297358628) Also cleanup sessions upon device restart.
        mPm.mHandler.postDelayed(() -> mPm.mInstallerService.cleanupDraftIfUnclaimed(sessionId),
                getUnarchiveForegroundTimeout());
        return sessionId;
    }

    private void attachListenerToSession(IntentSender statusReceiver, int existingSessionId,
            int userId) {
        PackageInstallerSession session = mPm.mInstallerService.getSession(existingSessionId);
        int status = session.getUnarchivalStatus();
        // Here we handle a race condition that might happen when an installer reports UNARCHIVAL_OK
        // but hasn't created a session yet. Without this the listener would never receive a success
        // response.
        if (status == UNARCHIVAL_OK) {
            notifyUnarchivalListener(UNARCHIVAL_OK, session.getInstallerPackageName(),
                    session.params.appPackageName, /* requiredStorageBytes= */ 0,
                    /* userActionIntent= */ null, Set.of(statusReceiver), userId);
            return;
        } else if (status != UNARCHIVAL_STATUS_UNSET) {
            throw new IllegalStateException(TextUtils.formatSimple("Session %s has unarchive status"
                    + "%s but is still active.", session.sessionId, status));
        }

        session.registerUnarchivalListener(statusReceiver);
    }

    /**
     * Returns the icon of an archived app. This is the icon of the main activity of the app.
     *
     * <p> In the rare case the app had multiple launcher activities, only one of the icons is
     * returned arbitrarily.
     *
     * <p> By default, the icon will be overlay'd with a cloud icon on top. An app can
     * disable the cloud overlay via the
     * {@link LauncherApps.ArchiveCompatibilityParams#setEnableIconOverlay(boolean)} API.
     */
    @Nullable
    public Bitmap getArchivedAppIcon(@NonNull String packageName, @NonNull UserHandle user,
            String callingPackageName) {
        return getArchivedAppIcon(packageName, user,
                isOverlayEnabled(Binder.getCallingUid(), callingPackageName));
    }

    @Nullable
    private Bitmap getArchivedAppIcon(@NonNull String packageName, @NonNull UserHandle user,
            boolean isOverlayEnabled) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(user);

        Computer snapshot = mPm.snapshotComputer();
        int callingUid = Binder.getCallingUid();
        int userId = user.getIdentifier();
        PackageStateInternal ps;
        try {
            ps = getPackageState(packageName, snapshot, callingUid, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, TextUtils.formatSimple("Package %s couldn't be found.", packageName), e);
            return null;
        }

        ArchiveState archiveState = getAnyArchiveState(ps, userId);
        if (archiveState == null || archiveState.getActivityInfos().size() == 0) {
            return null;
        }

        // TODO(b/298452477) Handle monochrome icons.
        // In the rare case the archived app defined more than two launcher activities, we choose
        // the first one arbitrarily.
        Bitmap icon = decodeIcon(archiveState.getActivityInfos().get(0));

        if (icon != null && isOverlayEnabled) {
            icon = includeCloudOverlay(icon);
        }
        return icon;
    }

    private boolean isOverlayEnabled(int callingUid, String packageName) {
        return getAppOpsManager().checkOp(
                AppOpsManager.OP_ARCHIVE_ICON_OVERLAY, callingUid, packageName)
                == MODE_ALLOWED;
    }

    /**
     * This method first checks the ArchiveState for the provided userId and then tries to fallback
     * to other users if the current user is not archived.
     *
     * <p> This fallback behaviour is required for archived apps to fit into the multi-user world
     * where APKs are shared across users. E.g. current ways of fetching icons for apps that are
     * only installed on the work profile also work when executed on the personal profile if you're
     * using {@link PackageManager#MATCH_UNINSTALLED_PACKAGES}. Resource fetching from APKs is for
     * the most part userId-agnostic, which we need to mimic here in order for existing methods
     * like {@link PackageManager#getApplicationIcon} to continue working.
     *
     * @return {@link ArchiveState} for {@code userId} if present. If not present, false back to an
     * arbitrary userId. If no user is archived, returns null.
     */
    @Nullable
    private ArchiveState getAnyArchiveState(PackageStateInternal ps, int userId) {
        PackageUserStateInternal userState = ps.getUserStateOrDefault(userId);
        if (isArchived(userState)) {
            return userState.getArchiveState();
        }

        for (int i = 0; i < ps.getUserStates().size(); i++) {
            userState = ps.getUserStates().valueAt(i);
            if (isArchived(userState)) {
                return userState.getArchiveState();
            }
        }

        return null;
    }

    @VisibleForTesting
    @Nullable
    Bitmap decodeIcon(ArchiveActivityInfo activityInfo) {
        Path iconBitmap = activityInfo.getIconBitmap();
        if (iconBitmap == null) {
            return null;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(iconBitmap.toString());
        // TODO(b/278553670) We should throw here after some time. Failing graciously now because
        // we've just changed the place where we store icons.
        if (bitmap == null) {
            Slog.e(TAG, "Archived icon cannot be decoded " + iconBitmap.toAbsolutePath());
            return null;
        }
        return bitmap;
    }

    @Nullable
    Bitmap includeCloudOverlay(Bitmap bitmap) {
        Drawable cloudDrawable =
                mContext.getResources()
                        .getDrawable(R.drawable.archived_app_cloud_overlay, mContext.getTheme());
        if (cloudDrawable == null) {
            Slog.e(TAG, "Unable to locate cloud overlay for archived app!");
            return bitmap;
        }
        BitmapDrawable appIconDrawable = new BitmapDrawable(mContext.getResources(), bitmap);
        appIconDrawable.setColorFilter(OPACITY_LAYER_FILTER);
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
        if (bitmap != null) {
            bitmap.recycle();
        }
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

    private static String getResponsibleInstallerPackage(InstallSource installSource) {
        return TextUtils.isEmpty(installSource.mUpdateOwnerPackageName)
                ? installSource.mInstallerPackageName
                : installSource.mUpdateOwnerPackageName;
    }

    private static String getResponsibleInstallerTitle(Context context, ApplicationInfo appInfo,
            String responsibleInstallerPackage, int userId)
            throws PackageManager.NameNotFoundException {
        final Context userContext = context.createPackageContextAsUser(
                responsibleInstallerPackage, /* flags= */ 0, new UserHandle(userId));
        return appInfo.loadLabel(userContext.getPackageManager()).toString();
    }

    static String getResponsibleInstallerPackage(PackageStateInternal ps) {
        return getResponsibleInstallerPackage(ps.getInstallSource());
    }

    @Nullable
    static SparseArray<String> getResponsibleInstallerTitles(Context context, Computer snapshot,
            InstallSource installSource, int requestUserId, int[] allUserIds) {
        final String responsibleInstallerPackage = getResponsibleInstallerPackage(installSource);
        final SparseArray<String> responsibleInstallerTitles = new SparseArray<>();
        try {
            if (requestUserId != UserHandle.USER_ALL) {
                final ApplicationInfo responsibleInstallerInfo = snapshot.getApplicationInfo(
                        responsibleInstallerPackage, /* flags= */ 0, requestUserId);
                if (responsibleInstallerInfo == null) {
                    return null;
                }

                final String title = getResponsibleInstallerTitle(context,
                        responsibleInstallerInfo, responsibleInstallerPackage, requestUserId);
                responsibleInstallerTitles.put(requestUserId, title);
            } else {
                // Go through all userIds.
                for (int i = 0; i < allUserIds.length; i++) {
                    final int userId = allUserIds[i];
                    final ApplicationInfo responsibleInstallerInfo = snapshot.getApplicationInfo(
                            responsibleInstallerPackage, /* flags= */ 0, userId);
                    // Can't get the applicationInfo on the user.
                    // Maybe the installer isn't installed on the user.
                    if (responsibleInstallerInfo == null) {
                        continue;
                    }

                    final String title = getResponsibleInstallerTitle(context,
                            responsibleInstallerInfo, responsibleInstallerPackage, userId);
                    responsibleInstallerTitles.put(userId, title);
                }
            }
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
        return responsibleInstallerTitles;
    }

    void notifyUnarchivalListener(int status, String installerPackageName, String appPackageName,
            long requiredStorageBytes, @Nullable PendingIntent userActionIntent,
            Set<IntentSender> unarchiveIntentSenders, int userId) {
        final Intent broadcastIntent = new Intent();
        broadcastIntent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, appPackageName);
        broadcastIntent.putExtra(EXTRA_UNARCHIVE_STATUS, status);

        if (status != UNARCHIVAL_OK) {
            final Intent dialogIntent = createErrorDialogIntent(status, installerPackageName,
                    appPackageName,
                    requiredStorageBytes, userActionIntent, userId);
            if (dialogIntent == null) {
                // Error already logged.
                return;
            }
            broadcastIntent.putExtra(Intent.EXTRA_INTENT, dialogIntent);
            broadcastIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
        }

        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_DENIED);
        for (IntentSender intentSender : unarchiveIntentSenders) {
            try {
                intentSender.sendIntent(mContext, 0, broadcastIntent,
                        /* requiredPermission */ null, options.toBundle(),
                        /* executor */ null, /* onFinished */ null);
            } catch (IntentSender.SendIntentException e) {
                Slog.e(TAG, TextUtils.formatSimple("Failed to send unarchive intent"), e);
            } finally {
                synchronized (mLauncherIntentSenders) {
                    mLauncherIntentSenders.remove(Pair.create(userId, appPackageName));
                }
            }
        }
    }

    @Nullable
    private Intent createErrorDialogIntent(int status, String installerPackageName,
            String appPackageName,
            long requiredStorageBytes, PendingIntent userActionIntent, int userId) {
        final Intent dialogIntent = new Intent(ACTION_UNARCHIVE_ERROR_DIALOG);
        dialogIntent.putExtra(EXTRA_UNARCHIVE_STATUS, status);
        dialogIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
        if (requiredStorageBytes > 0) {
            dialogIntent.putExtra(EXTRA_REQUIRED_BYTES, requiredStorageBytes);
        }
        // Note that the userActionIntent is provided by the installer and is used only by the
        // system package installer as a follow-up action after the user confirms the dialog.
        if (userActionIntent != null) {
            dialogIntent.putExtra(Intent.EXTRA_INTENT, userActionIntent);
        }
        dialogIntent.putExtra(EXTRA_INSTALLER_PACKAGE_NAME, installerPackageName);
        // We fetch this label from the archive state because the installer might not be installed
        // anymore in an edge case.
        String installerTitle = getInstallerTitle(appPackageName, userId);
        if (installerTitle == null) {
            // Error already logged.
            return null;
        }
        dialogIntent.putExtra(EXTRA_INSTALLER_TITLE, installerTitle);
        return dialogIntent;
    }

    private String getInstallerTitle(String appPackageName, int userId) {
        PackageStateInternal packageState;
        try {
            packageState = getPackageState(appPackageName,
                    mPm.snapshotComputer(),
                    Process.SYSTEM_UID, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, TextUtils.formatSimple(
                    "notifyUnarchivalListener: Couldn't fetch package state for %s.",
                    appPackageName), e);
            return null;
        }
        ArchiveState archiveState = packageState.getUserStateOrDefault(userId).getArchiveState();
        if (archiveState == null) {
            Slog.e(TAG, TextUtils.formatSimple("notifyUnarchivalListener: App not archived %s.",
                    appPackageName));
            return null;
        }
        return archiveState.getInstallerTitle();
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

    private UserManager getUserManager() {
        if (mUserManager == null) {
            mUserManager = mContext.getSystemService(UserManager.class);
        }
        return mUserManager;
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
        final Intent intent = new Intent();
        intent.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, packageName);
        intent.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        sendIntent(statusReceiver, packageName, message, intent);
    }

    private void sendIntent(IntentSender statusReceiver, String packageName, String message,
            Intent intent) {
        try {
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    MODE_BACKGROUND_ACTIVITY_START_DENIED);
            statusReceiver.sendIntent(mContext, 0, intent,
                    /* requiredPermission */ null, options.toBundle(),
                    /* executor */ null, /* onFinished */ null);
        } catch (IntentSender.SendIntentException e) {
            Slog.e(
                    TAG,
                    TextUtils.formatSimple("Failed to send status for %s with message %s",
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

    private static File createIconsDir(String packageName, @UserIdInt int userId)
            throws IOException {
        File iconsDir = getIconsDir(packageName, userId);
        if (!iconsDir.isDirectory()) {
            iconsDir.delete();
            iconsDir.mkdirs();
            if (!iconsDir.isDirectory()) {
                throw new IOException("Unable to create directory " + iconsDir);
            }
            if (DEBUG) {
                Slog.i(TAG, "Created icons directory at " + iconsDir.getAbsolutePath());
            }
        }
        SELinux.restorecon(iconsDir);
        return iconsDir;
    }

    private static File getIconsDir(String packageName, int userId) {
        return new File(
                new File(Environment.getDataSystemCeDirectory(userId), ARCHIVE_ICONS_DIR),
                packageName);
    }

    private static byte[] bytesFromBitmapFile(Path path) throws IOException {
        if (path == null) {
            return null;
        }
        // Technically we could just read the bytes, but we want to be sure we store the
        // right format.
        return bytesFromBitmap(BitmapFactory.decodeFile(path.toString()));
    }

    @Nullable
    private static String getPackageNameFromIntent(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        if (intent.getPackage() != null) {
            return intent.getPackage();
        }
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return null;
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

    private class UnarchiveIntentSender extends IIntentSender.Stub {
        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options)
                throws RemoteException {
            int status = intent.getExtras().getInt(PackageInstaller.EXTRA_UNARCHIVE_STATUS,
                    STATUS_PENDING_USER_ACTION);
            if (status == UNARCHIVAL_OK) {
                return;
            }
            Intent extraIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
            if (extraIntent != null && user != null
                    && mAppStateHelper.isAppTopVisible(
                    getCurrentLauncherPackageName(user.getIdentifier()))) {
                extraIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(extraIntent, user);
            }
        }
    }
}
