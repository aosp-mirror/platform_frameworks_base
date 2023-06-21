/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.provider.Settings;

import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

/**
 * Controller to cache in process the state of the device provisioning.
 * <p>
 * This controller keeps track of the values of device provisioning, user setup complete, and
 * whether Factory Reset Protection is active.
 */
public interface DeviceProvisionedController extends CallbackController<DeviceProvisionedListener> {

    /**
     * @return whether the device is provisioned
     * @see Settings.Global#DEVICE_PROVISIONED
     */
    boolean isDeviceProvisioned();

    /**
     * @deprecated use {@link com.android.systemui.settings.UserTracker}
     */
    @Deprecated
    int getCurrentUser();

    /**
     * @param user the user to query
     * @return whether that user has completed the user setup
     * @see Settings.Secure#USER_SETUP_COMPLETE
     */
    boolean isUserSetup(int user);

    /**
     * @see DeviceProvisionedController#isUserSetup
     */
    boolean isCurrentUserSetup();

    /** Returns true when Factory Reset Protection is locking the device. */
    boolean isFrpActive();

    /**
     * Interface to provide calls when the values tracked change
     */
    interface DeviceProvisionedListener {
        /**
         * Call when the device changes from not provisioned to provisioned
         */
        default void onDeviceProvisionedChanged() { }

        /**
         * Call on user switched
         */
        default void onUserSwitched() {
            onUserSetupChanged();
        }

        /**
         * Call when some user changes from not provisioned to provisioned
         */
        default void onUserSetupChanged() { }

        /**
         * Called when the state of FRP changes.
         */
        default void onFrpActiveChanged() {}
    }
}
