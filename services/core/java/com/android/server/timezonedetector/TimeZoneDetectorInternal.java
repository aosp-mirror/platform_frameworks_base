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

/**
 * The internal (in-process) system server API for the {@link
 * com.android.server.timezonedetector.TimeZoneDetectorService}.
 *
 * <p>The methods on this class can be called from any thread.
 * @hide
 */
public interface TimeZoneDetectorInternal extends Dumpable.Container {

    /** Adds a listener that will be invoked when {@link ConfigurationInternal} may have changed. */
    void addConfigurationListener(@NonNull ConfigurationChangeListener listener);

    /**
     * Removes a listener previously added via {@link
     * #addConfigurationListener(ConfigurationChangeListener)}.
     */
    void removeConfigurationListener(@NonNull ConfigurationChangeListener listener);

    /**
     * Returns a snapshot of the {@link ConfigurationInternal} for the current user. This is only a
     * snapshot so callers must use {@link #addConfigurationListener(ConfigurationChangeListener)}
     * to be notified when it changes.
     */
    @NonNull
    ConfigurationInternal getCurrentUserConfigurationInternal();

    /**
     * Suggests the current time zone, determined using geolocation, to the detector. The
     * detector may ignore the signal based on system settings, whether better information is
     * available, and so on. This method may be implemented asynchronously.
     */
    void suggestGeolocationTimeZone(@NonNull GeolocationTimeZoneSuggestion timeZoneSuggestion);

    /** Generates a state snapshot for metrics. */
    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState();
}
