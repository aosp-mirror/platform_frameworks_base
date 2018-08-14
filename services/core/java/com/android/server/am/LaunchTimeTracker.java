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
 * limitations under the License
 */

package com.android.server.am;

import android.app.WaitResult;
import android.os.SystemClock;
import android.os.Trace;
import android.util.SparseArray;

/**
 * Tracks launch time of apps to be reported by {@link WaitResult}. Note that this is slightly
 * different from {@link ActivityMetricsLogger}, but should eventually merged with it.
 */
class LaunchTimeTracker {

    private final SparseArray<Entry> mWindowingModeLaunchTime = new SparseArray<>();

    void setLaunchTime(ActivityRecord r) {
        Entry entry = mWindowingModeLaunchTime.get(r.getWindowingMode());
        if (entry == null){
            entry = new Entry();
            mWindowingModeLaunchTime.append(r.getWindowingMode(), entry);
        }
        entry.setLaunchTime(r);
    }

    void stopFullyDrawnTraceIfNeeded(int windowingMode) {
        final Entry entry = mWindowingModeLaunchTime.get(windowingMode);
        if (entry == null) {
            return;
        }
        entry.stopFullyDrawnTraceIfNeeded();
    }

    Entry getEntry(int windowingMode) {
        return mWindowingModeLaunchTime.get(windowingMode);
    }

    static class Entry {

        long mLaunchStartTime;
        long mFullyDrawnStartTime;

        void setLaunchTime(ActivityRecord r) {
            if (r.displayStartTime == 0) {
                r.fullyDrawnStartTime = r.displayStartTime = SystemClock.uptimeMillis();
                if (mLaunchStartTime == 0) {
                    startLaunchTraces(r.packageName);
                    mLaunchStartTime = mFullyDrawnStartTime = r.displayStartTime;
                }
            } else if (mLaunchStartTime == 0) {
                startLaunchTraces(r.packageName);
                mLaunchStartTime = mFullyDrawnStartTime = SystemClock.uptimeMillis();
            }
        }

        private void startLaunchTraces(String packageName) {
            if (mFullyDrawnStartTime != 0)  {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
            }
            Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "launching: " + packageName, 0);
            Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
        }

        private void stopFullyDrawnTraceIfNeeded() {
            if (mFullyDrawnStartTime != 0 && mLaunchStartTime == 0) {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, "drawing", 0);
                mFullyDrawnStartTime = 0;
            }
        }
    }
}
