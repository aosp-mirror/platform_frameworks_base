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
     * @param listener The listener to add.
     */
    public abstract void addOnCrossProfileWidgetProvidersChangeListener(
            OnCrossProfileWidgetProvidersChangeListener listener);
}
