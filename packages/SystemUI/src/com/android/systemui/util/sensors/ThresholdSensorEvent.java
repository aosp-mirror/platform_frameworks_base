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

package com.android.systemui.util.sensors;

import java.util.Locale;

/**
 * Returned when the below/above state of a {@link ThresholdSensor} changes.
 */
public class ThresholdSensorEvent {
    private final boolean mBelow;
    private final long mTimestampNs;

    public ThresholdSensorEvent(boolean below, long timestampNs) {
        mBelow = below;
        mTimestampNs = timestampNs;
    }

    public boolean getBelow() {
        return mBelow;
    }

    public long getTimestampNs() {
        return mTimestampNs;
    }

    public long getTimestampMs() {
        return mTimestampNs / 1000000;
    }

    @Override
    public String toString() {
        return String.format((Locale) null, "{near=%s, timestamp_ns=%d}", mBelow, mTimestampNs);
    }
}
