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
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.util.IndentingPrintWriter;

/**
 * The interface for the class that is responsible for setting the time zone on a device, used by
 * {@link TimeZoneDetectorService} and {@link TimeZoneDetectorInternal}.
 *
 * <p>The strategy receives suggestions, which it may use to modify the device's time zone setting.
 * Suggestions are acted on or ignored as needed, depending on previously received suggestions and
 * the current user's configuration (see {@link ConfigurationInternal}).
 *
 * <p>Devices can have zero, one or two automatic time zone detection algorithm available at any
 * point in time.
 *
 * <p>The two automatic detection algorithms supported are "telephony" and "geolocation". Algorithm
 * availability and use depends on several factors:
 * <ul>
 * <li>Telephony is only available on devices with a telephony stack.
 * <li>Geolocation is also optional and configured at image creation time. When enabled on a
 * device, its availability depends on the current user's settings, so switching between users can
 * change the automatic algorithm used by the device.</li>
 * </ul>
 *
 * <p>If there are no automatic time zone detections algorithms available then the user can usually
 * change the device time zone manually. Under most circumstances the current user can turn
 * automatic time zone detection on or off, or choose the algorithm via settings.
 *
 * <p>Telephony detection is independent of the current user. The device keeps track of the most
 * recent telephony suggestion from each slotIndex. When telephony detection is in use, the highest
 * scoring suggestion is used to set the device time zone based on a scoring algorithm. If several
 * slotIndexes provide the same score then the slotIndex with the lowest numeric value "wins". If
 * the situation changes and it is no longer possible to be confident about the time zone,
 * slotIndexes must have an empty suggestion submitted in order to "withdraw" their previous
 * suggestion otherwise it will remain in use.
 *
 * <p>Geolocation detection is dependent on the current user and their settings. The device retains
 * at most one geolocation suggestion. Generally, use of a device's location is dependent on the
 * user's "location toggle", but even when that is enabled the user may choose to enable / disable
 * the use of geolocation for device time zone detection. If the current user changes to one that
 * does not have geolocation detection enabled, or the user turns off geolocation detection, then
 * the strategy discards the latest geolocation suggestion. Devices that lose a location fix must
 * have an empty suggestion submitted in order to "withdraw" their previous suggestion otherwise it
 * will remain in use.
 *
 * <p>Threading:
 *
 * <p>Suggestion calls with a void return type may be handed off to a separate thread and handled
 * asynchronously. Synchronous calls like {@link #getCurrentUserConfigurationInternal()},
 * {@link #generateMetricsState()} and debug calls like {@link
 * #dump(IndentingPrintWriter, String[])}, may be called on a different thread concurrently with
 * other operations.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy extends Dumpable, Dumpable.Container {

    /**
     * Adds a listener that will be triggered whenever {@link ConfigurationInternal} may have
     * changed.
     */
    void addConfigChangeListener(@NonNull ConfigurationChangeListener listener);

    /**
     * Returns a snapshot of the configuration that controls time zone detector behavior for the
     * specified user.
     */
    @NonNull
    ConfigurationInternal getConfigurationInternal(@UserIdInt int userId);

    /**
     * Returns a snapshot of the configuration that controls time zone detector behavior for the
     * current user.
     */
    @NonNull
    ConfigurationInternal getCurrentUserConfigurationInternal();

    /**
     * Updates the configuration properties that control a device's time zone behavior.
     *
     * <p>This method returns {@code true} if the configuration was changed,
     * {@code false} otherwise.
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

    /** Generates a state snapshot for metrics. */
    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState();
}
