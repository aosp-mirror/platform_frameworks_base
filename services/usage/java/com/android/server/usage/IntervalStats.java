/**
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.android.server.usage;

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.util.ArrayMap;
import android.util.ArraySet;

class IntervalStats {
    public long beginTime;
    public long endTime;
    public long lastTimeSaved;
    public final ArrayMap<String, UsageStats> stats = new ArrayMap<>();
    public TimeSparseArray<UsageEvents.Event> events;

    // A string cache. This is important as when we're parsing XML files, we don't want to
    // keep hundreds of strings that have the same contents. We will read the string
    // and only keep it if it's not in the cache. The GC will take care of the
    // strings that had identical copies in the cache.
    private final ArraySet<String> mStringCache = new ArraySet<>();

    UsageStats getOrCreateUsageStats(String packageName) {
        UsageStats usageStats = stats.get(packageName);
        if (usageStats == null) {
            usageStats = new UsageStats();
            usageStats.mPackageName = packageName;
            usageStats.mBeginTimeStamp = beginTime;
            usageStats.mEndTimeStamp = endTime;
            stats.put(packageName, usageStats);
        }
        return usageStats;
    }

    void update(String packageName, long timeStamp, int eventType) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);

        // TODO(adamlesinski): Ensure that we recover from incorrect event sequences
        // like double MOVE_TO_BACKGROUND, etc.
        if (eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                eventType == UsageEvents.Event.END_OF_DAY) {
            if (usageStats.mLastEvent == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    usageStats.mLastEvent == UsageEvents.Event.CONTINUE_PREVIOUS_DAY) {
                usageStats.mTotalTimeInForeground += timeStamp - usageStats.mLastTimeUsed;
            }
        }
        usageStats.mLastEvent = eventType;
        usageStats.mLastTimeUsed = timeStamp;
        usageStats.mEndTimeStamp = timeStamp;
        endTime = timeStamp;
    }

    private String getCachedStringRef(String str) {
        final int index = mStringCache.indexOf(str);
        if (index < 0) {
            mStringCache.add(str);
            return str;
        }
        return mStringCache.valueAt(index);
    }

    UsageEvents.Event buildEvent(String packageName, String className) {
        UsageEvents.Event event = new UsageEvents.Event();
        event.mPackage = getCachedStringRef(packageName);
        if (className != null) {
            event.mClass = getCachedStringRef(className);
        }
        return event;
    }
}
