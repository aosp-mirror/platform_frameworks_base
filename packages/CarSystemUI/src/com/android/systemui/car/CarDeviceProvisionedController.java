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

import com.android.systemui.statusbar.policy.DeviceProvisionedController;

/**
 * This interface defines controller that monitors the status of SUW progress for each user in
 * addition to the functionality defined by {@link DeviceProvisionedController}.
 */
public interface CarDeviceProvisionedController extends DeviceProvisionedController {
    /**
     * Returns {@code true} when SUW is in progress for the given user.
     */
    boolean isUserSetupInProgress(int user);

    /**
     * Returns {@code true} when SUW is in progress for the current user.
     */
    default boolean isCurrentUserSetupInProgress() {
        return isUserSetupInProgress(getCurrentUser());
    }

    /**
     * Returns {@code true} when the user is setup and not currently in SUW.
     */
    default boolean isCurrentUserFullySetup() {
        return isCurrentUserSetup() && !isCurrentUserSetupInProgress();
    }
}
