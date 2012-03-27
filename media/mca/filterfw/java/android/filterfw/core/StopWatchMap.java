/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.filterfw.core;

import android.os.SystemClock;
import android.util.Log;
import java.util.HashMap;

/**
 * @hide
 */
class StopWatch {

    private int STOP_WATCH_LOGGING_PERIOD = 200;
    private String TAG = "MFF";

    private String mName;
    private long mStartTime;
    private long mTotalTime;
    private int mNumCalls;

    public StopWatch(String name) {
        mName = name;
        mStartTime = -1;
        mTotalTime = 0;
        mNumCalls = 0;
    }

    public void start() {
        if (mStartTime != -1) {
             throw new RuntimeException(
                 "Calling start with StopWatch already running");
        }
        mStartTime = SystemClock.elapsedRealtime();
    }

    public void stop() {
        if (mStartTime == -1) {
             throw new RuntimeException(
                 "Calling stop with StopWatch already stopped");
        }
        long stopTime = SystemClock.elapsedRealtime();
        mTotalTime += stopTime - mStartTime;
        ++mNumCalls;
        mStartTime = -1;
        if (mNumCalls % STOP_WATCH_LOGGING_PERIOD == 0) {
            Log.i(TAG, "AVG ms/call " + mName + ": " +
                  String.format("%.1f", mTotalTime * 1.0f / mNumCalls));
            mTotalTime = 0;
            mNumCalls = 0;
        }
    }

}

public class StopWatchMap {

    public boolean LOG_MFF_RUNNING_TIMES = false;

    private HashMap<String, StopWatch> mStopWatches = null;

    public StopWatchMap() {
        mStopWatches = new HashMap<String, StopWatch>();
    }

    public void start(String stopWatchName) {
        if (!LOG_MFF_RUNNING_TIMES) {
            return;
        }
        if (!mStopWatches.containsKey(stopWatchName)) {
            mStopWatches.put(stopWatchName, new StopWatch(stopWatchName));
        }
        mStopWatches.get(stopWatchName).start();
    }

    public void stop(String stopWatchName) {
        if (!LOG_MFF_RUNNING_TIMES) {
            return;
        }
        if (!mStopWatches.containsKey(stopWatchName)) {
            throw new RuntimeException(
                "Calling stop with unknown stopWatchName: " + stopWatchName);
        }
        mStopWatches.get(stopWatchName).stop();
    }

}
