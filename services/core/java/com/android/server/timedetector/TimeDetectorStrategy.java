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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timedetector.TimeSignal;
import android.util.TimestampedValue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The interface for classes that implement the time detection algorithm used by the
 * TimeDetectorService.
 *
 * @hide
 */
public interface TimeDetectorStrategy {

    interface Callback {
        void setTime(TimestampedValue<Long> time);
    }

    void initialize(@NonNull Callback callback);
    void suggestTime(@NonNull TimeSignal timeSignal);
    void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @Nullable String[] args);

    // Utility methods below are to be moved to a better home when one becomes more obvious.

    /**
     * Adjusts the supplied time value by applying the difference between the reference time
     * supplied and the reference time associated with the time.
     */
    static long getTimeAt(@NonNull TimestampedValue<Long> timeValue, long referenceClockMillisNow) {
        return (referenceClockMillisNow - timeValue.getReferenceTimeMillis())
                + timeValue.getValue();
    }
}
