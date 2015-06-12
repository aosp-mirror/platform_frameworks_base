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

import android.app.usage.ConfigurationStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import android.util.ArraySet;

class IntervalStats {
    public long beginTime;
    public long endTime;
    public long lastTimeSaved;
    public final ArrayMap<String, UsageStats> packageStats = new ArrayMap<>();
    public final ArrayMap<Configuration, ConfigurationStats> configurations = new ArrayMap<>();
    public Configuration activeConfiguration;
    public TimeSparseArray<UsageEvents.Event> events;

    // A string cache. This is important as when we're parsing XML files, we don't want to
    // keep hundreds of strings that have the same contents. We will read the string
    // and only keep it if it's not in the cache. The GC will take care of the
    // strings that had identical copies in the cache.
    private final ArraySet<String> mStringCache = new ArraySet<>();

    /**
     * Gets the UsageStats object for the given package, or creates one and adds it internally.
     */
    UsageStats getOrCreateUsageStats(String packageName) {
        UsageStats usageStats = packageStats.get(packageName);
        if (usageStats == null) {
            usageStats = new UsageStats();
            usageStats.mPackageName = getCachedStringRef(packageName);
            usageStats.mBeginTimeStamp = beginTime;
            usageStats.mEndTimeStamp = endTime;
            usageStats.mBeginIdleTime = 0;
            packageStats.put(usageStats.mPackageName, usageStats);
        }
        return usageStats;
    }

    /**
     * Gets the ConfigurationStats object for the given configuration, or creates one and adds it
     * internally.
     */
    ConfigurationStats getOrCreateConfigurationStats(Configuration config) {
        ConfigurationStats configStats = configurations.get(config);
        if (configStats == null) {
            configStats = new ConfigurationStats();
            configStats.mBeginTimeStamp = beginTime;
            configStats.mEndTimeStamp = endTime;
            configStats.mConfiguration = config;
            configurations.put(config, configStats);
        }
        return configStats;
    }

    /**
     * Builds a UsageEvents.Event, but does not add it internally.
     */
    UsageEvents.Event buildEvent(String packageName, String className) {
        UsageEvents.Event event = new UsageEvents.Event();
        event.mPackage = getCachedStringRef(packageName);
        if (className != null) {
            event.mClass = getCachedStringRef(className);
        }
        return event;
    }

    private boolean isStatefulEvent(int eventType) {
        switch (eventType) {
            case UsageEvents.Event.MOVE_TO_FOREGROUND:
            case UsageEvents.Event.MOVE_TO_BACKGROUND:
            case UsageEvents.Event.END_OF_DAY:
            case UsageEvents.Event.CONTINUE_PREVIOUS_DAY:
                return true;
        }
        return false;
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

        if (isStatefulEvent(eventType)) {
            usageStats.mLastEvent = eventType;
        }

        if (eventType != UsageEvents.Event.SYSTEM_INTERACTION) {
            usageStats.mLastTimeUsed = timeStamp;
        }
        usageStats.mLastTimeSystemUsed = timeStamp;
        usageStats.mEndTimeStamp = timeStamp;

        if (eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            usageStats.mLaunchCount += 1;
        }

        endTime = timeStamp;
    }

    /**
     * Updates the last active time for the package. The timestamp uses a timebase that
     * tracks the device usage time.
     * @param packageName
     * @param timeStamp
     */
    void updateBeginIdleTime(String packageName, long timeStamp) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        usageStats.mBeginIdleTime = timeStamp;
    }

    void updateSystemLastUsedTime(String packageName, long lastUsedTime) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        usageStats.mLastTimeSystemUsed = lastUsedTime;
    }

    void updateConfigurationStats(Configuration config, long timeStamp) {
        if (activeConfiguration != null) {
            ConfigurationStats activeStats = configurations.get(activeConfiguration);
            activeStats.mTotalTimeActive += timeStamp - activeStats.mLastTimeActive;
            activeStats.mLastTimeActive = timeStamp - 1;
        }

        if (config != null) {
            ConfigurationStats configStats = getOrCreateConfigurationStats(config);
            configStats.mLastTimeActive = timeStamp;
            configStats.mActivationCount += 1;
            activeConfiguration = configStats.mConfiguration;
        }

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
}
