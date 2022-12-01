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
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneState;
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
 * <p>The two automatic detection algorithms supported are "telephony" and "location". Algorithm
 * availability and use depends on several factors:
 * <ul>
 * <li>Telephony is only available on devices with a telephony stack.
 * <li>Location is also optional and configured at image creation time. When enabled on a device,
 * its availability depends on the current user's settings, so switching between users can change
 * the automatic detection algorithm used by the device.</li>
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
 * <p>Location-based detection is dependent on the current user and their settings. The device
 * retains at most one geolocation suggestion. Generally, use of a device's location is dependent on
 * the user's "location toggle", but even when that is enabled the user may choose to enable /
 * disable the use of location for device time zone detection. If the current user changes to one
 * that does not have location-based detection enabled, or the user turns off the location-based
 * detection, then the strategy will be sent an event that clears the latest suggestion. Devices
 * that lose their location fix must have an empty suggestion submitted in order to "withdraw" their
 * previous suggestion otherwise it will remain in use.
 *
 * <p>The strategy uses only one algorithm at a time and does not attempt consensus even when
 * more than one is available on a device. This "use only one" behavior is deliberate as different
 * algorithms have edge cases and blind spots that lead to incorrect answers or uncertainty;
 * different algorithms aren't guaranteed to agree, and algorithms may frequently lose certainty as
 * users enter areas without the necessary signals. Ultimately, with no perfect algorithm available,
 * the user is left to choose which algorithm works best for their circumstances.
 *
 * <p>When the location detection algorithm is supported and enabled, in certain circumstances, such
 * as during international travel, it makes sense to prioritize speed of detection via telephony
 * (when available) Vs waiting for the location-based detection algorithm to reach certainty.
 * Location-based detection can sometimes be slow to get a location fix and can require network
 * connectivity (which cannot be assumed when users are travelling) for server-assisted location
 * detection or time zone lookup. Therefore, as a restricted form of prioritization between location
 * and telephony algorithms, the strategy provides "telephony fallback mode" behavior, which can be
 * set to "supported" via device config. Fallback mode is entered at runtime in response to signals
 * from outside of the strategy, e.g. from a call to {@link
 * #enableTelephonyTimeZoneFallback(String)}, or from information in the latest {@link
 * LocationAlgorithmEvent}. For telephony fallback mode to actually use a telephony suggestion, the
 * location algorithm <em>must</em> report it is uncertain. Telephony fallback allows the use of
 * telephony suggestions to help with faster detection but only until the location algorithm
 * provides a concrete, "certain" suggestion. After the location algorithm has made a certain
 * suggestion, telephony fallback mode is disabled.
 *
 * <p>Threading:
 *
 * <p>Implementations of this class must be thread-safe as calls calls like {@link
 * #generateMetricsState()} and {@link #dump(IndentingPrintWriter, String[])} may be called on
 * different threads concurrently with other operations.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy extends Dumpable {

    /**
     * Adds a listener that will be triggered when something changes that could affect the result
     * of the {@link #getCapabilitiesAndConfig} call for the <em>current user only</em>. This
     * includes the current user changing. This is exposed so that (indirect) users like SettingsUI
     * can monitor for changes to data derived from {@link TimeZoneCapabilitiesAndConfig} and update
     * the UI accordingly.
     */
    void addChangeListener(StateChangeListener listener);

    /**
     * Returns a {@link TimeZoneCapabilitiesAndConfig} object for the specified user.
     *
     * <p>The strategy is dependent on device state like current user, settings and device config.
     * These updates are usually handled asynchronously, so callers should expect some delay between
     * a change being made directly to services like settings and the strategy becoming aware of
     * them. Changes made via {@link #updateConfiguration} will be visible immediately.
     *
     * @param userId the user ID to retrieve the information for
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig(
            @UserIdInt int userId, boolean bypassUserPolicyChecks);

    /**
     * Updates the configuration properties that control a device's time zone behavior.
     *
     * <p>This method returns {@code true} if the configuration was changed, {@code false}
     * otherwise.
     *
     * <p>See {@link #getCapabilitiesAndConfig} for guarantees about visibility of updates to
     * subsequent calls.
     *
     * @param userId the current user ID, supplied to make sure that the asynchronous process
     *   that happens when users switch is completed when the call is made
     * @param configuration the configuration changes
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    boolean updateConfiguration(@UserIdInt int userId, TimeZoneConfiguration configuration,
            boolean bypassUserPolicyChecks);

    /** Returns a snapshot of the system time zone state. See {@link TimeZoneState} for details. */
    @NonNull
    TimeZoneState getTimeZoneState();

    /**
     * Sets the system time zone state. See {@link TimeZoneState} for details. Intended for use
     * during testing to force the device's state, this bypasses the time zone detection logic.
     */
    void setTimeZoneState(@NonNull TimeZoneState timeZoneState);

    /**
     * Signals that a user has confirmed the time zone. If the {@code timeZoneId} is the same as
     * the current time zone then this can be used to raise the system's confidence in that time
     * zone. Returns {@code true} if confirmation was successful (i.e. the ID matched),
     * {@code false} otherwise.
     */
    boolean confirmTimeZone(@NonNull String timeZoneId);

    /**
     * Handles an event from the location-based time zone detection algorithm.
     */
    void handleLocationAlgorithmEvent(@NonNull LocationAlgorithmEvent event);

    /**
     * Suggests a time zone for the device using manually-entered (i.e. user sourced) information.
     *
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion suggestion,
            boolean bypassUserPolicyChecks);

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link TelephonyTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to
     * a specific {@link TelephonyTimeZoneSuggestion#getSlotIndex() slotIndex}.
     * See {@link TelephonyTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion.
     */
    void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion suggestion);

    /**
     * Tells the strategy that it can fall back to telephony detection while the location detection
     * algorithm remains uncertain. {@link #handleLocationAlgorithmEvent(LocationAlgorithmEvent)}
     * can disable it again. See {@link TimeZoneDetectorStrategy} for details.
     */
    void enableTelephonyTimeZoneFallback(@NonNull String reason);

    /** Generates a state snapshot for metrics. */
    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState();

    /** Returns {@code true} if the device supports telephony time zone detection. */
    boolean isTelephonyTimeZoneDetectionSupported();

    /** Returns {@code true} if the device supports location-based time zone detection. */
    boolean isGeoTimeZoneDetectionSupported();
}
