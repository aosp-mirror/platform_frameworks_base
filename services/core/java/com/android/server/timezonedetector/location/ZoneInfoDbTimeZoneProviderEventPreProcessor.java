/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector.location;

import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;

import static com.android.server.timezonedetector.location.LocationTimeZoneManagerService.infoLog;

import android.annotation.NonNull;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderStatus;

import com.android.i18n.timezone.ZoneInfoDb;

/**
 * {@link TimeZoneProviderEventPreProcessor} implementation which makes validations against
 * {@link ZoneInfoDb}.
 */
public class ZoneInfoDbTimeZoneProviderEventPreProcessor
        implements TimeZoneProviderEventPreProcessor {

    /**
     * Returns uncertain event if {@code event} has at least one unsupported time zone ID.
     */
    @Override
    public TimeZoneProviderEvent preProcess(@NonNull TimeZoneProviderEvent event) {
        if (event.getSuggestion() == null || event.getSuggestion().getTimeZoneIds().isEmpty()) {
            return event;
        }

        // If the provider has made a suggestion with unknown time zone IDs it cannot be used to set
        // the device's time zone. This logic prevents bad time zone IDs entering the time zone
        // detection logic from third party code.
        //
        // An event containing an unknown time zone ID could occur if the provider is using a
        // different TZDB version than the device. Provider developers are expected to take steps to
        // avoid version skew problem, e.g. by ensuring atomic updates with the platform time zone
        // rules, or providing IDs based on the device's TZDB version, so this is not considered a
        // common case.
        //
        // Treating a suggestion containing unknown time zone IDs as "uncertain" in the primary
        // enables immediate failover to a secondary provider, one that might provide valid IDs for
        // the same location, which should provide better behavior than just ignoring the event.
        if (hasInvalidZones(event)) {
            TimeZoneProviderStatus providerStatus = event.getTimeZoneProviderStatus();
            TimeZoneProviderStatus.Builder providerStatusBuilder;
            if (providerStatus != null) {
                providerStatusBuilder = new TimeZoneProviderStatus.Builder(providerStatus);
            } else {
                providerStatusBuilder = new TimeZoneProviderStatus.Builder();
            }
            return TimeZoneProviderEvent.createUncertainEvent(event.getCreationElapsedMillis(),
                    providerStatusBuilder
                            .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_FAILED)
                            .build());
        }

        return event;
    }

    private static boolean hasInvalidZones(TimeZoneProviderEvent event) {
        for (String timeZone : event.getSuggestion().getTimeZoneIds()) {
            if (!ZoneInfoDb.getInstance().hasTimeZone(timeZone)) {
                infoLog("event=" + event + " has unsupported zone(" + timeZone + ")");
                return true;
            }
        }

        return false;
    }

}
