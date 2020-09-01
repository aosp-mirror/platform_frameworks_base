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

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.os.UserHandle;

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
     * Listener for changes in the white-listed packages to show cross-profile
     * widgets.
     */
    public interface OnCrossProfileWidgetProvidersChangeListener {

        /**
         * Called when the white-listed packages to show cross-profile widgets
         * have changed for a given user.
         *
         * @param profileId The profile for which the white-listed packages changed.
         * @param packages The white-listed packages.
         */
        public void onCrossProfileWidgetProvidersChanged(int profileId, List<String> packages);
    }

    /**
     * Gets the packages whose widget providers are white-listed to be
     * available in the parent user.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param profileId The profile id.
     * @return The list of packages if such or empty list if there are
     *    no white-listed packages or the profile id is not a managed
     *    profile.
     */
    public abstract List<String> getCrossProfileWidgetProviders(int profileId);

    /**
     * Adds a listener for changes in the white-listed packages to show
     * cross-profile app widgets.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param listener The listener to add.
     */
    public abstract void addOnCrossProfileWidgetProvidersChangeListener(
            OnCrossProfileWidgetProvidersChangeListener listener);

    /**
     * Checks if an app with given uid is an active device admin of its user and has the policy
     * specified.
     *
     * <p>This takes the DPMS lock.  DO NOT call from PM/UM/AM with their lock held.
     *
     * @param uid App uid.
     * @param reqPolicy Required policy, for policies see {@link DevicePolicyManager}.
     * @return true if the uid is an active admin with the given policy.
     */
    public abstract boolean isActiveAdminWithPolicy(int uid, int reqPolicy);

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
     * @return the combined set of whitelisted package names set via
     * {@link DevicePolicyManager#setCrossProfilePackages(ComponentName, Set)} and
     * {@link com.android.internal.R.array#cross_profile_apps} and
     * {@link com.android.internal.R.array#vendor_cross_profile_apps}
     *
     * @hide
     */
    public abstract List<String> getAllCrossProfilePackages();

    /**
     * Returns the default package names set by the OEM that are allowed to request user consent for
     * cross-profile communication without being explicitly enabled by the admin, via
     * {@link com.android.internal.R.array#cross_profile_apps} and
     * {@link com.android.internal.R.array#vendor_cross_profile_apps}.
     *
     * @hide
     */
    public abstract List<String> getDefaultCrossProfilePackages();

    /**
     * Sends the {@code intent} to the packages with cross profile capabilities.
     *
     * <p>This means the application must have the {@code crossProfile} property and the
     * corresponding permissions, defined by
     * {@link
     * android.content.pm.CrossProfileAppsInternal#verifyPackageHasInteractAcrossProfilePermission}.
     *
     * <p>Note: This method doesn't modify {@code intent} but copies it before use.
     *
     * @param intent Template for the intent sent to the package.
     * @param parentHandle Handle of the user that will receive the intents.
     * @param requiresPermission If false, all packages with the {@code crossProfile} property
     *                           will receive the intent.
     */
    public abstract void broadcastIntentToCrossProfileManifestReceiversAsUser(Intent intent,
            UserHandle parentHandle, boolean requiresPermission);

    /**
     * Returns the profile owner component for the given user, or {@code null} if there is not one.
     */
    public abstract ComponentName getProfileOwnerAsUser(int userHandle);
}
