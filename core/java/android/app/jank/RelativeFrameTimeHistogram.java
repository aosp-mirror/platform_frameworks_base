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
 * A histogram of frame times relative to their deadline.
 *
 * This class aids in reporting {@link AppJankStats} to the system and is designed for use by
 * library widgets. It facilitates the recording of frame times in relation to the frame deadline.
 * The class records the distribution of time remaining until a frame is considered janky or how
 * janky the frame was.
 * <p>
 * A frame's relative frame time value indicates whether it was delivered early, on time, or late.
 * A negative relative frame time value indicates the frame was delivered early, a value of zero
 * indicates the frame was delivered on time and a positive value indicates the frame was delivered
 * late. The values of the endpoints indicate how early or late a frame was delivered.
 * <p>
 * The relative frame times are recorded as a histogram: values are
 * {@link #addRelativeFrameTimeMillis added} to a bucket by increasing the bucket's counter. The
 * count of frames with a relative frame time between
 * {@link #getBucketEndpointsMillis bucket endpoints} {@code i} and {@code i+1} can be obtained
 * through index {@code i} of {@link #getBucketCounters}.
 *
 */
@FlaggedApi(Flags.FLAG_DETAILED_APP_JANK_METRICS_API)
public class RelativeFrameTimeHistogram {
    private static int[] sBucketEndpoints = new int[]{
            Integer.MIN_VALUE, -200, -150, -100, -90, -80, -70, -60, -50, -40, -30, -25, -20, -18,
            -16, -14, -12, -10, -8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 25, 30, 40,
            50, 60, 70, 80, 90, 100, 150, 200, 300, 400, 500, 600, 700, 800, 900, 1000,
            Integer.MAX_VALUE
    };
    //
    private int[] mBucketCounts;

    /**
     * Create a new instance of RelativeFrameTimeHistogram.
     */
    public RelativeFrameTimeHistogram() {
        mBucketCounts = new int[sBucketEndpoints.length - 1];
    }

    /**
     * Increases the count by one for the bucket representing the relative frame time.
     *
     * @param frameTimeMillis relative frame time in millis, relative frame time is the difference
     *                           between a frames deadline and when it was rendered.
     */
    public void addRelativeFrameTimeMillis(int frameTimeMillis) {
        int countsIndex = getRelativeFrameTimeBucketIndex(frameTimeMillis);
        mBucketCounts[countsIndex]++;
    }

    /**
     * Returns the counts for the all the relative frame time buckets.
     *
     * @return an array of integers representing the counts of relative frame times. This value
     * cannot be null.
     */
    public @NonNull int[] getBucketCounters() {
        return Arrays.copyOf(mBucketCounts, mBucketCounts.length);
    }

    /**
     * Returns the relative frame time endpoints for the histogram.
     * <p>
     * Index {@code i} of {@link #getBucketCounters} contains the count of frames that had a
     * relative frame time between {@code endpoints[i]} (inclusive) and {@code endpoints[i+1]}
     * (exclusive).
     *
     * @return array of integers representing the endpoints for the predefined histogram count
     * buckets. This value cannot be null.
     */
    public @NonNull int[] getBucketEndpointsMillis() {
        return Arrays.copyOf(sBucketEndpoints, sBucketEndpoints.length);
    }

    // This takes the relative frame time and returns what bucket it belongs to in the counters
    // array.
    private int getRelativeFrameTimeBucketIndex(int relativeFrameTime) {
        if (relativeFrameTime < 20) {
            if (relativeFrameTime >= -20) {
                return (relativeFrameTime + 20) / 2 + 12;
            }
            if (relativeFrameTime >= -30) {
                return (relativeFrameTime + 30) / 5 + 10;
            }
            if (relativeFrameTime >= -100) {
                return (relativeFrameTime + 100) / 10 + 3;
            }
            if (relativeFrameTime >= -200) {
                return (relativeFrameTime + 200) / 50 + 1;
            }
            return 0;
        }
        if (relativeFrameTime < 30) {
            return (relativeFrameTime - 20) / 5 + 32;
        }
        if (relativeFrameTime < 100) {
            return (relativeFrameTime - 30) / 10 + 34;
        }
        if (relativeFrameTime < 200) {
            return (relativeFrameTime - 50) / 100 + 41;
        }
        if (relativeFrameTime < 1000) {
            return (relativeFrameTime - 200) / 100 + 43;
        }
        return mBucketCounts.length - 1;
    }
}
