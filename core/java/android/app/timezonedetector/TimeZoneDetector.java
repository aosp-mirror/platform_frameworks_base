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

package android.app.timezonedetector;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;

/**
 * The interface through which system components can send signals to the TimeZoneDetectorService.
 *
 * @hide
 */
@SystemService(Context.TIME_ZONE_DETECTOR_SERVICE)
public interface TimeZoneDetector {

    /**
     * Returns the current user's time zone capabilities. See {@link TimeZoneCapabilities}.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @NonNull
    TimeZoneCapabilities getCapabilities();

    /**
     * Modifies the time zone detection configuration.
     *
     * <p>Configuration settings vary in scope: some may be global (affect all users), others may be
     * specific to the current user.
     *
     * <p>The ability to modify configuration settings can be subject to restrictions. For
     * example, they may be determined by device hardware, general policy (i.e. only the primary
     * user can set them), or by a managed device policy. Use {@link #getCapabilities()} to obtain
     * information at runtime about the user's capabilities.
     *
     * <p>Attempts to modify configuration settings with capabilities that are {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_SUPPORTED} or {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_ALLOWED} will have no effect and a {@code false}
     * will be returned. Modifying configuration settings with capabilities that are {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_APPLICABLE} or {@link
     * TimeZoneCapabilities#CAPABILITY_POSSESSED} will succeed. See {@link
     * TimeZoneCapabilities} for further details.
     *
     * <p>If the supplied configuration only has some values set, then only the specified settings
     * will be updated (where the user's capabilities allow) and other settings will be left
     * unchanged.
     *
     * @return {@code true} if all the configuration settings specified have been set to the
     *   new values, {@code false} if none have
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    boolean updateConfiguration(@NonNull TimeZoneConfiguration configuration);

    /**
     * An interface that can be used to listen for changes to the time zone detector configuration.
     */
    @FunctionalInterface
    interface TimeZoneConfigurationListener {
        /**
         * Called when something about the time zone configuration on the device has changed.
         * This could be because the current user has changed, one of the device's relevant settings
         * has changed, or something that could affect a user's capabilities has changed.
         * There are no guarantees about the thread used.
         */
        void onChange();
    }

    /**
     * Registers a listener that will be informed when something about the time zone configuration
     * changes.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    void addConfigurationListener(@NonNull TimeZoneConfigurationListener listener);

    /**
     * Removes a listener previously passed to
     * {@link #addConfigurationListener(ITimeZoneConfigurationListener)}
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    void removeConfigurationListener(@NonNull TimeZoneConfigurationListener listener);

    /**
     * A shared utility method to create a {@link ManualTimeZoneSuggestion}.
     *
     * @hide
     */
    static ManualTimeZoneSuggestion createManualTimeZoneSuggestion(String tzId, String debugInfo) {
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(tzId);
        suggestion.addDebugInfo(debugInfo);
        return suggestion;
    }

    /**
     * Suggests the current time zone, determined from the user's manually entered information, to
     * the detector. Returns {@code false} if the suggestion was invalid, or the device
     * configuration / user capabilities prevents the suggestion being used (even if it is the same
     * as the current device time zone), {@code true} if the suggestion was accepted. A suggestion
     * that is valid but does not change the time zone because it matches the current device time
     * zone is considered accepted.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE)
    boolean suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion timeZoneSuggestion);

    /**
     * Suggests the current time zone, determined using telephony signals, to the detector. The
     * detector may ignore the signal based on system settings, whether better information is
     * available, and so on.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE)
    void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion);
}
