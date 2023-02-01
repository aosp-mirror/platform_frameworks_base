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
import android.util.IndentingPrintWriter;

/**
 * The interface for the class that is responsible for setting the time zone on a device, used by
 * {@link TimeZoneDetectorService} and {@link TimeZoneDetectorInternal}.
 *
 * <p>The strategy receives suggestions, which it may use to modify the device's time zone setting.
 * Suggestions are acted on or ignored as needed, depending on previously received suggestions and
 * the current user's configuration (see {@link ConfigurationInternal}).
 *
 * <p>Devices can have zero, one or two automatic time zone detection algorithms available at any
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
 * <p>The strategy uses only one algorithm at a time and does not attempt consensus even when
 * more than one is available on a device. This "use only one" behavior is deliberate as different
 * algorithms have edge cases and blind spots that lead to incorrect answers or uncertainty;
 * different algorithms aren't guaranteed to agree, and algorithms may frequently lose certainty as
 * users enter areas without the necessary signals. Ultimately, with no perfect algorithm available,
 * the user is left to choose which algorithm works best for their circumstances.
 *
 * <p>When geolocation detection is supported and enabled, in certain circumstances, such as during
 * international travel, it makes sense to prioritize speed of detection via telephony (when
 * available) Vs waiting for the geolocation algorithm to reach certainty. Geolocation detection can
 * sometimes be slow to get a location fix and can require network connectivity (which cannot be
 * assumed when users are travelling) for server-assisted location detection or time zone lookup.
 * Therefore, as a restricted form of prioritization between geolocation and telephony algorithms,
 * the strategy provides "telephony fallback" behavior, which can be set to "supported" via device
 * config. Fallback mode is toggled on at runtime via {@link #enableTelephonyTimeZoneFallback()} in
 * response to signals outside of the scope of this class. Telephony fallback allows the use of
 * telephony suggestions to help with faster detection but only until geolocation detection
 * provides a concrete, "certain" suggestion. After geolocation has made the first certain
 * suggestion, telephony fallback is disabled until the next call to {@link
 * #enableTelephonyTimeZoneFallback()}.
 *
 * <p>Threading:
 *
 * <p>Implementations of this class must be thread-safe as calls calls like {@link
 * #generateMetricsState()} and {@link #dump(IndentingPrintWriter, String[])} may be called on
 * differents thread concurrently with other operations.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy extends Dumpable {

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
     * Tells the strategy that it can fall back to telephony detection while geolocation detection
     * remains uncertain. {@link #suggestGeolocationTimeZone(GeolocationTimeZoneSuggestion)} can
     * disable it again. See {@link TimeZoneDetectorStrategy} for details.
     */
    void enableTelephonyTimeZoneFallback();

    /** Generates a state snapshot for metrics. */
    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState();

    /** Returns {@code true} if the device supports telephony time zone detection. */
    boolean isTelephonyTimeZoneDetectionSupported();

    /** Returns {@code true} if the device supports geolocation time zone detection. */
    boolean isGeoTimeZoneDetectionSupported();
}
