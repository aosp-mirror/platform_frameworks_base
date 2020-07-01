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
     * Returns the current user's complete time zone configuration. See {@link
     * TimeZoneConfiguration}.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @NonNull
    TimeZoneConfiguration getConfiguration();

    /**
     * Modifies the time zone detection configuration.
     *
     * <p>Configuration properties vary in scope: some may be device-wide, others may be specific to
     * the current user.
     *
     * <p>The ability to modify configuration properties can be subject to restrictions. For
     * example, they may be determined by device hardware, general policy (i.e. only the primary
     * user can set them), or by a managed device policy. See {@link #getCapabilities()} to obtain
     * information at runtime about the user's capabilities.
     *
     * <p>Attempts to set configuration with capabilities that are {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_SUPPORTED} or {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_ALLOWED} will have no effect and a {@code false}
     * will be returned. Setting configuration with capabilities that are {@link
     * TimeZoneCapabilities#CAPABILITY_NOT_APPLICABLE} or {@link
     * TimeZoneCapabilities#CAPABILITY_POSSESSED} will succeed. See {@link
     * TimeZoneCapabilities} for further details.
     *
     * <p>If the configuration is not "complete", then only the specified properties will be
     * updated (where the user's capabilities allow) and other settings will be left unchanged. See
     * {@link TimeZoneConfiguration#isComplete()}.
     *
     * @return {@code true} if all the configuration properties specified have been set to the
     *   new values, {@code false} if none have
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    boolean updateConfiguration(@NonNull TimeZoneConfiguration configuration);

    /**
     * Registers a listener that will be informed when the configuration changes. The complete
     * configuration is passed to the listener, not just the properties that have changed.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    void addConfigurationListener(@NonNull ITimeZoneConfigurationListener listener);

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
