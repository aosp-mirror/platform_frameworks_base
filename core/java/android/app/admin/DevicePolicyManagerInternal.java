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

import android.content.Intent;

import java.util.List;

/**
 * Device policy manager local system service interface.
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
     * Creates an intent to show the admin support dialog to let the user know that the package is
     * suspended by the admin. This assumes that {@param packageName} is suspended by the
     * device/profile owner. The caller should check if the package is suspended or not.
     *
     * <p>This method does not take the DPMS lock.  Safe to be called from anywhere.
     *
     * @param packageName The package that is suspended
     * @param userId The user having the suspended package.
     * @return The intent to trigger the admin support dialog.
     */
    public abstract Intent createPackageSuspendedDialogIntent(String packageName, int userId);
}
