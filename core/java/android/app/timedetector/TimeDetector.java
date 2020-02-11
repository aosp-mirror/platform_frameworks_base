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
     * Suggests the user's manually entered current time to the detector.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE)
    void suggestManualTime(@NonNull ManualTimeSuggestion timeSuggestion);

    /**
     * Suggests the time according to a network time source like NTP.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion);
}
