/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.timedetector;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.SystemClock;
import android.os.TimestampedValue;

/**
 * The interface through which system components can send signals to the TimeDetectorService.
 *
 * @hide
 */
@SystemService(Context.TIME_DETECTOR_SERVICE)
public interface TimeDetector {

    /**
     * The name of the service for shell commands.
     * @hide
     */
    String SHELL_COMMAND_SERVICE_NAME = "time_detector";

    /**
     * A shell command that prints the current "auto time detection" global setting value.
     * @hide
     */
    String SHELL_COMMAND_IS_AUTO_DETECTION_ENABLED = "is_auto_detection_enabled";

    /**
     * A shared utility method to create a {@link ManualTimeSuggestion}.
     *
     * @hide
     */
    static ManualTimeSuggestion createManualTimeSuggestion(long when, String why) {
        TimestampedValue<Long> utcTime =
                new TimestampedValue<>(SystemClock.elapsedRealtime(), when);
        ManualTimeSuggestion manualTimeSuggestion = new ManualTimeSuggestion(utcTime);
        manualTimeSuggestion.addDebugInfo(why);
        return manualTimeSuggestion;
    }

    /**
     * Suggests a telephony-signal derived time to the detector. The detector may ignore the signal
     * if better signals are available such as those that come from more reliable sources or were
     * determined more recently.
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE)
    void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion);

    /**
     * Suggests the current time, determined from the user's manually entered information, to
     * the detector. Returns {@code false} if the suggestion was invalid, or the device
     * configuration prevented the suggestion being used, {@code true} if the suggestion was
     * accepted. A suggestion that is valid but does not change the time because it matches the
     * current device time is considered accepted.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE)
    boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSuggestion);

    /**
     * Suggests the time according to a network time source like NTP.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion);

    /**
     * Suggests the time according to a gnss time source.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    void suggestGnssTime(GnssTimeSuggestion timeSuggestion);
}
