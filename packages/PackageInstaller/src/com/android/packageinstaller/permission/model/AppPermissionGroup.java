/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.model;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;

import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.res.ResourceId;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.utils.ArrayUtils;
import com.android.packageinstaller.permission.utils.LocationUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

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
    private static final String PLATFORM_PACKAGE_NAME = "android";

    private static final String KILL_REASON_APP_OP_CHANGE = "Permission related app op changed";

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
    private final @StringRes int mRequest;
    private final @StringRes int mRequestDetail;
    private final @StringRes int mBackgroundRequest;
    private final @StringRes int mBackgroundRequestDetail;
    private final CharSequence mDescription;
    private final ArrayMap<String, Permission> mPermissions = new ArrayMap<>();
    private final String mIconPkg;
    private final int mIconResId;

    /**
     * Some permissions are split into foreground and background permission. All non-split and
     * foreground permissions are in {@link #mPermissions}, all background permissions are in
     * this field.
     */
    private AppPermissionGroup mBackgroundPermissions;

    private final boolean mAppSupportsRuntimePermissions;
    private final boolean mIsEphemeralApp;
    private boolean mContainsEphemeralPermission;
    private boolean mContainsPreRuntimePermission;

    /**
     * Does this group contain at least one permission that is split into a foreground and
     * background permission? This does not necessarily mean that the app also requested the
     * background permission.
     */
    private boolean mHasPermissionWithBackgroundMode;

    public static AppPermissionGroup create(Context context, PackageInfo packageInfo,
            String permissionName) {
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

        PackageItemInfo groupInfo = permissionInfo;
        if (permissionInfo.group != null) {
            try {
                groupInfo = context.getPackageManager().getPermissionGroupInfo(
                        permissionInfo.group, 0);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        List<PermissionInfo> permissionInfos = null;
        if (groupInfo instanceof PermissionGroupInfo) {
            try {
                permissionInfos = context.getPackageManager().queryPermissionsByGroup(
                        groupInfo.name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        return create(context, packageInfo, groupInfo, permissionInfos,
                Process.myUserHandle());
    }

    public static AppPermissionGroup create(Context context, PackageInfo packageInfo,
            PackageItemInfo groupInfo, List<PermissionInfo> permissionInfos,
            UserHandle userHandle) {

        AppPermissionGroup group = new AppPermissionGroup(context, packageInfo, groupInfo.name,
                groupInfo.packageName, groupInfo.loadLabel(context.getPackageManager()),
                loadGroupDescription(context, groupInfo), getRequest(groupInfo),
                getRequestDetail(groupInfo), getBackgroundRequest(groupInfo),
                getBackgroundRequestDetail(groupInfo), groupInfo.packageName, groupInfo.icon,
                userHandle);

        if (groupInfo instanceof PermissionInfo) {
            permissionInfos = new ArrayList<>();
            permissionInfos.add((PermissionInfo) groupInfo);
        }

        if (permissionInfos == null || permissionInfos.isEmpty()) {
            return null;
        }

        // Parse and create permissions reqested by the app
        ArrayMap<String, Permission> allPermissions = new ArrayMap<>();
        final int permissionCount = packageInfo.requestedPermissions.length;
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

            final boolean appOpAllowed = appOp != null
                    && context.getSystemService(AppOpsManager.class).checkOpNoThrow(appOp,
                    packageInfo.applicationInfo.uid, packageInfo.packageName)
                    == MODE_ALLOWED;

            final int flags = context.getPackageManager().getPermissionFlags(
                    requestedPermission, packageInfo.packageName, userHandle);

            Permission permission = new Permission(requestedPermission,
                    requestedPermissionInfo.backgroundPermission, granted,
                    appOp, appOpAllowed, flags, requestedPermissionInfo.protectionLevel);

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
                }
            }
        }

        // Add permissions found to this group
        for (int i = 0; i < numPermissions; i++) {
            Permission permission = allPermissions.valueAt(i);

            if (permission.isBackgroundPermission()) {
                if (group.getBackgroundPermissions() == null) {
                    group.mBackgroundPermissions = new AppPermissionGroup(group.mContext,
                            group.getApp(), group.getName(), group.getDeclaringPackage(),
                            group.getLabel(), group.getDescription(), group.getRequest(),
                            group.getRequestDetail(), group.getBackgroundRequest(),
                            group.getBackgroundRequestDetail(), group.getIconPkg(),
                            group.getIconResId(), UserHandle.of(group.getUserId()));
                }

                group.getBackgroundPermissions().addPermission(permission);
            } else {
                group.addPermission(permission);
            }
        }

        return group;
    }

    private static @StringRes int getRequest(PackageItemInfo group) {
        if (group instanceof PermissionGroupInfo) {
            return ((PermissionGroupInfo) group).requestRes;
        } else if (group instanceof PermissionInfo) {
            return ((PermissionInfo) group).requestRes;
        } else {
            return 0;
        }
    }

    private static CharSequence loadGroupDescription(Context context, PackageItemInfo group) {
        CharSequence description = null;
        if (group instanceof PermissionGroupInfo) {
            description = ((PermissionGroupInfo) group).loadDescription(
                    context.getPackageManager());
        } else if (group instanceof PermissionInfo) {
            description = ((PermissionInfo) group).loadDescription(
                    context.getPackageManager());
        }

        if (description == null || description.length() <= 0) {
            description = context.getString(R.string.default_permission_description);
        }

        return description;
    }

    private AppPermissionGroup(Context context, PackageInfo packageInfo, String name,
            String declaringPackage, CharSequence label, CharSequence description,
            @StringRes int request, @StringRes int requestDetail,
            @StringRes int backgroundRequest, @StringRes int backgroundRequestDetail,
            String iconPkg, int iconResId, UserHandle userHandle) {
        mContext = context;
        mUserHandle = userHandle;
        mPackageManager = mContext.getPackageManager();
        mPackageInfo = packageInfo;
        mAppSupportsRuntimePermissions = packageInfo.applicationInfo
                .targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
        mIsEphemeralApp = packageInfo.applicationInfo.isInstantApp();
        mAppOps = context.getSystemService(AppOpsManager.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
        mDeclaringPackage = declaringPackage;
        mName = name;
        mLabel = label;
        mDescription = description;
        mCollator = Collator.getInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        mRequest = request;
        mRequestDetail = requestDetail;
        mBackgroundRequest = backgroundRequest;
        mBackgroundRequestDetail = backgroundRequestDetail;
        if (iconResId != 0) {
            mIconPkg = iconPkg;
            mIconResId = iconResId;
        } else {
            mIconPkg = context.getPackageName();
            mIconResId = R.drawable.ic_perm_device_info;
        }
    }

    public boolean doesSupportRuntimePermissions() {
        return mAppSupportsRuntimePermissions;
    }

    public boolean isGrantingAllowed() {
        return (!mIsEphemeralApp || mContainsEphemeralPermission)
                && (mAppSupportsRuntimePermissions || mContainsPreRuntimePermission);
    }

    public boolean isReviewRequired() {
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

    public void resetReviewRequired() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isReviewRequired()) {
                permission.resetReviewRequired();
                mPackageManager.updatePermissionFlags(permission.getName(),
                        mPackageInfo.packageName,
                        PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED,
                        0, mUserHandle);
            }
        }
    }

    public boolean hasGrantedByDefaultPermission() {
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

    public String getName() {
        return mName;
    }

    public String getDeclaringPackage() {
        return mDeclaringPackage;
    }

    public String getIconPkg() {
        return mIconPkg;
    }

    public int getIconResId() {
        return mIconResId;
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * @hide
     * @return The resource Id of the request string.
     */
    @SystemApi
    public @StringRes int getRequest() {
        return mRequest;
    }

    /**
     * Extract the (subtitle) message explaining to the user that the permission is only granted to
     * the apps running in the foreground.
     *
     * @param info The package item info to extract the message from
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    private static @StringRes int getRequestDetail(PackageItemInfo info) {
        if (info instanceof PermissionGroupInfo) {
            return ((PermissionGroupInfo) info).requestDetailResourceId;
        } else {
            return ResourceId.ID_NULL;
        }
    }

    /**
     * Get the (subtitle) message explaining to the user that the permission is only granted to
     * the apps running in the foreground.
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    public @StringRes int getRequestDetail() {
        return mRequestDetail;
    }

    /**
     * Extract the title of the dialog explaining to the user that the permission is granted while
     * the app is in background and in foreground.
     *
     * @param info The package item info to extract the message from
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    private static @StringRes int getBackgroundRequest(PackageItemInfo info) {
        if (info instanceof PermissionGroupInfo) {
            return ((PermissionGroupInfo) info).backgroundRequestResourceId;
        } else {
            return ResourceId.ID_NULL;
        }
    }

    /**
     * Get the title of the dialog explaining to the user that the permission is granted while
     * the app is in background and in foreground.
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    public @StringRes int getBackgroundRequest() {
        return mBackgroundRequest;
    }

    /**
     * Extract the (subtitle) message explaining to the user that the she/he is about to allow the
     * app to have background access.
     *
     * @param info The package item info to extract the message from
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    private static @StringRes int getBackgroundRequestDetail(PackageItemInfo info) {
        if (info instanceof PermissionGroupInfo) {
            return ((PermissionGroupInfo) info).backgroundRequestDetailResourceId;
        } else {
            return ResourceId.ID_NULL;
        }
    }

    /**
     * Get the (subtitle) message explaining to the user that the she/he is about to allow the
     * app to have background access.
     *
     * @return the message of {@link ResourceId#ID_NULL} if unset
     */
    public @StringRes int getBackgroundRequestDetail() {
        return mBackgroundRequestDetail;
    }

    public CharSequence getDescription() {
        return mDescription;
    }

    public int getUserId() {
        return mUserHandle.getIdentifier();
    }

    public boolean hasPermission(String permission) {
        return mPermissions.get(permission) != null;
    }

    public boolean areRuntimePermissionsGranted() {
        return areRuntimePermissionsGranted(null);
    }

    public boolean areRuntimePermissionsGranted(String[] filterPermissions) {
        if (LocationUtils.isLocationGroupAndProvider(mName, mPackageInfo.packageName)) {
            return LocationUtils.isLocationEnabled(mContext);
        }
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (filterPermissions != null
                    && !ArrayUtils.contains(filterPermissions, permission.getName())) {
                continue;
            }
            if (mAppSupportsRuntimePermissions) {
                if (permission.isGranted()) {
                    return true;
                }
            } else if (permission.isGranted()
                    && (!permission.affectsAppOp() || permission.isAppOpAllowed())
                    && !permission.isReviewRequired()) {
                return true;
            }
        }
        return false;
    }

    public boolean grantRuntimePermissions(boolean fixedByTheUser) {
        return grantRuntimePermissions(fixedByTheUser, null);
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
     * @param uid        The uid of the process the app op if for
     */
    private void allowAppOp(Permission permission, int uid) {
        if (permission.isBackgroundPermission()) {
            ArrayList<Permission> foregroundPermissions = permission.getForegroundPermissions();

            int numForegroundPermissions = foregroundPermissions.size();
            for (int i = 0; i < numForegroundPermissions; i++) {
                Permission foregroundPermission = foregroundPermissions.get(i);
                if (foregroundPermission.isAppOpAllowed()) {
                    mAppOps.setUidMode(foregroundPermission.getAppOp(), uid, MODE_ALLOWED);
                }
            }
        } else {
            if (permission.hasBackgroundPermission()) {
                Permission backgroundPermission = permission.getBackgroundPermission();

                if (backgroundPermission == null) {
                    // The app requested a permission that has a background permission but it did
                    // not request the background permission, hence it can never get background
                    // access
                    mAppOps.setUidMode(permission.getAppOp(), uid, MODE_FOREGROUND);
                } else {
                    if (backgroundPermission.isAppOpAllowed()) {
                        mAppOps.setUidMode(permission.getAppOp(), uid, MODE_ALLOWED);
                    } else {
                        mAppOps.setUidMode(permission.getAppOp(), uid, MODE_FOREGROUND);
                    }
                }
            } else {
                mAppOps.setUidMode(permission.getAppOp(), uid, MODE_ALLOWED);
            }
        }
    }

    /**
     * Grant permissions of the group.
     *
     * <p>This also automatically grants all app ops for permissions that have app ops.
     * <p>This does <u>only</u> grant permissions in {@link #mPermissions}, i.e. usually not
     * the background permissions.
     *
     * @param fixedByTheUser If the user requested that she/he does not want to be asked again
     * @param filterPermissions If {@code null} all permissions of the group will be granted.
     *                          Otherwise only permissions in {@code filterPermissions} will be
     *                          granted.
     *
     * @return {@code true} iff all permissions of this group could be granted.
     */
    public boolean grantRuntimePermissions(boolean fixedByTheUser, String[] filterPermissions) {
        final int uid = mPackageInfo.applicationInfo.uid;

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

            if (mAppSupportsRuntimePermissions) {
                // Do not touch permissions fixed by the system.
                if (permission.isSystemFixed()) {
                    return false;
                }

                // Ensure the permission app op enabled before the permission grant.
                if (permission.affectsAppOp() && !permission.isAppOpAllowed()) {
                    permission.setAppOpAllowed(true);
                    allowAppOp(permission, uid);
                }

                // Grant the permission if needed.
                if (!permission.isGranted()) {
                    permission.setGranted(true);
                    mPackageManager.grantRuntimePermission(mPackageInfo.packageName,
                            permission.getName(), mUserHandle);
                }

                // Update the permission flags.
                if (!fixedByTheUser) {
                    // Now the apps can ask for the permission as the user
                    // no longer has it fixed in a denied state.
                    if (permission.isUserFixed() || permission.isUserSet()) {
                        permission.setUserFixed(false);
                        permission.setUserSet(false);
                        mPackageManager.updatePermissionFlags(permission.getName(),
                                mPackageInfo.packageName,
                                PackageManager.FLAG_PERMISSION_USER_FIXED
                                        | PackageManager.FLAG_PERMISSION_USER_SET,
                                0, mUserHandle);
                    }
                }
            } else {
                // Legacy apps cannot have a not granted permission but just in case.
                if (!permission.isGranted()) {
                    continue;
                }

                int killUid = -1;
                int mask = 0;

                // If the permissions has no corresponding app op, then it is a
                // third-party one and we do not offer toggling of such permissions.
                if (permission.affectsAppOp()) {
                    if (!permission.isAppOpAllowed()) {
                        permission.setAppOpAllowed(true);
                        allowAppOp(permission, uid);

                        // Legacy apps do not know that they have to retry access to a
                        // resource due to changes in runtime permissions (app ops in this
                        // case). Therefore, we restart them on app op change, so they
                        // can pick up the change.
                        killUid = uid;
                    }

                    // Mark that the permission should not be be granted on upgrade
                    // when the app begins supporting runtime permissions.
                    if (permission.shouldRevokeOnUpgrade()) {
                        permission.setRevokeOnUpgrade(false);
                        mask |= PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                    }
                }

                // Granting a permission explicitly means the user already
                // reviewed it so clear the review flag on every grant.
                if (permission.isReviewRequired()) {
                    permission.resetReviewRequired();
                    mask |= PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
                }

                if (mask != 0) {
                    mPackageManager.updatePermissionFlags(permission.getName(),
                            mPackageInfo.packageName, mask, 0, mUserHandle);
                }

                if (killUid != -1) {
                    mActivityManager.killUid(uid, KILL_REASON_APP_OP_CHANGE);
                }
            }
        }

        return true;
    }

    public boolean revokeRuntimePermissions(boolean fixedByTheUser) {
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
     */
    private void disallowAppOp(Permission permission, int uid) {
        if (permission.isBackgroundPermission()) {
            ArrayList<Permission> foregroundPermissions = permission.getForegroundPermissions();

            int numForegroundPermissions = foregroundPermissions.size();
            for (int i = 0; i < numForegroundPermissions; i++) {
                Permission foregroundPermission = foregroundPermissions.get(i);
                if (foregroundPermission.isAppOpAllowed()) {
                    mAppOps.setUidMode(foregroundPermission.getAppOp(), uid, MODE_FOREGROUND);
                }
            }
        } else {
            mAppOps.setUidMode(permission.getAppOp(), uid, MODE_IGNORED);
        }
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
        final int uid = mPackageInfo.applicationInfo.uid;

        // We toggle permissions only to apps that support runtime
        // permissions, otherwise we toggle the app op corresponding
        // to the permission if the permission is granted to the app.
        for (Permission permission : mPermissions.values()) {
            if (filterPermissions != null
                    && !ArrayUtils.contains(filterPermissions, permission.getName())) {
                continue;
            }

            if (mAppSupportsRuntimePermissions) {
                // Do not touch permissions fixed by the system.
                if (permission.isSystemFixed()) {
                    return false;
                }

                // Revoke the permission if needed.
                if (permission.isGranted()) {
                    permission.setGranted(false);
                    mPackageManager.revokeRuntimePermission(mPackageInfo.packageName,
                            permission.getName(), mUserHandle);
                }

                // Update the permission flags.
                if (fixedByTheUser) {
                    // Take a note that the user fixed the permission.
                    if (permission.isUserSet() || !permission.isUserFixed()) {
                        permission.setUserSet(false);
                        permission.setUserFixed(true);
                        mPackageManager.updatePermissionFlags(permission.getName(),
                                mPackageInfo.packageName,
                                PackageManager.FLAG_PERMISSION_USER_SET
                                        | PackageManager.FLAG_PERMISSION_USER_FIXED,
                                PackageManager.FLAG_PERMISSION_USER_FIXED,
                                mUserHandle);
                    }
                } else {
                    if (!permission.isUserSet() || permission.isUserFixed()) {
                        permission.setUserSet(true);
                        permission.setUserFixed(false);
                        // Take a note that the user already chose once.
                        mPackageManager.updatePermissionFlags(permission.getName(),
                                mPackageInfo.packageName,
                                PackageManager.FLAG_PERMISSION_USER_SET
                                        | PackageManager.FLAG_PERMISSION_USER_FIXED,
                                PackageManager.FLAG_PERMISSION_USER_SET,
                                mUserHandle);
                    }
                }

                if (permission.affectsAppOp()) {
                    permission.setAppOpAllowed(false);
                    disallowAppOp(permission, uid);
                }
            } else {
                // Legacy apps cannot have a non-granted permission but just in case.
                if (!permission.isGranted()) {
                    continue;
                }

                int mask = 0;
                int flags = 0;
                int killUid = -1;

                // If the permission has no corresponding app op, then it is a
                // third-party one and we do not offer toggling of such permissions.
                if (permission.affectsAppOp()) {
                    if (permission.isAppOpAllowed()) {
                        permission.setAppOpAllowed(false);
                        disallowAppOp(permission, uid);

                        // Disabling an app op may put the app in a situation in which it
                        // has a handle to state it shouldn't have, so we have to kill the
                        // app. This matches the revoke runtime permission behavior.
                        killUid = uid;
                    }

                    // Mark that the permission should not be granted on upgrade
                    // when the app begins supporting runtime permissions.
                    if (!permission.shouldRevokeOnUpgrade()) {
                        permission.setRevokeOnUpgrade(true);
                        mask |= PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                        flags |= PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
                    }
                }

                if (mask != 0) {
                    mPackageManager.updatePermissionFlags(permission.getName(),
                            mPackageInfo.packageName, mask, flags, mUserHandle);
                }

                if (killUid != -1) {
                    mActivityManager.killUid(uid, KILL_REASON_APP_OP_CHANGE);
                }
            }
        }

        return true;
    }

    public void setPolicyFixed() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            permission.setPolicyFixed(true);
            mPackageManager.updatePermissionFlags(permission.getName(),
                    mPackageInfo.packageName,
                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                    mUserHandle);
        }
    }

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

    public int getFlags() {
        int flags = 0;
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            flags |= permission.getFlags();
        }
        return flags;
    }

    public boolean isUserFixed() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isUserFixed()) {
                return true;
            }
        }
        return false;
    }

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

    public boolean isUserSet() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            Permission permission = mPermissions.valueAt(i);
            if (permission.isUserSet()) {
                return true;
            }
        }
        return false;
    }

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
        if (o == null || !(o instanceof AppPermissionGroup)) {
            return false;
        }

        AppPermissionGroup other = (AppPermissionGroup) o;
        return mName.equals(other.mName)
                && mPackageInfo.packageName.equals(other.mPackageInfo.packageName)
                && mUserHandle.equals(other.mUserHandle);
    }

    @Override
    public int hashCode() {
        return mName.hashCode() + mPackageInfo.packageName.hashCode() + mUserHandle.hashCode();
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
}
