/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;

/**
 * The internal (in-process) system server API for the time zone detector service.
 *
 * <p>The methods on this class can be called from any thread.
 *
 * <p>Methods marked with "[For device policy manager only]" are for use by the device policy
 * manager to set device state and must not enforce device policy restrictions.
 *
 * @hide
 */
public interface TimeZoneDetectorInternal {

    /**
     * [For device policy manager only] Returns a snapshot of the configuration that controls time
     * zone detector behavior for the current user.
     */
    @NonNull
    TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfigForDpm();

    /**
     * [For device policy manager only] Updates the configuration properties that control a device's
     * time zone behavior for the current user.
     *
     * <p>This method returns {@code true} if the configuration was changed,
     * {@code false} otherwise.
     */
    boolean updateConfigurationForDpm(@NonNull TimeZoneConfiguration configuration);

    /**
     * [For device policy manager only] Attempts to set the device to a manually entered time zone.
     * Returns {@code false} if the suggestion is invalid, or the device configuration prevents the
     * suggestion being used, {@code true} if the suggestion has been accepted. A suggestion that is
     * valid but does not change the time zone because it matches the current device time zone is
     * considered accepted.
     */
    boolean setManualTimeZoneForDpm(@NonNull ManualTimeZoneSuggestion suggestion);

    /**
     * Handles the supplied {@link LocationAlgorithmEvent}. The detector may ignore the event based
     * on system settings, whether better information is available, and so on. This method may be
     * implemented asynchronously.
     */
    void handleLocationAlgorithmEvent(@NonNull LocationAlgorithmEvent locationAlgorithmEvent);

    /** Generates a state snapshot for metrics. */
    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState();
}
