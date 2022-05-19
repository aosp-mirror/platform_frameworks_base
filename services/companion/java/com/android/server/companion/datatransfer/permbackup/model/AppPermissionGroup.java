/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer.permbackup.model;

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OPSTR_LEGACY_STORAGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.server.companion.datatransfer.permbackup.utils.ArrayUtils;
import com.android.server.companion.datatransfer.permbackup.utils.LocationUtils;
import com.android.server.companion.datatransfer.permbackup.utils.SoftRestrictedPermissionPolicy;
import com.android.server.companion.datatransfer.permbackup.utils.Utils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * All permissions of a permission group that are requested by an app.
 *
 * <p>Some permissions only grant access to the protected resource while the app is running in the
 * foreground. These permissions are considered "split" into this foreground and a matching
 * "background" permission.
 *
 * <p>All background permissions of the group are not in the main group and will not be affected
 * by operations on the group. The background permissions can be found in the {@link
 * #getBackgroundPermissions() background permissions group}.
 */
public final class AppPermissionGroup implements Comparable<AppPermissionGroup> {
    private static final String LOG_TAG = AppPermissionGroup.class.getSimpleName();
    private static final String PLATFORM_PACKAGE_NAME = "android";

    private static final String KILL_REASON_APP_OP_CHANGE = "Permission related app op changed";

    /**
     * Importance level to define the threshold for whether a package is in a state which resets the
     * timer on its one-time permission session
     */
    private static final int ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

    /**
     * Importance level to define the threshold for whether a package is in a state which keeps its
     * one-time permission session alive after the timer ends
     */
    private static final int ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

    private final Context mContext;
    private final UserHandle mUserHandle;
    private final PackageManager mPackageManager;
    private final AppOpsManager mAppOps;
    private final ActivityManager mActivityManager;
    private final Collator mCollator;

    private final PackageInfo mPackageInfo;
    private final String mName;
    private final String mDeclaringPackage;
    private final CharSequence mLabel;
    private final CharSequence mFullLabel;
    private final @StringRes int mRequest;
    private final @StringRes int mRequestDetail;
    private final @StringRes int mBackgroundRequest;
    private final @StringRes int mBackgroundRequestDetail;
    private final @StringRes int mUpgradeRequest;
    private final @StringRes int mUpgradeRequestDetail;
    private final CharSequence mDescription;
    private final ArrayMap<String, Permission> mPermissions = new ArrayMap<>();
    private final String mIconPkg;
    private final int mIconResId;

    /** Delay changes until {@link #persistChanges} is called */
    private final boolean mDelayChanges;

    /**
     * Some permissions are split into foreground and background permission. All non-split and
     * foreground permissions are in {@link #mPermissions}, all background permissions are in
     * this field.
     */
    private AppPermissionGroup mBackgroundPermissions;

    private final boolean mAppSupportsRuntimePermissions;
    private final boolean mIsEphemeralApp;
    private final boolean mIsNonIsolatedStorage;
    private boolean mContainsEphemeralPermission;
    private boolean mContainsPreRuntimePermission;

    /**
     * Does this group contain at least one permission that is split into a foreground and
     * background permission? This does not necessarily mean that the app also requested the
     * background permission.
     */
    private boolean mHasPermissionWithBackgroundMode;

    private boolean mTriggerLocationAccessCheckOnPersist;

    private boolean mIsSelfRevoked;

    /**
     * Create the app permission group.
     *
     * @param context the {@code Context} to retrieve system services.
     * @param packageInfo package information about the app.
     * @param permissionName the name of the permission this object represents.
     * @param delayChanges whether to delay changes until {@link #persistChanges} is called.
     *
     * @return the AppPermissionGroup.
     */
    public static AppPermissionGroup create(Context context, PackageInfo packageInfo,
            String permissionName, boolean delayChanges) {
        PermissionInfo permissionInfo;
        try {
            permissionInfo = context.getPackageManager().getPermissionInfo(permissionName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        if ((permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                != PermissionInfo.PROTECTION_DANGEROUS
                || (permissionInfo.flags & PermissionInfo.FLAG_INSTALLED) == 0
                || (permissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0) {
            return null;
        }

        String group = Utils.getGroupOfPermission(permissionInfo);
        PackageItemInfo groupInfo = permissionInfo;
        if (group != null) {
            try {
                groupInfo = context.getPackageManager().getPermissionGroupInfo(group, 0);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        List<PermissionInfo> permissionInfos = null;
        if (groupInfo instanceof PermissionGroupInfo) {
            try {
                permissionInfos = Utils.getPermissionInfosForGroup(context.getPackageManager(),
                        groupInfo.name);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        return create(context, packageInfo, groupInfo, permissionInfos, delayChanges);
    }

    /**
     * Create the app permission group.
     *
     * @param app the current application
     * @param packageName the name of the package
     * @param permissionGroupName the name of the permission group
     * @param user the user of the package
     * @param delayChanges whether to delay changes until {@link #persistChanges} is called.
     *
     * @return the AppPermissionGroup.
     */
    public static AppPermissionGroup create(Application app, String packageName,
            String permissionGroupName, UserHandle user, boolean delayChanges) {
        try {
            PackageInfo packageInfo = Utils.getUserContext(app, user).getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            PackageItemInfo groupInfo = Utils.getGroupInfo(permissionGroupName, app);
            if (groupInfo == null) {
                return null;
            }

            List<PermissionInfo> permissionInfos = null;
            if (groupInfo instanceof PermissionGroupInfo) {
                permissionInfos = Utils.getPermissionInfosForGroup(app.getPackageManager(),
                        groupInfo.name);
            }
            return create(app, packageInfo, groupInfo, permissionInfos, delayChanges);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Create the app permission group.
     *
     * @param context the {@code Context} to retrieve system services.
     * @param packageInfo package information about the app.
     * @param groupInfo the information about the group created.
     * @param permissionInfos the information about the permissions belonging to the group.
     * @param delayChanges whether to delay changes until {@link #persistChanges} is called.
     *
     * @return the AppPermissionGroup.
     */
    public static AppPermissionGroup create(Context context, PackageInfo packageInfo,
            PackageItemInfo groupInfo, List<PermissionInfo> permissionInfos, boolean delayChanges) {
        PackageManager packageManager = context.getPackageManager();
        CharSequence groupLabel = groupInfo.loadLabel(packageManager);
        CharSequence fullGroupLabel = groupInfo.loadSafeLabel(packageManager, 0,
                TextUtils.SAFE_STRING_FLAG_TRIM | TextUtils.SAFE_STRING_FLAG_FIRST_LINE);
        return create(context, packageInfo, groupInfo, permissionInfos, groupLabel,
                fullGroupLabel, delayChanges);
    }

    /**
     * Create the app permission group.
     *
     * @param context the {@code Context} to retrieve system services.
     * @param packageInfo package information about the app.
     * @param groupInfo the information about the group created.
     * @param permissionInfos the information about the permissions belonging to the group.
     * @param groupLabel the label of the group.
     * @param fullGroupLabel the untruncated label of the group.
     * @param delayChanges whether to delay changes until {@link #persistChanges} is called.
     *
     * @return the AppPermissionGroup.
     */
    public static AppPermissionGroup create(Context context, PackageInfo packageInfo,
            PackageItemInfo groupInfo, List<PermissionInfo> permissionInfos,
            CharSequence groupLabel, CharSequence fullGroupLabel, boolean delayChanges) {
        PackageManager packageManager = context.getPackageManager();
        UserHandle userHandle = UserHandle.getUserHandleForUid(packageInfo.applicationInfo.uid);

        if (groupInfo instanceof PermissionInfo) {
            permissionInfos = new ArrayList<>();
            permissionInfos.add((PermissionInfo) groupInfo);
        }

        if (permissionInfos == null || permissionInfos.isEmpty()) {
            return null;
        }

        AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);

        AppPermissionGroup group = new AppPermissionGroup(context, packageInfo, groupInfo.name,
                groupInfo.packageName, groupLabel, fullGroupLabel,
                /* description */ null, /* request */ 0,
                /* requestDetail */ 0, /* backgroundRequest */ 0,
                /* backgroundRequestDetail */ 0, /* upgradeRequest */0,
                /* upgradeRequestDetail */ 0, groupInfo.packageName, groupInfo.icon,
                userHandle, delayChanges, appOpsManager);

        final Set<String> exemptedRestrictedPermissions = context.getPackageManager()
                .getWhitelistedRestrictedPermissions(packageInfo.packageName,
                        Utils.FLAGS_PERMISSION_WHITELIST_ALL);

        // Parse and create permissions requested by the app
        ArrayMap<String, Permission> allPermissions = new ArrayMap<>();
        final int permissionCount = packageInfo.requestedPermissions == null ? 0
                : packageInfo.requestedPermissions.length;
        String packageName = packageInfo.packageName;
        for (int i = 0; i < permissionCount; i++) {
            String requestedPermission = packageInfo.requestedPermissions[i];

            PermissionInfo requestedPermissionInfo = null;

            for (PermissionInfo permissionInfo : permissionInfos) {
                if (requestedPermission.equals(permissionInfo.name)) {
                    requestedPermissionInfo = permissionInfo;
                    break;
                }
            }

            if (requestedPermissionInfo == null) {
                continue;
            }

            // Collect only runtime permissions.
            if ((requestedPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    != PermissionInfo.PROTECTION_DANGEROUS) {
                continue;
            }

            // Don't allow toggling non-platform permission groups for legacy apps via app ops.
            if (packageInfo.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1
                    && !PLATFORM_PACKAGE_NAME.equals(groupInfo.packageName)) {
                continue;
            }

            final boolean granted = (packageInfo.requestedPermissionsFlags[i]
                    & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;

            final String appOp = PLATFORM_PACKAGE_NAME.equals(requestedPermissionInfo.packageName)
                    ? AppOpsManager.permissionToOp(requestedPermissionInfo.name) : null;

            final boolean appOpAllowed;
            if (appOp == null) {
                appOpAllowed = false;
            } else {
                int appOpsMode = appOpsManager.unsafeCheckOpRaw(appOp,
                        packageInfo.applicationInfo.uid, packageName);
                appOpAllowed = appOpsMode == MODE_ALLOWED || appOpsMode == MODE_FOREGROUND;
            }

            final int flags = packageManager.getPermissionFlags(
                    requestedPermission, packageName, userHandle);

            Permission permission = new Permission(requestedPermission, requestedPermissionInfo,
                    granted, appOp, appOpAllowed, flags);

            if (requestedPermissionInfo.backgroundPermission != null) {
                group.mHasPermissionWithBackgroundMode = true;
            }

            allPermissions.put(requestedPermission, permission);
        }

        int numPermissions = allPermissions.size();
        if (numPermissions == 0) {
            return null;
        }

        // Link up foreground and background permissions
        for (int i = 0; i < allPermissions.size(); i++) {
            Permission permission = allPermissions.valueAt(i);

            if (permission.getBackgroundPermissionName() != null) {
                Permission backgroundPermission = allPermissions.get(
                        permission.getBackgroundPermissionName());

                if (backgroundPermission != null) {
                    backgroundPermission.addForegroundPermissions(permission);
                    permission.setBackgroundPermission(backgroundPermission);

                    // The background permissions isAppOpAllowed refers to the background state of
                    // the foregound permission's appOp. Hence we can only set it once we know the
                    // matching foreground permission.
                    // @see #allowAppOp
                    if (context.getSystemService(AppOpsManager.class).unsafeCheckOpRaw(
                            permission.getAppOp(), packageInfo.applicationInfo.uid,
                            packageInfo.packageName) == MODE_ALLOWED) {
                        backgroundPermission.setAppOpAllowed(true);
                    }
                }
            }
        }

        // Add permissions found to this group
        for (int i = 0; i < numPermissions; i++) {
            Permission permission = allPermissions.valueAt(i);

            if ((!permission.isHardRestricted()
                    || exemptedRestrictedPermissions.contains(permission.getName()))
                    && (!permission.isSoftRestricted()
                    || SoftRestrictedPermissionPolicy.shouldShow(packageInfo, permission))) {
                if (permission.isBackgroundPermission()) {
                    if (group.getBackgroundPermissions() == null) {
                        group.mBackgroundPermissions = new AppPermissionGroup(group.mContext,
                                group.getApp(), group.getName(), group.getDeclaringPackage(),
                                group.getLabel(), group.getFullLabel(), group.getDescription(),
                                group.getRequest(), group.getRequestDetail(),
                                group.getBackgroundRequest(), group.getBackgroundRequestDetail(),
                                group.getUpgradeRequest(), group.getUpgradeRequestDetail(),
                                group.getIconPkg(), group.getIconResId(), group.getUser(),
                                delayChanges, appOpsManager);
                    }

                    group.getBackgroundPermissions().addPermission(permission);
                } else {
                    group.addPermission(permission);
                }
            }
        }

        if (group.getPermissions().isEmpty()) {
            return null;
        }

        return group;
    }

    private AppPermissionGroup(Context context, PackageInfo packageInfo, String name,
            String declaringPackage, CharSequence label, CharSequence fullLabel,
            CharSequence description, @StringRes int request, @StringRes int requestDetail,
            @StringRes int backgroundRequest, @StringRes int backgroundRequestDetail,
            @StringRes int upgradeRequest, @StringRes int upgradeRequestDetail,
            String iconPkg, int iconResId, UserHandle userHandle, boolean delayChanges,
            @NonNull AppOpsManager appOpsManager) {
        int targetSDK = packageInfo.applicationInfo.targetSdkVersion;

        mContext = context;
        mUserHandle = userHandle;
        mPackageManager = mContext.getPackageManager();
        mPackageInfo = packageInfo;
        mAppSupportsRuntimePermissions = targetSDK > Build.VERSION_CODES.LOLLIPOP_MR1;
        mIsEphemeralApp = packageInfo.applicationInfo.isInstantApp();
        mAppOps = appOpsManager;
        mActivityManager = context.getSystemService(ActivityManager.class);
        mDeclaringPackage = declaringPackage;
        mName = name;
        mLabel = label;
        mFullLabel = fullLabel;
        mDescription = description;
        mCollator = Collator.getInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        mRequest = request;
        mRequestDetail = requestDetail;
        mBackgroundRequest = backgroundRequest;
        mBackgroundRequestDetail = backgroundRequestDetail;
        mUpgradeRequest = upgradeRequest;
        mUpgradeRequestDetail = upgradeRequestDetail;
        mDelayChanges = delayChanges;
        if (iconResId != 0) {
            mIconPkg = iconPkg;
            mIconResId = iconResId;
        } else {
            mIconPkg = context.getPackageName();
            mIconResId = 0; // doesn't matter to CDM
        }

        mIsNonIsolatedStorage = targetSDK < Build.VERSION_CODES.P
                || (targetSDK < Build.VERSION_CODES.R
                && mAppOps.unsafeCheckOpNoThrow(OPSTR_LEGACY_STORAGE,
                packageInfo.applicationInfo.uid, packageInfo.packageName) == MODE_ALLOWED);
    }

    boolean doesSupportRuntimePermissions() {
        return mAppSupportsRuntimePermissions;
    }

    boolean isGrantingAllowed() {
        return (!mIsEphemeralApp || mContainsEphemeralPermission)
                && (mAppSupportsRuntimePermissions || mContainsPreRuntimePermission);
    }

    boolean isReviewRequired() {
        if (mAppSupportsRuntimePermissions) {
            return false;
        }
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isReviewRequired()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Are any of the permissions in this group user sensitive.
     *
     * @return {@code true} if any of the permissions in the group is user sensitive.
     */
    public boolean isUserSensitive() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isUserSensitive()) {
                return true;
            }
        }
        return false;
    }

    void unsetReviewRequired() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isReviewRequired()) {
                permission.unsetReviewRequired();
            }
        }

        if (!mDelayChanges) {
            persistChanges(false);
        }
    }

    boolean hasGrantedByDefaultPermission() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isGrantedByDefault()) {
                return true;
            }
        }
        return false;
    }

    public PackageInfo getApp() {
        return mPackageInfo;
    }

    String getName() {
        return mName;
    }

    String getDeclaringPackage() {
        return mDeclaringPackage;
    }

    String getIconPkg() {
        return mIconPkg;
    }

    int getIconResId() {
        return mIconResId;
    }

    CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Get the full un-ellipsized label of the permission group.
     *
     * @return the full label of the group.
     */
    public CharSequence getFullLabel() {
        return mFullLabel;
    }

    /**
     * @hide
     * @return The resource Id of the request string.
     */
    public @StringRes int getRequest() {
        return mRequest;
    }

    /**
     * Get the (subtitle) message explaining to the user that the permission is only granted to
     * the apps running in the foreground.
     *
     * @return the message or 0 if unset
     */
    public @StringRes int getRequestDetail() {
        return mRequestDetail;
    }

    /**
     * Get the title of the dialog explaining to the user that the permission is granted while
     * the app is in background and in foreground.
     *
     * @return the message or 0 if unset
     */
    public @StringRes int getBackgroundRequest() {
        return mBackgroundRequest;
    }

    /**
     * Get the (subtitle) message explaining to the user that the she/he is about to allow the
     * app to have background access.
     *
     * @return the message or 0 if unset
     */
    public @StringRes int getBackgroundRequestDetail() {
        return mBackgroundRequestDetail;
    }

    /**
     * Get the title of the dialog explaining to the user that the permission, which was
     * previously only granted for foreground, is granted while the app is in background and in
     * foreground.
     *
     * @return the message or 0 if unset
     */
    public @StringRes int getUpgradeRequest() {
        return mUpgradeRequest;
    }

    /**
     * Get the (subtitle) message explaining to the user that the she/he is about to allow the
     * app to have background access while currently having foreground only.
     *
     * @return the message or 0 if unset
     */
    public @StringRes int getUpgradeRequestDetail() {
        return mUpgradeRequestDetail;
    }

    public CharSequence getDescription() {
        return mDescription;
    }

    public UserHandle getUser() {
        return mUserHandle;
    }

    /**
     * Check if the group contains the permission.
     */
    public boolean hasPermission(String permission) {
        return mPermissions.get(permission) != null;
    }

    /**
     * Return a permission if in this group.
     *
     * @param permissionName The name of the permission
     *
     * @return The permission
     */
    public @Nullable Permission getPermission(@NonNull String permissionName) {
        return mPermissions.get(permissionName);
    }

    /**
     * Check if at least one of the permissions in the entire permission group should be considered
     * granted.
     */
    public boolean areRuntimePermissionsGranted() {
        return areRuntimePermissionsGranted(null);
    }

    /**
     * Check if at least one of the permissions in the filterPermissions should be considered
     * granted.
     */
    public boolean areRuntimePermissionsGranted(String[] filterPermissions) {
        return areRuntimePermissionsGranted(filterPermissions, false);
    }

    /**
     * @param filterPermissions the permissions to check for, null for all in this group
     * @param asOneTime add the requirement that at least one of the granted permissions must have
     *                 the ONE_TIME flag to return true
     */
    public boolean areRuntimePermissionsGranted(String[] filterPermissions, boolean asOneTime) {
        return areRuntimePermissionsGranted(filterPermissions, asOneTime, true);
    }

    /**
     * Returns true if at least one of the permissions in filterPermissions (or the entire
     * permission group if null) should be considered granted and satisfy the requirements
     * described by asOneTime and includingAppOp.
     *
     * @param filterPermissions the permissions to check for, null for all in this group
     * @param asOneTime add the requirement that the granted permission must have the ONE_TIME flag
     * @param includingAppOp add the requirement that if the granted permissions has a
     *                       corresponding AppOp, it must be allowed.
     */
    public boolean areRuntimePermissionsGranted(String[] filterPermissions, boolean asOneTime,
            boolean includingAppOp) {
        if (LocationUtils.isLocationGroupAndProvider(mContext, mName, mPackageInfo.packageName)) {
            return LocationUtils.isLocationEnabled(mContext) && !asOneTime;
        }
        // The permission of the extra location controller package is determined by the status of
        // the controller package itself.
        if (LocationUtils.isLocationGroupAndControllerExtraPackage(
                mContext, mName, mPackageInfo.packageName)) {
            return LocationUtils.isExtraLocationControllerPackageEnabled(mContext) && !asOneTime;
        }
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (filterPermissions != null
                    && !ArrayUtils.contains(filterPermissions, permission.getName())) {
                continue;
            }
            boolean isGranted = includingAppOp ? permission.isGrantedIncludingAppOp()
                    : permission.isGranted();
            if (isGranted && (!asOneTime || permission.isOneTime())) {
                return true;
            }
        }
        if (mBackgroundPermissions != null) {
            // If asOneTime is true and none of the foreground permissions are one-time, but some
            // background permissions are, then we still want to return true.
            return mBackgroundPermissions.areRuntimePermissionsGranted(filterPermissions,
                    asOneTime, includingAppOp);
        }
        return false;
    }

    boolean grantRuntimePermissions(boolean setByTheUser, boolean fixedByTheUser) {
        return grantRuntimePermissions(setByTheUser, fixedByTheUser, null);
    }

    /**
     * Set mode of an app-op if needed.
     *
     * @param op The op to set
     * @param uid The uid the app-op belongs to
     * @param mode The new mode
     *
     * @return {@code true} iff app-op was changed
     */
    private boolean setAppOpMode(@NonNull String op, int uid, int mode) {
        int currentMode = mAppOps.unsafeCheckOpRaw(op, uid, mPackageInfo.packageName);
        if (currentMode == mode) {
            return false;
        }

        mAppOps.setUidMode(op, uid, mode);
        return true;
    }

    /**
     * Allow the app op for a permission/uid.
     *
     * <p>There are three cases:
     * <dl>
     * <dt>The permission is not split into foreground/background</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_ALLOWED}</dd>
     * <dt>The permission is a foreground permission:</dt>
     * <dd><dl><dt>The background permission permission is granted</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_ALLOWED}</dd>
     * <dt>The background permission permission is <u>not</u> granted</dt>
     * <dd>The app op matching the permission will be set to
     * {@link AppOpsManager#MODE_FOREGROUND}</dd>
     * </dl></dd>
     * <dt>The permission is a background permission:</dt>
     * <dd>All granted foreground permissions for this background permission will be set to
     * {@link AppOpsManager#MODE_ALLOWED}</dd>
     * </dl>
     *
     * @param permission The permission which has an appOps that should be allowed
     * @param uid        The uid of the process the app op is for
     *
     * @return {@code true} iff app-op was changed
     */
    private boolean allowAppOp(Permission permission, int uid) {
        boolean wasChanged = false;

        if (permission.isBackgroundPermission()) {
            ArrayList<Permission> foregroundPermissions = permission.getForegroundPermissions();

            int numForegroundPermissions = foregroundPermissions.size();
            for (int i = 0; i < numForegroundPermissions; i++) {
                Permission foregroundPermission = foregroundPermissions.get(i);
                if (foregroundPermission.isAppOpAllowed()) {
                    wasChanged |= setAppOpMode(foregroundPermission.getAppOp(), uid, MODE_ALLOWED);
                }
            }
        } else {
            if (permission.hasBackgroundPermission()) {
                Permission backgroundPermission = permission.getBackgroundPermission();

                if (backgroundPermission == null) {
                    // The app requested a permission that has a background permission but it did
                    // not request the background permission, hence it can never get background
                    // access
                    wasChanged = setAppOpMode(permission.getAppOp(), uid, MODE_FOREGROUND);
                } else {
                    if (backgroundPermission.isAppOpAllowed()) {
                        wasChanged = setAppOpMode(permission.getAppOp(), uid, MODE_ALLOWED);
                    } else {
                        wasChanged = setAppOpMode(permission.getAppOp(), uid, MODE_FOREGROUND);
                    }
                }
            } else {
                wasChanged = setAppOpMode(permission.getAppOp(), uid, MODE_ALLOWED);
            }
        }

        return wasChanged;
    }

    /**
     * Kills the app the permissions belong to (and all apps sharing the same uid)
     *
     * @param reason The reason why the apps are killed
     */
    private void killApp(String reason) {
        mActivityManager.killUid(mPackageInfo.applicationInfo.uid, reason);
    }

    /**
     * Grant permissions of the group.
     *
     * <p>This also automatically grants all app ops for permissions that have app ops.
     * <p>This does <u>only</u> grant permissions in {@link #mPermissions}, i.e. usually not
     * the background permissions.
     *
     * @param setByTheUser If the user has made the decision. This does not unset the flag
     * @param fixedByTheUser If the user requested that she/he does not want to be asked again
     * @param filterPermissions If {@code null} all permissions of the group will be granted.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          granted.
     *
     * @return {@code true} iff all permissions of this group could be granted.
     */
    public boolean grantRuntimePermissions(boolean setByTheUser, boolean fixedByTheUser,
            String[] filterPermissions) {
        boolean killApp = false;
        boolean wasAllGranted = true;

        // We toggle permissions only to apps that support runtime
        // permissions, otherwise we toggle the app op corresponding
        // to the permission if the permission is granted to the app.
        for (Permission permission : mPermissions.values()) {
            if (filterPermissions != null
                    && !ArrayUtils.contains(filterPermissions, permission.getName())) {
                continue;
            }

            if (!permission.isGrantingAllowed(mIsEphemeralApp, mAppSupportsRuntimePermissions)) {
                // Skip unallowed permissions.
                continue;
            }

            boolean wasGranted = permission.isGrantedIncludingAppOp();

            if (mAppSupportsRuntimePermissions) {
                // Do not touch permissions fixed by the system.
                if (permission.isSystemFixed()) {
                    wasAllGranted = false;
                    break;
                }

                // Ensure the permission app op is enabled before the permission grant.
                if (permission.affectsAppOp() && !permission.isAppOpAllowed()) {
                    permission.setAppOpAllowed(true);
                }

                // Grant the permission if needed.
                if (!permission.isGranted()) {
                    permission.setGranted(true);
                }

                // Update the permission flags.
                if (!fixedByTheUser) {
                    if (permission.isUserFixed()) {
                        permission.setUserFixed(false);
                    }
                    if (setByTheUser) {
                        if (!permission.isUserSet()) {
                            permission.setUserSet(true);
                        }
                    }
                } else {
                    if (!permission.isUserFixed()) {
                        permission.setUserFixed(true);
                    }
                    if (permission.isUserSet()) {
                        permission.setUserSet(false);
                    }
                }
                if (permission.isReviewRequired()) {
                    permission.unsetReviewRequired();
                }
            } else {
                // Legacy apps cannot have a not granted permission but just in case.
                if (!permission.isGranted()) {
                    continue;
                }

                // If the permissions has no corresponding app op, then it is a
                // third-party one and we do not offer toggling of such permissions.
                if (permission.affectsAppOp()) {
                    if (!permission.isAppOpAllowed()) {
                        permission.setAppOpAllowed(true);

                        // Legacy apps do not know that they have to retry access to a
                        // resource due to changes in runtime permissions (app ops in this
                        // case). Therefore, we restart them on app op change, so they
                        // can pick up the change.
                        killApp = true;
                    }

                    // Mark that the permission is not kept granted only for compatibility.
                    if (permission.isRevokedCompat()) {
                        permission.setRevokedCompat(false);
                    }
                }

                // Granting a permission explicitly means the user already
                // reviewed it so clear the review flag on every grant.
                if (permission.isReviewRequired()) {
                    permission.unsetReviewRequired();
                }
            }

            // If we newly grant background access to the fine location, double-guess the user some
            // time later if this was really the right choice.
            if (!wasGranted && permission.isGrantedIncludingAppOp()) {
                if (permission.getName().equals(ACCESS_FINE_LOCATION)) {
                    Permission bgPerm = permission.getBackgroundPermission();
                    if (bgPerm != null) {
                        if (bgPerm.isGrantedIncludingAppOp()) {
                            mTriggerLocationAccessCheckOnPersist = true;
                        }
                    }
                } else if (permission.getName().equals(ACCESS_BACKGROUND_LOCATION)) {
                    ArrayList<Permission> fgPerms = permission.getForegroundPermissions();
                    if (fgPerms != null) {
                        int numFgPerms = fgPerms.size();
                        for (int fgPermNum = 0; fgPermNum < numFgPerms; fgPermNum++) {
                            Permission fgPerm = fgPerms.get(fgPermNum);

                            if (fgPerm.getName().equals(ACCESS_FINE_LOCATION)) {
                                if (fgPerm.isGrantedIncludingAppOp()) {
                                    mTriggerLocationAccessCheckOnPersist = true;
                                }

                                break;
                            }
                        }
                    }
                }
            }
        }

        if (!mDelayChanges) {
            persistChanges(false);

            if (killApp) {
                killApp(KILL_REASON_APP_OP_CHANGE);
            }
        }

        return wasAllGranted;
    }

    boolean revokeRuntimePermissions(boolean fixedByTheUser) {
        return revokeRuntimePermissions(fixedByTheUser, null);
    }

    /**
     * Disallow the app op for a permission/uid.
     *
     * <p>There are three cases:
     * <dl>
     * <dt>The permission is not split into foreground/background</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_IGNORED}</dd>
     * <dt>The permission is a foreground permission:</dt>
     * <dd>The app op matching the permission will be set to {@link AppOpsManager#MODE_IGNORED}</dd>
     * <dt>The permission is a background permission:</dt>
     * <dd>All granted foreground permissions for this background permission will be set to
     * {@link AppOpsManager#MODE_FOREGROUND}</dd>
     * </dl>
     *
     * @param permission The permission which has an appOps that should be disallowed
     * @param uid        The uid of the process the app op if for
     *
     * @return {@code true} iff app-op was changed
     */
    private boolean disallowAppOp(Permission permission, int uid) {
        boolean wasChanged = false;

        if (permission.isBackgroundPermission()) {
            ArrayList<Permission> foregroundPermissions = permission.getForegroundPermissions();

            int numForegroundPermissions = foregroundPermissions.size();
            for (int i = 0; i < numForegroundPermissions; i++) {
                Permission foregroundPermission = foregroundPermissions.get(i);
                if (foregroundPermission.isAppOpAllowed()) {
                    wasChanged |= setAppOpMode(foregroundPermission.getAppOp(), uid,
                            MODE_FOREGROUND);
                }
            }
        } else {
            wasChanged = setAppOpMode(permission.getAppOp(), uid, MODE_IGNORED);
        }

        return wasChanged;
    }

    /**
     * Revoke permissions of the group.
     *
     * <p>This also disallows all app ops for permissions that have app ops.
     * <p>This does <u>only</u> revoke permissions in {@link #mPermissions}, i.e. usually not
     * the background permissions.
     *
     * @param fixedByTheUser If the user requested that she/he does not want to be asked again
     * @param filterPermissions If {@code null} all permissions of the group will be revoked.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          revoked.
     *
     * @return {@code true} iff all permissions of this group could be revoked.
     */
    public boolean revokeRuntimePermissions(boolean fixedByTheUser, String[] filterPermissions) {
        boolean killApp = false;
        boolean wasAllRevoked = true;

        // We toggle permissions only to apps that support runtime
        // permissions, otherwise we toggle the app op corresponding
        // to the permission if the permission is granted to the app.
        for (Permission permission : mPermissions.values()) {
            if (filterPermissions != null
                    && !ArrayUtils.contains(filterPermissions, permission.getName())) {
                continue;
            }

            // Do not touch permissions fixed by the system.
            if (permission.isSystemFixed()) {
                wasAllRevoked = false;
                break;
            }

            if (mAppSupportsRuntimePermissions) {
                // Revoke the permission if needed.
                if (permission.isGranted()) {
                    permission.setGranted(false);
                }

                // Update the permission flags.
                if (fixedByTheUser) {
                    // Take a note that the user fixed the permission.
                    if (permission.isUserSet() || !permission.isUserFixed()) {
                        permission.setUserSet(false);
                        permission.setUserFixed(true);
                    }
                } else {
                    if (!permission.isUserSet() || permission.isUserFixed()) {
                        permission.setUserSet(true);
                        permission.setUserFixed(false);
                    }
                }

                if (permission.affectsAppOp()) {
                    permission.setAppOpAllowed(false);
                }
            } else {
                // Legacy apps cannot have a non-granted permission but just in case.
                if (!permission.isGranted()) {
                    continue;
                }

                // If the permission has no corresponding app op, then it is a
                // third-party one and we do not offer toggling of such permissions.
                if (permission.affectsAppOp()) {
                    if (permission.isAppOpAllowed()) {
                        permission.setAppOpAllowed(false);

                        // Disabling an app op may put the app in a situation in which it
                        // has a handle to state it shouldn't have, so we have to kill the
                        // app. This matches the revoke runtime permission behavior.
                        killApp = true;
                    }

                    // Mark that the permission is kept granted only for compatibility.
                    if (!permission.isRevokedCompat()) {
                        permission.setRevokedCompat(true);
                    }
                }
            }
        }

        if (!mDelayChanges) {
            persistChanges(false);

            if (killApp) {
                killApp(KILL_REASON_APP_OP_CHANGE);
            }
        }

        return wasAllRevoked;
    }

    /**
     * Mark permissions in this group as policy fixed.
     *
     * @param filterPermissions The permissions to mark
     */
    public void setPolicyFixed(@NonNull String[] filterPermissions) {
        for (String permissionName : filterPermissions) {
            Permission permission = mPermissions.get(permissionName);

            if (permission != null) {
                permission.setPolicyFixed(true);
            }
        }

        if (!mDelayChanges) {
            persistChanges(false);
        }
    }

    /**
     * Set the user-fixed flag for all permissions in this group.
     *
     * @param isUsedFixed if the flag should be set or not
     */
    public void setUserFixed(boolean isUsedFixed) {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            permission.setUserFixed(isUsedFixed);
        }

        if (!mDelayChanges) {
            persistChanges(false);
        }
    }

    /**
     * Mark this group as having been self-revoked.
     */
    public void setSelfRevoked() {
        mIsSelfRevoked = true;
    }

    /**
     * Set the one-time flag for all permissions in this group.
     *
     * @param isOneTime if the flag should be set or not
     */
    public void setOneTime(boolean isOneTime) {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            permission.setOneTime(isOneTime);
        }

        if (!mDelayChanges) {
            persistChanges(false);
        }
    }

    /**
     * Set the user-set flag for all permissions in this group.
     *
     * @param isUserSet if the flag should be set or not
     */
    public void setUserSet(boolean isUserSet) {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            permission.setUserSet(isUserSet);
        }

        if (!mDelayChanges) {
            persistChanges(false);
        }
    }

    /**
     * Get all permissions in the group.
     */
    public ArrayList<Permission> getPermissions() {
        return new ArrayList<>(mPermissions.values());
    }

    /**
     * @return An {@link AppPermissionGroup}-object that contains all background permissions for
     * this group.
     */
    public AppPermissionGroup getBackgroundPermissions() {
        return mBackgroundPermissions;
    }

    /**
     * @return {@code true} iff the app request at least one permission in this group that has a
     * background permission. It is possible that the app does not request the matching background
     * permission and hence will only ever get foreground access, never background access.
     */
    public boolean hasPermissionWithBackgroundMode() {
        return mHasPermissionWithBackgroundMode;
    }

    /**
     * Is the group a storage permission group that is referring to an app that does not have
     * isolated storage
     *
     * @return {@code true} iff this is a storage group on an app that does not have isolated
     * storage
     */
    public boolean isNonIsolatedStorage() {
        return mIsNonIsolatedStorage;
    }

    /**
     * Whether this is group that contains all the background permission for regular permission
     * group.
     *
     * @return {@code true} iff this is a background permission group.
     *
     * @see #getBackgroundPermissions()
     */
    public boolean isBackgroundGroup() {
        return mPermissions.valueAt(0).isBackgroundPermission();
    }

    /**
     * Whether this group supports one-time permissions
     * @return {@code true} iff this group supports one-time permissions
     */
    public boolean supportsOneTimeGrant() {
        return Utils.supportsOneTimeGrant(getName());
    }

    int getFlags() {
        int flags = 0;
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            flags |= permission.getFlags();
        }
        return flags;
    }

    boolean isUserFixed() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isUserFixed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if there's a permission in the group is policy fixed.
     */
    public boolean isPolicyFixed() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isPolicyFixed()) {
                return true;
            }
        }
        return false;
    }

    boolean isUserSet() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isUserSet()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if there's a permission in the group is system fixed.
     */
    public boolean isSystemFixed() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isSystemFixed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether any of the permissions in this group is one-time
     */
    public boolean isOneTime() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isOneTime()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether at least one permission is granted and every granted permission is one-time
     */
    public boolean isStrictlyOneTime() {
        boolean oneTimePermissionFound = false;
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isGranted()) {
                if (!permission.isOneTime()) {
                    return false;
                }
                oneTimePermissionFound = true;
            }
        }
        return oneTimePermissionFound;
    }

    @Override
    public int compareTo(AppPermissionGroup another) {
        final int result = mCollator.compare(mLabel.toString(), another.mLabel.toString());
        if (result == 0) {
            // Unbadged before badged.
            return mPackageInfo.applicationInfo.uid
                    - another.mPackageInfo.applicationInfo.uid;
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AppPermissionGroup)) {
            return false;
        }

        AppPermissionGroup other = (AppPermissionGroup) o;

        boolean equal = mName.equals(other.mName)
                && mPackageInfo.packageName.equals(other.mPackageInfo.packageName)
                && mUserHandle.equals(other.mUserHandle)
                && mPermissions.equals(other.mPermissions);
        if (!equal) {
            return false;
        }

        if (mBackgroundPermissions != null && other.getBackgroundPermissions() != null) {
            return mBackgroundPermissions.getPermissions().equals(
                    other.getBackgroundPermissions().getPermissions());
        }
        return mBackgroundPermissions == other.getBackgroundPermissions();
    }

    @Override
    public int hashCode() {
        ArrayList<Permission> backgroundPermissions = new ArrayList<>();
        if (mBackgroundPermissions != null) {
            backgroundPermissions = mBackgroundPermissions.getPermissions();
        }
        return Objects.hash(mName, mPackageInfo.packageName, mUserHandle, mPermissions,
                backgroundPermissions);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(getClass().getSimpleName());
        builder.append("{name=").append(mName);
        if (mBackgroundPermissions != null) {
            builder.append(", <has background permissions>}");
        }
        if (!mPermissions.isEmpty()) {
            builder.append(", <has permissions>}");
        } else {
            builder.append('}');
        }
        return builder.toString();
    }

    private void addPermission(Permission permission) {
        mPermissions.put(permission.getName(), permission);
        if (permission.isEphemeral()) {
            mContainsEphemeralPermission = true;
        }
        if (!permission.isRuntimeOnly()) {
            mContainsPreRuntimePermission = true;
        }
    }

    /**
     * If the changes to this group were delayed, persist them to the platform.
     *
     * @param mayKillBecauseOfAppOpsChange If the app these permissions belong to may be killed if
     *                                     app ops change. If this is set to {@code false} the
     *                                     caller has to make sure to kill the app if needed.
     */
    public void persistChanges(boolean mayKillBecauseOfAppOpsChange) {
        persistChanges(mayKillBecauseOfAppOpsChange, null, null);
    }

    /**
     * If the changes to this group were delayed, persist them to the platform.
     *
     * @param mayKillBecauseOfAppOpsChange If the app these permissions belong to may be killed if
     *                                     app ops change. If this is set to {@code false} the
     *                                     caller has to make sure to kill the app if needed.
     * @param revokeReason If any permissions are getting revoked, the reason for revoking them.
     */
    public void persistChanges(boolean mayKillBecauseOfAppOpsChange, String revokeReason) {
        persistChanges(mayKillBecauseOfAppOpsChange, revokeReason, null);
    }

    /**
     * If the changes to this group were delayed, persist them to the platform.
     *
     * @param mayKillBecauseOfAppOpsChange If the app these permissions belong to may be killed if
     *                                     app ops change. If this is set to {@code false} the
     *                                     caller has to make sure to kill the app if needed.
     * @param revokeReason If any permissions are getting revoked, the reason for revoking them.
     * @param filterPermissions If provided, only persist state for the given permissions
     */
    public void persistChanges(boolean mayKillBecauseOfAppOpsChange, String revokeReason,
            Set<String> filterPermissions) {
        int uid = mPackageInfo.applicationInfo.uid;

        int numPermissions = mPermissions.size();
        boolean shouldKillApp = false;

        for (int i = 0; i < numPermissions; i++) {
            Permission permission = mPermissions.valueAt(i);

            if (filterPermissions != null && !filterPermissions.contains(permission.getName())) {
                continue;
            }

            if (!permission.isSystemFixed()) {
                if (permission.isGranted()) {
                    mPackageManager.grantRuntimePermission(mPackageInfo.packageName,
                            permission.getName(), mUserHandle);
                } else {
                    boolean isCurrentlyGranted = mContext.checkPermission(permission.getName(), -1,
                            uid) == PERMISSION_GRANTED;

                    if (isCurrentlyGranted) {
                        if (revokeReason == null) {
                            mPackageManager.revokeRuntimePermission(mPackageInfo.packageName,
                                    permission.getName(), mUserHandle);
                        } else {
                            mPackageManager.revokeRuntimePermission(mPackageInfo.packageName,
                                    permission.getName(), mUserHandle, revokeReason);
                        }
                    }
                }
            }

//            int flags = (permission.isUserSet() ? PackageManager.FLAG_PERMISSION_USER_SET : 0)
//                    | (permission.isUserFixed() ? PackageManager.FLAG_PERMISSION_USER_FIXED : 0)
//                    | (permission.isRevokedCompat()
//                    ? PackageManager.FLAG_PERMISSION_REVOKED_COMPAT : 0)
//                    | (permission.isPolicyFixed() ?
//                          PackageManager.FLAG_PERMISSION_POLICY_FIXED : 0)
//                    | (permission.isReviewRequired()
//                    ? PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED : 0)
//                    | (permission.isOneTime() ? PackageManager.FLAG_PERMISSION_ONE_TIME : 0)
//                    | (permission.isSelectedLocationAccuracy()
//                    ? PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY : 0);

//            mPackageManager.updatePermissionFlags(permission.getName(),
//                    mPackageInfo.packageName,
//                    PackageManager.FLAG_PERMISSION_USER_SET
//                            | PackageManager.FLAG_PERMISSION_USER_FIXED
//                            | PackageManager.FLAG_PERMISSION_REVOKED_COMPAT
//                            | PackageManager.FLAG_PERMISSION_POLICY_FIXED
//                            | (permission.isReviewRequired()
//                            ? 0 : PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED)
//                            | PackageManager.FLAG_PERMISSION_ONE_TIME
//                            | PackageManager.FLAG_PERMISSION_AUTO_REVOKED // clear auto revoke
//                            | PackageManager.FLAG_PERMISSION_SELECTED_LOCATION_ACCURACY,
//                    flags, mUserHandle);

            if (permission.affectsAppOp()) {
                if (!permission.isSystemFixed()) {
                    // Enabling/Disabling an app op may put the app in a situation in which it has
                    // a handle to state it shouldn't have, so we have to kill the app. This matches
                    // the revoke runtime permission behavior.
                    if (permission.isAppOpAllowed()) {
                        boolean wasChanged = allowAppOp(permission, uid);
                        shouldKillApp |= wasChanged && !mAppSupportsRuntimePermissions;
                    } else {
                        shouldKillApp |= disallowAppOp(permission, uid);
                    }
                }
            }
        }

        if (mayKillBecauseOfAppOpsChange && shouldKillApp) {
            killApp(KILL_REASON_APP_OP_CHANGE);
        }

//        if (mTriggerLocationAccessCheckOnPersist) {
//            new LocationAccessCheck(mContext, null).checkLocationAccessSoon();
//            mTriggerLocationAccessCheckOnPersist = false;
//        }

//        String packageName = mPackageInfo.packageName;
//        if (areRuntimePermissionsGranted(null, true, false)) {
//            // Required to read device config in Utils.getOneTimePermissions*().
//            final long token = Binder.clearCallingIdentity();
//            try {
//                if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                mContext.getSystemService(PermissionManager.class)
//                        .startOneTimePermissionSession(packageName,
//                                Utils.getOneTimePermissionsTimeout(),
//                                Utils.getOneTimePermissionsKilledDelay(mIsSelfRevoked),
//                                ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER,
//                                ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE);
//                } else {
//                    mContext.getSystemService(PermissionManager.class)
//                            .startOneTimePermissionSession(packageName,
//                                    Utils.getOneTimePermissionsTimeout(),
//                                    ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_RESET_TIMER,
//                                    ONE_TIME_PACKAGE_IMPORTANCE_LEVEL_TO_KEEP_SESSION_ALIVE);
//                }
//            } finally {
//                Binder.restoreCallingIdentity(token);
//            }
//        } else {
//            mContext.getSystemService(PermissionManager.class)
//                    .stopOneTimePermissionSession(packageName);
//        }
    }

    /**
     * Check if permission group contains a runtime permission that split from an installed
     * permission and the split happened in an Android version higher than app's targetSdk.
     *
     * @return {@code true} if there is such permission, {@code false} otherwise
     */
    public boolean hasInstallToRuntimeSplit() {
        PermissionManager permissionManager =
                (PermissionManager) mContext.getSystemService(PermissionManager.class);

        int numSplitPerms = permissionManager.getSplitPermissions().size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            PermissionManager.SplitPermissionInfo spi =
                    permissionManager.getSplitPermissions().get(splitPermNum);
            String splitPerm = spi.getSplitPermission();

            PermissionInfo pi;
            try {
                pi = mPackageManager.getPermissionInfo(splitPerm, 0);
            } catch (NameNotFoundException e) {
                Log.w(LOG_TAG, "No such permission: " + splitPerm, e);
                continue;
            }

            // Skip if split permission is not "install" permission.
            if (pi.getProtection() != pi.PROTECTION_NORMAL) {
                continue;
            }

            List<String> newPerms = spi.getNewPermissions();
            int numNewPerms = newPerms.size();
            for (int newPermNum = 0; newPermNum < numNewPerms; newPermNum++) {
                String newPerm = newPerms.get(newPermNum);

                if (!hasPermission(newPerm)) {
                    continue;
                }

                try {
                    pi = mPackageManager.getPermissionInfo(newPerm, 0);
                } catch (NameNotFoundException e) {
                    Log.w(LOG_TAG, "No such permission: " + newPerm, e);
                    continue;
                }

                // Skip if new permission is not "runtime" permission.
                if (pi.getProtection() != pi.PROTECTION_DANGEROUS) {
                    continue;
                }

                if (mPackageInfo.applicationInfo.targetSdkVersion < spi.getTargetSdk()) {
                    return true;
                }
            }
        }
        return false;
    }
}
