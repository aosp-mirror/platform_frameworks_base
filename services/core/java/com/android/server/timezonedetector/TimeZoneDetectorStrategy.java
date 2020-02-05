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
import android.app.timezonedetector.PhoneTimeZoneSuggestion;

import java.io.PrintWriter;

/**
 * The interface for the class that implement the time detection algorithm used by the
 * {@link TimeZoneDetectorService}.
 *
 * <p>Most calls will be handled by a single thread but that is not true for all calls. For example
 * {@link #dump(PrintWriter, String[])}) may be called on a different thread so implementations must
 * handle thread safety.
 *
 * @hide
 */
public interface TimeZoneDetectorStrategy {

    /** Process the suggested manually-entered (i.e. user sourced) time zone. */
    void suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion);

    /**
     * Suggests a time zone for the device, or withdraws a previous suggestion if
     * {@link PhoneTimeZoneSuggestion#getZoneId()} is {@code null}. The suggestion is scoped to a
     * specific {@link PhoneTimeZoneSuggestion#getSlotIndex() phone}.
     * See {@link PhoneTimeZoneSuggestion} for an explanation of the metadata associated with a
     * suggestion. The strategy uses suggestions to decide whether to modify the device's time zone
     * setting and what to set it to.
     */
    void suggestPhoneTimeZone(@NonNull PhoneTimeZoneSuggestion suggestion);

    /**
     * Called when there has been a change to the automatic time zone detection setting.
     */
    void handleAutoTimeZoneDetectionChanged();

    /**
     * Dumps internal state such as field values.
     */
    void dump(PrintWriter pw, String[] args);
}
