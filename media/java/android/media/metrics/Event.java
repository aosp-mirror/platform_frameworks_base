/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.metrics;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Bundle;

/**
 * Abstract class for metrics events.
 */
public abstract class Event {
    final long mTimeSinceCreatedMillis;
    Bundle mMetricsBundle = new Bundle();

    // hide default constructor
    /* package */ Event() {
        mTimeSinceCreatedMillis = MediaMetricsManager.INVALID_TIMESTAMP;
    }

    /* package */ Event(long timeSinceCreatedMillis, Bundle extras) {
        mTimeSinceCreatedMillis = timeSinceCreatedMillis;
        mMetricsBundle = extras;
    }

    /**
     * Gets time since the corresponding log session is created in millisecond.
     * @return the timestamp since the instance is created, or -1 if unknown.
     * @see LogSessionId
     * @see PlaybackSession
     * @see RecordingSession
     */
    @IntRange(from = -1)
    public long getTimeSinceCreatedMillis() {
        return mTimeSinceCreatedMillis;
    }

    /**
     * Gets metrics-related information that is not supported by dedicated methods.
     * <p>It is intended to be used for backwards compatibility by the metrics infrastructure.
     */
    @NonNull
    public Bundle getMetricsBundle() {
        return mMetricsBundle;
    }
}
