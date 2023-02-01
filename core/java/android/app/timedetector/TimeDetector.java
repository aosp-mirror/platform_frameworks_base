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
import android.app.time.UnixEpochTime;
import android.content.Context;
import android.os.SystemClock;

/**
 * The interface through which system components can query and send signals to the
 * TimeDetectorService.
 *
 * <p>SDK APIs are exposed on {@link android.app.time.TimeManager} to obscure the internal split
 * between time and time zone detection services. Migrate APIs there if they need to be part of an
 * SDK API.
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
     * A shell command that sets the current "auto time detection" global setting value.
     * @hide
     */
    String SHELL_COMMAND_SET_AUTO_DETECTION_ENABLED = "set_auto_detection_enabled";

    /**
     * A shell command that injects a manual time suggestion.
     * @hide
     */
    String SHELL_COMMAND_SUGGEST_MANUAL_TIME = "suggest_manual_time";

    /**
     * A shell command that injects a telephony time suggestion.
     * @hide
     */
    String SHELL_COMMAND_SUGGEST_TELEPHONY_TIME = "suggest_telephony_time";

    /**
     * A shell command that injects a network time suggestion.
     * @hide
     */
    String SHELL_COMMAND_SUGGEST_NETWORK_TIME = "suggest_network_time";

    /**
     * A shell command that prints the current network time information.
     * @hide
     */
    String SHELL_COMMAND_GET_NETWORK_TIME = "get_network_time";

    /**
     * A shell command that clears the detector's network time information.
     * @hide
     */
    String SHELL_COMMAND_CLEAR_NETWORK_TIME = "clear_network_time";

    /**
     * A shell command that injects a GNSS time suggestion.
     * @hide
     */
    String SHELL_COMMAND_SUGGEST_GNSS_TIME = "suggest_gnss_time";

    /**
     * A shell command that injects a external time suggestion.
     * @hide
     */
    String SHELL_COMMAND_SUGGEST_EXTERNAL_TIME = "suggest_external_time";

    /**
     * A shell command that retrieves the current system clock time state.
     * @hide
     */
    String SHELL_COMMAND_GET_TIME_STATE = "get_time_state";

    /**
     * A shell command that sets the current time state for testing.
     * @hide
     */
    String SHELL_COMMAND_SET_TIME_STATE = "set_time_state_for_tests";

    /**
     * A shell command that sets the confidence in the current time state for testing.
     * @hide
     */
    String SHELL_COMMAND_CONFIRM_TIME = "confirm_time";

    /**
     * A shared utility method to create a {@link ManualTimeSuggestion}.
     *
     * @hide
     */
    static ManualTimeSuggestion createManualTimeSuggestion(long when, String why) {
        UnixEpochTime unixEpochTime = new UnixEpochTime(SystemClock.elapsedRealtime(), when);
        ManualTimeSuggestion manualTimeSuggestion = new ManualTimeSuggestion(unixEpochTime);
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
}
