/*
 * Copyright 2019 The Android Open Source Project
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
import android.annotation.UserIdInt;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.util.IndentingPrintWriter;

/**
 * The interface for the class that implements the time detection algorithm used by the
 * {@link TimeZoneDetectorService}.
 *
 * <p>The strategy uses suggestions to decide whether to modify the device's time zone setting
 * and what to set it to.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(IndentingPrintWriter, String[])}) may be called on a different thread so
 * implementations mustvhandle thread safety.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy extends Dumpable, Dumpable.Container {

    /** A listener for strategy events. */
    interface StrategyListener {
        /**
         * Invoked when configuration has been changed.
         */
        void onConfigurationChanged();
    }

    /** Sets the listener that enables the strategy to communicate with the surrounding service. */
    void setStrategyListener(@NonNull StrategyListener listener);

    /** Returns the user's time zone capabilities. */
    @NonNull
    TimeZoneCapabilities getCapabilities(@UserIdInt int userId);

    /**
     * Returns the configuration that controls time zone detector behavior.
     */
    @NonNull
    TimeZoneConfiguration getConfiguration(@UserIdInt int userId);

    /**
     * Updates the configuration settings that control time zone detector behavior.
     */
    boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration configuration);

    /**
     * Suggests zero, one or more time zones for the device, or withdraws a previous suggestion if
     * {@link GeolocationTimeZoneSuggestion#getZoneIds()} is {@code null}.
     */
    void suggestGeolocationTimeZone(@NonNull GeolocationTimeZoneSuggestion suggestion);

    /**
     * Suggests a time zone for the device using manually-entered (i.e. user sourced) information.
     */
    boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion suggestion);

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link TelephonyTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to
     * a specific {@link TelephonyTimeZoneSuggestion#getSlotIndex() slotIndex}.
     * See {@link TelephonyTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion.
     */
    void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion suggestion);

    /**
     * Called when there has been a change to the automatic time zone detection configuration.
     */
    void handleAutoTimeZoneConfigChanged();
}
