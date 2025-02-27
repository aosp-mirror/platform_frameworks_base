/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

import java.util.Arrays;

/**
 * This class is intended to be used when reporting {@link AppJankStats} back to the system. It's
 * intended to be used by library widgets to help facilitate the reporting of frame overrun times
 * by adding those times into predefined buckets.
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public class FrameOverrunHistogram {
    private static int[] sBucketEndpoints = new int[]{
            Integer.MIN_VALUE, -200, -150, -100, -90, -80, -70, -60, -50, -40, -30, -25, -20, -18,
            -16, -14, -12, -10, -8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 25, 30, 40,
            50, 60, 70, 80, 90, 100, 150, 200, 300, 400, 500, 600, 700, 800, 900, 1000
    };
    private int[] mBucketCounts;

    /**
     * Create a new instance of FrameOverrunHistogram.
     */
    public FrameOverrunHistogram() {
        mBucketCounts = new int[sBucketEndpoints.length];
    }

    /**
     * Increases the count by one for the bucket representing the frame overrun duration.
     *
     * @param frameOverrunMillis frame overrun duration in millis, frame overrun is the difference
     *                           between a frames deadline and when it was rendered.
     */
    public void addFrameOverrunMillis(int frameOverrunMillis) {
        int countsIndex = getIndexForCountsFromOverrunTime(frameOverrunMillis);
        mBucketCounts[countsIndex]++;
    }

    /**
     * Returns the counts for the all the frame overrun buckets.
     *
     * @return an array of integers representing the counts of frame overrun times. This value
     * cannot be null.
     */
    public @NonNull int[] getBucketCounters() {
        return Arrays.copyOf(mBucketCounts, mBucketCounts.length);
    }

    /**
     * Returns the predefined endpoints for the histogram.
     *
     * @return array of integers representing the endpoints for the predefined histogram count
     * buckets. This value cannot be null.
     */
    public @NonNull int[] getBucketEndpointsMillis() {
        return Arrays.copyOf(sBucketEndpoints, sBucketEndpoints.length);
    }

    // This takes the overrun time and returns what bucket it belongs to in the counters array.
    private int getIndexForCountsFromOverrunTime(int overrunTime) {
        if (overrunTime < 20) {
            if (overrunTime >= -20) {
                return (overrunTime + 20) / 2 + 12;
            }
            if (overrunTime >= -30) {
                return (overrunTime + 30) / 5 + 10;
            }
            if (overrunTime >= -100) {
                return (overrunTime + 100) / 10 + 3;
            }
            if (overrunTime >= -200) {
                return (overrunTime + 200) / 50 + 1;
            }
            return 0;
        }
        if (overrunTime < 30) {
            return (overrunTime - 20) / 5 + 32;
        }
        if (overrunTime < 100) {
            return (overrunTime - 30) / 10 + 34;
        }
        if (overrunTime < 200) {
            return (overrunTime - 50) / 100 + 41;
        }
        if (overrunTime < 1000) {
            return (overrunTime - 200) / 100 + 43;
        }
        return sBucketEndpoints.length - 1;
    }
}
