/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.devicestate;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;

import android.annotation.NonNull;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Utilities for {@link DeviceStateManager}.
 * @hide
 */
public class DeviceStateUtil {
    private DeviceStateUtil() { }

    /**
     * Returns the state identifier of the {@link DeviceState} that matches the
     * {@code currentState}s physical properties. This will return the identifier of the
     * {@link DeviceState} that matches the devices physical configuration.
     *
     * Returns {@link INVALID_DEVICE_STATE_IDENTIFIER} if there is no {@link DeviceState} in the
     * provided list of {@code supportedStates} that matches.
     * @hide
     */
    public static int calculateBaseStateIdentifier(@NonNull DeviceState currentState,
            @NonNull List<DeviceState> supportedStates) {
        DeviceState.Configuration stateConfiguration = currentState.getConfiguration();
        for (int i = 0; i < supportedStates.size(); i++) {
            DeviceState stateToCompare = supportedStates.get(i);
            if (stateToCompare.getConfiguration().getPhysicalProperties().isEmpty()) {
                continue;
            }
            if (isDeviceStateMatchingPhysicalProperties(stateConfiguration.getPhysicalProperties(),
                    supportedStates.get(i))) {
                return supportedStates.get(i).getIdentifier();
            }
        }
        return INVALID_DEVICE_STATE_IDENTIFIER;
    }

    /**
     * Returns if the physical properties provided, matches the same physical properties on the
     * provided {@link DeviceState}.
     */
    private static boolean isDeviceStateMatchingPhysicalProperties(
            Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties,
            DeviceState state) {
        Iterator<@DeviceState.PhysicalDeviceStateProperties Integer> iterator =
                physicalProperties.iterator();
        while (iterator.hasNext()) {
            if (!state.hasProperty(iterator.next())) {
                return false;
            }
        }
        return true;
    }

}
