/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.car;

import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

/**
 * A listener that listens for changes in SUW progress for a user in addition to the
 * functionality defined by {@link DeviceProvisionedListener}.
 */
public interface CarDeviceProvisionedListener extends DeviceProvisionedListener {
    @Override
    default void onUserSwitched() {
        onUserSetupChanged();
        onUserSetupInProgressChanged();
    }
    /**
     * A callback for when a change occurs in SUW progress for a user.
     */
    default void onUserSetupInProgressChanged() {
    }
}
