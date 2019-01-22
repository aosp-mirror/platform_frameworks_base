/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib;

import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
import static android.app.admin.DevicePolicyManager.PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.widget.LockPatternUtils;

import java.util.List;
import java.util.Set;

/**
 * Utility class to host methods usable in adding a restricted padlock icon and showing admin
 * support message dialog.
 */
public class RestrictedLockUtilsInternal extends RestrictedLockUtils {

    private static final String LOG_TAG = "RestrictedLockUtils";

    /**
     * @return drawables for displaying with settings that are locked by a device admin.
     */
    public static Drawable getRestrictedPadlock(Context context) {
        Drawable restrictedPadlock = context.getDrawable(android.R.drawable.ic_info);
        final int iconSize = context.getResources().getDimensionPixelSize(
                android.R.dimen.config_restrictedIconSize);

        TypedArray ta = context.obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        restrictedPadlock.setTint(colorAccent);

        restrictedPadlock.setBounds(0, 0, iconSize, iconSize);
        return restrictedPadlock;
    }

    /**
     * Checks if a restriction is enforced on a user and returns the enforced admin and
     * admin userId.
     *
     * @param userRestriction Restriction to check
     * @param userId User which we need to check if restriction is enforced on.
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} If the restriction is not set. If the restriction is set by both device owner
     * and profile owner, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfRestrictionEnforced(Context context,
            String userRestriction, int userId) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }

        final UserManager um = UserManager.get(context);
        final List<UserManager.EnforcingUser> enforcingUsers =
                um.getUserRestrictionSources(userRestriction, UserHandle.of(userId));

        if (enforcingUsers.isEmpty()) {
            // Restriction is not enforced.
            return null;
        } else if (enforcingUsers.size() > 1) {
            return EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(userRestriction);
        }

        final int restrictionSource = enforcingUsers.get(0).getUserRestrictionSource();
        final int adminUserId = enforcingUsers.get(0).getUserHandle().getIdentifier();
        if (restrictionSource == UserManager.RESTRICTION_SOURCE_PROFILE_OWNER) {
            // Check if it is a profile owner of the user under consideration.
            if (adminUserId == userId) {
                return getProfileOwner(context, userRestriction, adminUserId);
            } else {
                // Check if it is a profile owner of a managed profile of the current user.
                // Otherwise it is in a separate user and we return a default EnforcedAdmin.
                final UserInfo parentUser = um.getProfileParent(adminUserId);
                return (parentUser != null && parentUser.id == userId)
                        ? getProfileOwner(context, userRestriction, adminUserId)
                        : EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(userRestriction);
            }
        } else if (restrictionSource == UserManager.RESTRICTION_SOURCE_DEVICE_OWNER) {
            // When the restriction is enforced by device owner, return the device owner admin only
            // if the admin is for the {@param userId} otherwise return a default EnforcedAdmin.
            return adminUserId == userId
                    ? getDeviceOwner(context, userRestriction)
                    : EnforcedAdmin.createDefaultEnforcedAdminWithRestriction(userRestriction);
        }

        // If the restriction is enforced by system then return null.
        return null;
    }

    public static boolean hasBaseUserRestriction(Context context,
            String userRestriction, int userId) {
        final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return um.hasBaseUserRestriction(userRestriction, UserHandle.of(userId));
    }

    /**
     * Checks whether keyguard features are disabled by policy.
     *
     * @param context {@link Context} for the calling user.
     *
     * @param keyguardFeatures Any one of keyguard features that can be
     * disabled by {@link android.app.admin.DevicePolicyManager#setKeyguardDisabledFeatures}.
     *
     * @param userId User to check enforced admin status for.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} If the notification features are not disabled. If the restriction is set by
     * multiple admins, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfKeyguardFeaturesDisabled(Context context,
            int keyguardFeatures, final @UserIdInt int userId) {
        final LockSettingCheck check = (dpm, admin, checkUser) -> {
            int effectiveFeatures = dpm.getKeyguardDisabledFeatures(admin, checkUser);
            if (checkUser != userId) {
                effectiveFeatures &= PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER;
            }
            return (effectiveFeatures & keyguardFeatures) != KEYGUARD_DISABLE_FEATURES_NONE;
        };
        if (UserManager.get(context).getUserInfo(userId).isManagedProfile()) {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return findEnforcedAdmin(dpm.getActiveAdminsAsUser(userId), dpm, userId, check);
        }
        return checkForLockSetting(context, userId, check);
    }

    /**
     * @return the UserHandle for a userId. Return null for USER_NULL
     */
    private static UserHandle getUserHandleOf(@UserIdInt int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        } else {
            return UserHandle.of(userId);
        }
    }

    /**
     * Filter a set of device admins based on a predicate {@code check}. This is equivalent to
     * {@code admins.stream().filter(check).map(x → new EnforcedAdmin(admin, userId)} except it's
     * returning a zero/one/many-type thing.
     *
     * @param admins set of candidate device admins identified by {@link ComponentName}.
     * @param userId user to create the resultant {@link EnforcedAdmin} as.
     * @param check filter predicate.
     *
     * @return {@code null} if none of the {@param admins} match.
     *         An {@link EnforcedAdmin} if exactly one of the admins matches.
     *         Otherwise, {@link EnforcedAdmin#MULTIPLE_ENFORCED_ADMIN} for multiple matches.
     */
    @Nullable
    private static EnforcedAdmin findEnforcedAdmin(@Nullable List<ComponentName> admins,
            @NonNull DevicePolicyManager dpm, @UserIdInt int userId,
            @NonNull LockSettingCheck check) {
        if (admins == null) {
            return null;
        }

        final UserHandle user = getUserHandleOf(userId);
        EnforcedAdmin enforcedAdmin = null;
        for (ComponentName admin : admins) {
            if (check.isEnforcing(dpm, admin, userId)) {
                if (enforcedAdmin == null) {
                    enforcedAdmin = new EnforcedAdmin(admin, user);
                } else {
                    return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                }
            }
        }
        return enforcedAdmin;
    }

    public static EnforcedAdmin checkIfUninstallBlocked(Context context,
            String packageName, int userId) {
        EnforcedAdmin allAppsControlDisallowedAdmin = checkIfRestrictionEnforced(context,
                UserManager.DISALLOW_APPS_CONTROL, userId);
        if (allAppsControlDisallowedAdmin != null) {
            return allAppsControlDisallowedAdmin;
        }
        EnforcedAdmin allAppsUninstallDisallowedAdmin = checkIfRestrictionEnforced(context,
                UserManager.DISALLOW_UNINSTALL_APPS, userId);
        if (allAppsUninstallDisallowedAdmin != null) {
            return allAppsUninstallDisallowedAdmin;
        }
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            if (ipm.getBlockUninstallForUser(packageName, userId)) {
                return getProfileOrDeviceOwner(context, getUserHandleOf(userId));
            }
        } catch (RemoteException e) {
            // Nothing to do
        }
        return null;
    }

    /**
     * Check if an application is suspended.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if the application is not suspended.
     */
    public static EnforcedAdmin checkIfApplicationIsSuspended(Context context, String packageName,
            int userId) {
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            if (ipm.isPackageSuspendedForUser(packageName, userId)) {
                return getProfileOrDeviceOwner(context, getUserHandleOf(userId));
            }
        } catch (RemoteException | IllegalArgumentException e) {
            // Nothing to do
        }
        return null;
    }

    public static EnforcedAdmin checkIfInputMethodDisallowed(Context context,
            String packageName, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOrDeviceOwner(context, getUserHandleOf(userId));
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isInputMethodPermittedByAdmin(admin.component,
                    packageName, userId);
        }
        int managedProfileId = getManagedProfileId(context, userId);
        EnforcedAdmin profileAdmin = getProfileOrDeviceOwner(context,
                getUserHandleOf(managedProfileId));
        boolean permittedByProfileAdmin = true;
        if (profileAdmin != null) {
            permittedByProfileAdmin = dpm.isInputMethodPermittedByAdmin(profileAdmin.component,
                    packageName, managedProfileId);
        }
        if (!permitted && !permittedByProfileAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByProfileAdmin) {
            return profileAdmin;
        }
        return null;
    }

    /**
     * @param context
     * @param userId user id of a managed profile.
     * @return is remote contacts search disallowed.
     */
    public static EnforcedAdmin checkIfRemoteContactSearchDisallowed(Context context, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOwner(context, userId);
        if (admin == null) {
            return null;
        }
        UserHandle userHandle = UserHandle.of(userId);
        if (dpm.getCrossProfileContactsSearchDisabled(userHandle)
                && dpm.getCrossProfileCallerIdDisabled(userHandle)) {
            return admin;
        }
        return null;
    }

    /**
     * @param userId user id of a managed profile.
     * @return profile owner admin if cross profile calendar is disallowed.
     */
    public static EnforcedAdmin getCrossProfileCalendarEnforcingAdmin(Context context, int userId) {
        final Context managedProfileContext = createPackageContextAsUser(
                context, userId);
        final DevicePolicyManager dpm = managedProfileContext.getSystemService(
                DevicePolicyManager.class);
        if (dpm == null) {
            return null;
        }
        final EnforcedAdmin admin = getProfileOwner(context, userId);
        if (admin == null) {
            return null;
        }
        final Set<String> packages = dpm.getCrossProfileCalendarPackages();
        if (packages != null && packages.isEmpty()) {
            return admin;
        }
        return null;
    }

    /**
     * @param userId user id of a managed profile.
     * @return a context created from the given context for the given user, or null if it fails.
     */
    private static Context createPackageContextAsUser(Context context, int userId) {
        try {
            return context.createPackageContextAsUser(
                    context.getPackageName(), 0 /* flags */, UserHandle.of(userId));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Failed to create user context", e);
        }
        return null;
    }

    public static EnforcedAdmin checkIfAccessibilityServiceDisallowed(Context context,
            String packageName, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOrDeviceOwner(context, getUserHandleOf(userId));
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isAccessibilityServicePermittedByAdmin(admin.component,
                    packageName, userId);
        }
        int managedProfileId = getManagedProfileId(context, userId);
        EnforcedAdmin profileAdmin = getProfileOrDeviceOwner(context,
                getUserHandleOf(managedProfileId));
        boolean permittedByProfileAdmin = true;
        if (profileAdmin != null) {
            permittedByProfileAdmin = dpm.isAccessibilityServicePermittedByAdmin(
                    profileAdmin.component, packageName, managedProfileId);
        }
        if (!permitted && !permittedByProfileAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByProfileAdmin) {
            return profileAdmin;
        }
        return null;
    }

    private static int getManagedProfileId(Context context, int userId) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        List<UserInfo> userProfiles = um.getProfiles(userId);
        for (UserInfo uInfo : userProfiles) {
            if (uInfo.id == userId) {
                continue;
            }
            if (uInfo.isManagedProfile()) {
                return uInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Check if account management for a specific type of account is disabled by admin.
     * Only a profile or device owner can disable account management. So, we check if account
     * management is disabled and return profile or device owner on the calling user.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if the account management is not disabled.
     */
    public static EnforcedAdmin checkIfAccountManagementDisabled(Context context,
            String accountType, int userId) {
        if (accountType == null) {
            return null;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) || dpm == null) {
            return null;
        }
        boolean isAccountTypeDisabled = false;
        String[] disabledTypes = dpm.getAccountTypesWithManagementDisabledAsUser(userId);
        for (String type : disabledTypes) {
            if (accountType.equals(type)) {
                isAccountTypeDisabled = true;
                break;
            }
        }
        if (!isAccountTypeDisabled) {
            return null;
        }
        return getProfileOrDeviceOwner(context, getUserHandleOf(userId));
    }

    /**
     * Check if {@param packageName} is restricted by the profile or device owner from using
     * metered data.
     *
     * @return EnforcedAdmin object containing the enforced admin component and admin user details,
     * or {@code null} if the {@param packageName} is not restricted.
     */
    public static EnforcedAdmin checkIfMeteredDataRestricted(Context context,
            String packageName, int userId) {
        final EnforcedAdmin enforcedAdmin = getProfileOrDeviceOwner(context,
                getUserHandleOf(userId));
        if (enforcedAdmin == null) {
            return null;
        }

        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        return dpm.isMeteredDataDisabledPackageForUser(enforcedAdmin.component, packageName, userId)
                ? enforcedAdmin : null;
    }

    /**
     * Checks if {@link android.app.admin.DevicePolicyManager#setAutoTimeRequired} is enforced
     * on the device.
     *
     * @return EnforcedAdmin Object containing the device owner component and
     * userId the device owner is running as, or {@code null} setAutoTimeRequired is not enforced.
     */
    public static EnforcedAdmin checkIfAutoTimeRequired(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.getAutoTimeRequired()) {
            return null;
        }
        ComponentName adminComponent = dpm.getDeviceOwnerComponentOnCallingUser();
        return new EnforcedAdmin(adminComponent, getUserHandleOf(UserHandle.myUserId()));
    }

    /**
     * Checks if an admin has enforced minimum password quality requirements on the given user.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if no quality requirements are set. If the requirements are set by
     * multiple device admins, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfPasswordQualityIsSet(Context context, int userId) {
        final LockSettingCheck check =
                (DevicePolicyManager dpm, ComponentName admin, @UserIdInt int checkUser) ->
                        dpm.getPasswordQuality(admin, checkUser)
                                > DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }

        LockPatternUtils lockPatternUtils = new LockPatternUtils(context);
        if (sProxy.isSeparateProfileChallengeEnabled(lockPatternUtils, userId)) {
            // userId is managed profile and has a separate challenge, only consider
            // the admins in that user.
            final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userId);
            if (admins == null) {
                return null;
            }
            EnforcedAdmin enforcedAdmin = null;
            final UserHandle user = getUserHandleOf(userId);
            for (ComponentName admin : admins) {
                if (check.isEnforcing(dpm, admin, userId)) {
                    if (enforcedAdmin == null) {
                        enforcedAdmin = new EnforcedAdmin(admin, user);
                    } else {
                        return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                    }
                }
            }
            return enforcedAdmin;
        } else {
            return checkForLockSetting(context, userId, check);
        }
    }

    /**
     * Checks if any admin has set maximum time to lock.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if no admin has set this restriction. If multiple admins has set this, then
     * the admin component will be set to {@code null} and userId to {@link UserHandle#USER_NULL}
     */
    public static EnforcedAdmin checkIfMaximumTimeToLockIsSet(Context context) {
        return checkForLockSetting(context, UserHandle.myUserId(),
                (DevicePolicyManager dpm, ComponentName admin, @UserIdInt int userId) ->
                        dpm.getMaximumTimeToLock(admin, userId) > 0);
    }

    private interface LockSettingCheck {
        boolean isEnforcing(DevicePolicyManager dpm, ComponentName admin, @UserIdInt int userId);
    }

    /**
     * Checks whether any of the user's profiles enforce the lock setting. A managed profile is only
     * included if it does not have a separate challenge.
     *
     * The user identified by {@param userId} is always included.
     */
    private static EnforcedAdmin checkForLockSetting(
            Context context, @UserIdInt int userId, LockSettingCheck check) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        final LockPatternUtils lockPatternUtils = new LockPatternUtils(context);
        EnforcedAdmin enforcedAdmin = null;
        // Return all admins for this user and the profiles that are visible from this
        // user that do not use a separate work challenge.
        for (UserInfo userInfo : UserManager.get(context).getProfiles(userId)) {
            final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userInfo.id);
            if (admins == null) {
                continue;
            }
            final UserHandle user = getUserHandleOf(userInfo.id);
            final boolean isSeparateProfileChallengeEnabled =
                    sProxy.isSeparateProfileChallengeEnabled(lockPatternUtils, userInfo.id);
            for (ComponentName admin : admins) {
                if (!isSeparateProfileChallengeEnabled) {
                    if (check.isEnforcing(dpm, admin, userInfo.id)) {
                        if (enforcedAdmin == null) {
                            enforcedAdmin = new EnforcedAdmin(admin, user);
                        } else {
                            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                        }
                        // This same admins could have set policies both on the managed profile
                        // and on the parent. So, if the admin has set the policy on the
                        // managed profile here, we don't need to further check if that admin
                        // has set policy on the parent admin.
                        continue;
                    }
                }
                if (userInfo.isManagedProfile()) {
                    // If userInfo.id is a managed profile, we also need to look at
                    // the policies set on the parent.
                    DevicePolicyManager parentDpm = sProxy.getParentProfileInstance(dpm, userInfo);
                    if (check.isEnforcing(parentDpm, admin, userInfo.id)) {
                        if (enforcedAdmin == null) {
                            enforcedAdmin = new EnforcedAdmin(admin, user);
                        } else {
                            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                        }
                    }
                }
            }
        }
        return enforcedAdmin;
    }

    public static EnforcedAdmin getDeviceOwner(Context context) {
        return getDeviceOwner(context, null);
    }

    private static EnforcedAdmin getDeviceOwner(Context context, String enforcedRestriction) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getDeviceOwnerComponentOnAnyUser();
        if (adminComponent != null) {
            return new EnforcedAdmin(
                    adminComponent, enforcedRestriction, dpm.getDeviceOwnerUser());
        }
        return null;
    }

    private static EnforcedAdmin getProfileOwner(Context context, int userId) {
        return getProfileOwner(context, null, userId);
    }

    private static EnforcedAdmin getProfileOwner(
            Context context, String enforcedRestriction, int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        }
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getProfileOwnerAsUser(userId);
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, enforcedRestriction, getUserHandleOf(userId));
        }
        return null;
    }

    /**
     * Set the menu item as disabled by admin by adding a restricted padlock at the end of the
     * text and set the click listener which will send an intent to show the admin support details
     * dialog. If the admin is null, remove the padlock and disabled color span. When the admin is
     * null, we also set the OnMenuItemClickListener to null, so if you want to set a custom
     * OnMenuItemClickListener, set it after calling this method.
     */
    public static void setMenuItemAsDisabledByAdmin(final Context context,
            final MenuItem item, final EnforcedAdmin admin) {
        SpannableStringBuilder sb = new SpannableStringBuilder(item.getTitle());
        removeExistingRestrictedSpans(sb);

        if (admin != null) {
            final int disabledColor = context.getColor(R.color.disabled_text_color);
            sb.setSpan(new ForegroundColorSpan(disabledColor), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ImageSpan image = new RestrictedLockImageSpan(context);
            sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    sendShowAdminSupportDetailsIntent(context, admin);
                    return true;
                }
            });
        } else {
            item.setOnMenuItemClickListener(null);
        }
        item.setTitle(sb);
    }

    private static void removeExistingRestrictedSpans(SpannableStringBuilder sb) {
        final int length = sb.length();
        RestrictedLockImageSpan[] imageSpans = sb.getSpans(length - 1, length,
                RestrictedLockImageSpan.class);
        for (ImageSpan span : imageSpans) {
            final int start = sb.getSpanStart(span);
            final int end = sb.getSpanEnd(span);
            sb.removeSpan(span);
            sb.delete(start, end);
        }
        ForegroundColorSpan[] colorSpans = sb.getSpans(0, length, ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colorSpans) {
            sb.removeSpan(span);
        }
    }

    public static boolean isAdminInCurrentUserOrProfile(Context context, ComponentName admin) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        UserManager um = UserManager.get(context);
        for (UserInfo userInfo : um.getProfiles(UserHandle.myUserId())) {
            if (dpm.isAdminActiveAsUser(admin, userInfo.id)) {
                return true;
            }
        }
        return false;
    }

    public static void setTextViewPadlock(Context context,
            TextView textView, boolean showPadlock) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(textView.getText());
        removeExistingRestrictedSpans(sb);
        if (showPadlock) {
            final ImageSpan image = new RestrictedLockImageSpan(context);
            sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(sb);
    }

    /**
     * Takes a {@link android.widget.TextView} and applies an alpha so that the text looks like
     * disabled and appends a padlock to the text. This assumes that there are no
     * ForegroundColorSpans and RestrictedLockImageSpans used on the TextView.
     */
    public static void setTextViewAsDisabledByAdmin(Context context,
            TextView textView, boolean disabled) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(textView.getText());
        removeExistingRestrictedSpans(sb);
        if (disabled) {
            final int disabledColor = context.getColor(R.color.disabled_text_color);
            sb.setSpan(new ForegroundColorSpan(disabledColor), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setCompoundDrawables(null, null, getRestrictedPadlock(context), null);
            textView.setCompoundDrawablePadding(context.getResources().getDimensionPixelSize(
                    R.dimen.restricted_icon_padding));
        } else {
            textView.setCompoundDrawables(null, null, null, null);
        }
        textView.setText(sb);
    }

    /**
     * Static {@link LockPatternUtils} and {@link DevicePolicyManager} wrapper for testing purposes.
     * {@link LockPatternUtils} is an internal API not supported by robolectric.
     * {@link DevicePolicyManager} has a {@code getProfileParent} not yet suppored by robolectric.
     */
    @VisibleForTesting
    static Proxy sProxy = new Proxy();

    @VisibleForTesting
    static class Proxy {
        public boolean isSeparateProfileChallengeEnabled(LockPatternUtils utils, int userHandle) {
            return utils.isSeparateProfileChallengeEnabled(userHandle);
        }

        public DevicePolicyManager getParentProfileInstance(DevicePolicyManager dpm, UserInfo ui) {
            return dpm.getParentProfileInstance(ui);
        }
    }
}
