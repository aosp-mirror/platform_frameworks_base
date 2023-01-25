/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.timedetector.ManualTimeSuggestion;

/**
 * The internal (in-process) system server API for the time detector service.
 *
 * <p>The methods on this class can be called from any thread.
 *
 * <p>Methods marked with "[For device policy manager only]" are for use by the device policy
 * manager to set device state and must not enforce device policy restrictions.
 *
 * @hide
 */
public interface TimeDetectorInternal {

    /**
     * [For device policy manager only] Returns a snapshot of the configuration that controls time
     * detector behavior for the current user.
     */
    @NonNull
    TimeCapabilitiesAndConfig getCapabilitiesAndConfigForDpm();

    /**
     * [For device policy manager only] Updates the configuration properties that control a device's
     * time behavior for the current user.
     *
     * <p>This method returns {@code true} if the configuration was changed, {@code false}
     * otherwise.
     */
    boolean updateConfigurationForDpm(@NonNull TimeConfiguration configuration);

    /**
     * [For device policy manager only] Attempts to set the device to a manually entered time.
     * Returns {@code false} if the suggestion is invalid, or the device configuration prevents the
     * suggestion being used, {@code true} if the suggestion has been accepted. A suggestion that is
     * valid but does not change the time because it matches the current device time is considered
     * accepted.
     */
    boolean setManualTimeForDpm(@NonNull ManualTimeSuggestion suggestion);

    /**
     * Suggests a network time to the time detector. The suggestion may not be used by the time
     * detector to set the device's time depending on device configuration and user settings, but
     * can replace previous network suggestions received.
     */
    void suggestNetworkTime(@NonNull NetworkTimeSuggestion suggestion);

    /**
     * Suggests a GNSS-derived time to the time detector. The suggestion may not be used by the time
     * detector to set the device's time depending on device configuration and user settings, but
     * can replace previous GNSS suggestions received.
     */
    void suggestGnssTime(@NonNull GnssTimeSuggestion suggestion);
}
