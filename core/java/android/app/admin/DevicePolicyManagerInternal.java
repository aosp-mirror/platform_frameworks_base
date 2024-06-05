/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager.EnforcingUser;

import java.util.List;
import java.util.Set;

/**
 * Device policy manager local system service interface.
 *
 * Maintenance note: if you need to expose information from DPMS to lower level services such as
 * PM/UM/AM/etc, then exposing it from DevicePolicyManagerInternal is not safe because it may cause
 * lock order inversion. Consider using {@link DevicePolicyCache} instead.
 *
 * @hide Only for use within the system server.
 */
public abstract class DevicePolicyManagerInternal {

    /**
     * Listener for changes in the allowlisted packages to show cross-profile
     * widgets.
     */
    public interface OnCrossProfileWidgetProvidersChangeListener {

        /**
         * Called when the allowlisted packages to show cross-profile widgets
         * have changed for a given user.
         *
         * @param profileId The profile for which the allowlisted packages changed.
         * @param packages The allowlisted packages.
         */
        public void onCrossProfileWidgetProvidersChanged(int profileId, List<String> packages);
    }

    /**
     * Gets the packages whose widget providers are allowlisted to be
     * available in the parent user.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param profileId The profile id.
     * @return The list of packages if such or empty list if there are
     *    no allowlisted packages or the profile id is not a managed
     *    profile.
     */
    public abstract List<String> getCrossProfileWidgetProviders(int profileId);

    /**
     * Adds a listener for changes in the allowlisted packages to show
     * cross-profile app widgets.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param listener The listener to add.
     */
    public abstract void addOnCrossProfileWidgetProvidersChangeListener(
            OnCrossProfileWidgetProvidersChangeListener listener);

    /**
     * @param userHandle the handle of the user whose profile owner is being fetched.
     * @return the configured supervision app if it exists and is the device owner or policy owner.
     */
    public abstract @Nullable ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent(
            @NonNull UserHandle userHandle);

    /**
     * Checks if an app with given uid is an active device owner of its user.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param uid App uid.
     * @return true if the uid is an active device owner.
     */
    public abstract boolean isActiveDeviceOwner(int uid);

    /**
     * Checks if an app with given uid is an active profile owner of its user.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param uid App uid.
     * @return true if the uid is an active profile owner.
     */
    public abstract boolean isActiveProfileOwner(int uid);

    /**
     * Checks if an app with given uid is the active supervision admin.
     *
     * <p>This takes the DPMS lock. DO NOT call from PM/UM/AM with their lock held.
     *
     * @param uid App uid.
     * @return true if the uid is the active supervision app.
     */
    public abstract boolean isActiveSupervisionApp(int uid);

    /**
     * Creates an intent to show the admin support dialog to say that an action is disallowed by
     * the device/profile owner.
     *
     * <p>This method does not take the DPMS lock.  Safe to be called from anywhere.
     * @param userId The user where the action is disallowed.
     * @param useDefaultIfNoAdmin If true, a non-null intent will be returned, even if we couldn't
     * find a profile/device owner.
     * @return The intent to trigger the admin support dialog.
     */
    public abstract Intent createShowAdminSupportIntent(int userId, boolean useDefaultIfNoAdmin);

    /**
     * Creates an intent to show the admin support dialog showing the admin who has set a user
     * restriction.
     *
     * <p>This method does not take the DPMS lock. Safe to be called from anywhere.
     * @param userId The user where the user restriction is set.
     * @return The intent to trigger the admin support dialog, or null if the user restriction is
     * not enforced by the profile/device owner.
     */
    public abstract Intent createUserRestrictionSupportIntent(int userId, String userRestriction);

    /**
     * Returns whether this user/profile is affiliated with the device.
     *
     * <p>
     * By definition, the user that the device owner runs on is always affiliated with the device.
     * Any other user/profile is considered affiliated with the device if the set specified by its
     * profile owner via {@link DevicePolicyManager#setAffiliationIds} intersects with the device
     * owner's.
     * <p>
     * Profile owner on the primary user will never be considered as affiliated as there is no
     * device owner to be affiliated with.
     */
    public abstract boolean isUserAffiliatedWithDevice(int userId);

    /**
     * Returns whether the calling package can install or uninstall packages without user
     * interaction.
     */
    public abstract boolean canSilentlyInstallPackage(String callerPackage, int callerUid);

    /**
     * Reports that a profile has changed to use a unified or separate credential.
     *
     * @param userId User ID of the profile.
     */
    public abstract void reportSeparateProfileChallengeChanged(@UserIdInt int userId);

    /**
     * Return text of error message if printing is disabled.
     * Called by Print Service when printing is disabled by PO or DO when printing is attempted.
     *
     * @param userId The user in question
     * @return localized error message
     */
    public abstract CharSequence getPrintingDisabledReasonForUser(@UserIdInt int userId);

    /**
     * @return cached version of DPM policies that can be accessed without risking deadlocks.
     * Do not call it directly. Use {@link DevicePolicyCache#getInstance()} instead.
     */
    protected abstract DevicePolicyCache getDevicePolicyCache();

    /**
     * @return cached version of device state related to DPM that can be accessed without risking
     * deadlocks.
     * Do not call it directly. Use {@link DevicePolicyCache#getInstance()} instead.
     */
    protected abstract DeviceStateCache getDeviceStateCache();

    /**
     * Returns the combined set of the following:
     * <ul>
     * <li>The package names that the admin has previously set as allowed to request user consent
     * for cross-profile communication, via {@link
     * DevicePolicyManager#setCrossProfilePackages(ComponentName, Set)}.</li>
     * <li>The default package names that are allowed to request user consent for cross-profile
     * communication without being explicitly enabled by the admin, via
     * {@link com.android.internal.R.array#cross_profile_apps} and
     * {@link com.android.internal.R.array#vendor_cross_profile_apps}.</li>
     * </ul>
     *
     * @return the combined set of allowlisted package names set via
     * {@link DevicePolicyManager#setCrossProfilePackages(ComponentName, Set)} and
     * {@link com.android.internal.R.array#cross_profile_apps} and
     * {@link com.android.internal.R.array#vendor_cross_profile_apps}
     *
     * @hide
     */
    public abstract List<String> getAllCrossProfilePackages(int userId);

    /**
     * Returns the default package names set by the OEM that are allowed to communicate
     * cross-profile without being explicitly enabled by the admin, via {@link
     * com.android.internal.R.array#cross_profile_apps} and {@link
     * com.android.internal.R.array#vendor_cross_profile_apps}.
     *
     * @hide
     */
    public abstract List<String> getDefaultCrossProfilePackages();

    /**
     * Sends the {@code intent} to the package holding the
     * {@link android.app.role.RoleManager#ROLE_DEVICE_MANAGER} role and packages with cross
     * profile capabilities, meaning the application must have the {@code crossProfile}
     * property and at least one of the following permissions:
     *
     * <ul>
     *     <li>{@link android.Manifest.permission.INTERACT_ACROSS_PROFILES}
     *     <li>{@link android.Manifest.permission.INTERACT_ACROSS_USERS}
     *     <li>{@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL}
     *     <li>{@link AppOpsManager.OP_INTERACT_ACROSS_PROFILES} appop
     * </ul>
     *
     * <p>Note: The intent itself is not modified but copied before use.
     *`
     * @param intent Template for the intent sent to the packages.
     * @param parentHandle Handle of the user that will receive the intents.
     * @param requiresPermission If false, all packages with the {@code crossProfile} property
     *                           will receive the intent without requiring the additional
     *                           permissions.
     */
    public abstract void broadcastIntentToManifestReceivers(Intent intent,
            UserHandle parentHandle, boolean requiresPermission);

    /**
     * Returns the profile owner component for the given user, or {@code null} if there is not one.
     */
    @Nullable
    public abstract ComponentName getProfileOwnerAsUser(@UserIdInt int userId);

    /**
     * Returns the device owner component for the device, or {@code null} if there is not one.
     *
     * @deprecated added temporarily to support Android Role permission granting.
     * Please contact Android Enterprise Device Policy team before calling this function.
     */
    @Deprecated
    @Nullable
    public abstract ComponentName getDeviceOwnerComponent(boolean callingUserOnly);

    /**
     * Returns the user id of the device owner, or {@link UserHandle#USER_NULL} if there is not one.
     */
    @UserIdInt
    public abstract int getDeviceOwnerUserId();

    /**
     * Returns whether the given package is a device owner or a profile owner in the calling user.
     */
    public abstract boolean isDeviceOrProfileOwnerInCallingUser(String packageName);

    /**
     * Returns whether this class supports being deferred the responsibility for resetting the given
     * op.
     */
    public abstract boolean supportsResetOp(int op);

    /**
     * Resets the given op across the profile group of the given user for the given package. Assumes
     * {@link #supportsResetOp(int)} is true.
     */
    public abstract void resetOp(int op, String packageName, @UserIdInt int userId);

    /**
     * Checks if the calling process has been granted permission to apply a device policy on a
     * specific user.
     *
     * The given permission will be checked along with its associated cross-user permission, if it
     * exists and the target user is different to the calling user.
     *
     * @param callerPackage the package of the calling application.
     * @param permission The name of the permission being checked.
     * @param targetUserId The userId of the user which the caller needs permission to act on.
     * @throws SecurityException If the calling process has not been granted the permission.
     */
    public abstract void enforcePermission(String callerPackage, String permission,
            int targetUserId);

    /**
     * Return whether the calling process has been granted permission to apply a device policy on
     * a specific user.
     *
     * The given permission will be checked along with its associated cross-user
     * permission, if it exists and the target user is different to the calling user.
     *
     * @param callerPackage the package of the calling application.
     * @param permission The name of the permission being checked.
     * @param targetUserId The userId of the user which the caller needs permission to act on.
     */
    public abstract boolean hasPermission(String callerPackage, String permission,
            int targetUserId);

    /**
     * True if either the entire device or the user is organization managed.
     */
    public abstract boolean isUserOrganizationManaged(@UserIdInt int userId);

    /**
     * Returns a map of admin to {@link Bundle} map of restrictions set by the admins for the
     * provided {@code packageName} in the provided {@code userId}
     */
    public abstract List<Bundle> getApplicationRestrictionsPerAdminForUser(
            String packageName, @UserIdInt int userId);

    /**
     *  Returns a list of users who set a user restriction on a given user.
     */
    public abstract List<EnforcingUser> getUserRestrictionSources(String restriction,
                @UserIdInt int userId);

    /**
     * Enforces resolved security logging policy, should only be invoked from device policy engine.
     */
    public abstract void enforceSecurityLoggingPolicy(boolean enabled);

    /**
     * Enforces resolved audit logging policy, should only be invoked from device policy engine.
     */
    public abstract void enforceAuditLoggingPolicy(boolean enabled);
}
