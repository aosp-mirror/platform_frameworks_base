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
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;

import java.io.PrintWriter;

/**
 * The interface for the class that implements the time detection algorithm used by the
 * {@link TimeZoneDetectorService}.
 *
 * <p>The strategy uses suggestions to decide whether to modify the device's time zone setting
 * and what to set it to.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(PrintWriter, String[])}) may be called on a different thread so implementations must
 * handle thread safety.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy {

    /**
     * Suggests a time zone for the device, determined from the user's manually entered information.
     * Returns {@code false} if the suggestion was invalid, or the device configuration prevented
     * the suggestion being used, {@code true} if the suggestion was accepted. A suggestion that is
     * valid but does not change the time zone because it matches the current device time zone is
     * considered accepted.
     */
    boolean suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion);

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link TelephonyTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to
     * a specific {@link TelephonyTimeZoneSuggestion#getSlotIndex() slotIndex}.
     * See {@link TelephonyTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion.
     */
    void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion suggestion);

    /**
     * Called when there has been a change to the automatic time zone detection setting.
     */
    void handleAutoTimeZoneDetectionChanged();

    /**
     * Dumps internal state such as field values.
     */
    void dump(PrintWriter pw, String[] args);
}
