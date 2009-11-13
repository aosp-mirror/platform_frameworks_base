/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.os;

import android.util.Log;

import java.util.HashMap;

/**
 * A class to help with measuring latency in your code.
 * 
 * Suggested usage:
 * 1) Instanciate a LatencyTimer as a class field.
 *      private [static] LatencyTimer mLt = new LatencyTimer(100, 1000);
 * 2) At various points in the code call sample with a string and the time delta to some fixed time.
 *    The string should be unique at each point of the code you are measuring.
 *      mLt.sample("before processing event", System.nanoTime() - event.getEventTimeNano());
 *      processEvent(event);
 *      mLt.sample("after processing event ", System.nanoTime() - event.getEventTimeNano());
 *
 * @hide
 */
public final class LatencyTimer
{
    final String TAG = "LatencyTimer";
    final int mSampleSize;
    final int mScaleFactor;
    volatile HashMap<String, long[]> store = new HashMap<String, long[]>();

    /**
    * Creates a LatencyTimer object
    * @param sampleSize number of samples to collect before printing out the average
    * @param scaleFactor divisor used to make each sample smaller to prevent overflow when
    *        (sampleSize * average sample value)/scaleFactor > Long.MAX_VALUE
    */
    public LatencyTimer(int sampleSize, int scaleFactor) {
        if (scaleFactor == 0) {
            scaleFactor = 1;
        }
        mScaleFactor = scaleFactor;
        mSampleSize = sampleSize;
    }

    /**
     * Add a sample delay for averaging.
     * @param tag string used for printing out the result. This should be unique at each point of
     *  this called.
     * @param delta time difference from an unique point of reference for a particular iteration
     */
    public void sample(String tag, long delta) {
        long[] array = getArray(tag);

        // array[mSampleSize] holds the number of used entries
        final int index = (int) array[mSampleSize]++;
        array[index] = delta;
        if (array[mSampleSize] == mSampleSize) {
            long totalDelta = 0;
            for (long d : array) {
                totalDelta += d/mScaleFactor;
            }
            array[mSampleSize] = 0;
            Log.i(TAG, tag + " average = " + totalDelta / mSampleSize);
        }
    }

    private long[] getArray(String tag) {
        long[] data = store.get(tag);
        if (data == null) {
            synchronized(store) {
                data = store.get(tag);
                if (data == null) {
                    data = new long[mSampleSize + 1];
                    store.put(tag, data);
                    data[mSampleSize] = 0;
                }
            }
        }
        return data;
    }
}
