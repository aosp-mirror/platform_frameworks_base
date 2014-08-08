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
import android.content.ComponentName;
import android.util.ArrayMap;

class IntervalStats {
    public long beginTime;
    public long endTime;
    public long lastTimeSaved;
    public final ArrayMap<String, UsageStats> stats = new ArrayMap<>();
    public TimeSparseArray<UsageEvents.Event> events;

    // Maps flattened string representations of component names to ComponentName.
    // This helps save memory from using many duplicate ComponentNames and
    // parse time when reading XML.
    private final ArrayMap<String, ComponentName> mComponentNames = new ArrayMap<>();

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

    /**
     * Return a ComponentName for the given string representation. This will use a cached
     * copy of the ComponentName if possible, otherwise it will parse and add it to the
     * internal cache.
     */
    ComponentName getCachedComponentName(String str) {
        ComponentName name = mComponentNames.get(str);
        if (name == null) {
            name = ComponentName.unflattenFromString(str);
            if (name != null) {
                mComponentNames.put(str, name);
            }
        }
        return name;
    }
}
