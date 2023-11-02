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

import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderStatus;

/**
 * Fake implementation of {@link TimeZoneProviderEventPreProcessor} which assumes that all events
 * are valid or always uncertain if {@link #enterUncertainMode()} was called.
 */
public final class FakeTimeZoneProviderEventPreProcessor
        implements TimeZoneProviderEventPreProcessor {

    private boolean mIsUncertain = false;

    @Override
    public TimeZoneProviderEvent preProcess(TimeZoneProviderEvent timeZoneProviderEvent) {
        if (mIsUncertain) {
            TimeZoneProviderStatus timeZoneProviderStatus = null;
            return TimeZoneProviderEvent.createUncertainEvent(
                    timeZoneProviderEvent.getCreationElapsedMillis(), timeZoneProviderStatus);
        }
        return timeZoneProviderEvent;
    }

    /** Enters a mode where {@link #preProcess} will always return "uncertain" events. */
    public void enterUncertainMode() {
        mIsUncertain = true;
    }
}
