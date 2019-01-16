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

import static android.app.usage.UsageEvents.Event.ACTIVITY_DESTROYED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED;
import static android.app.usage.UsageEvents.Event.CONFIGURATION_CHANGE;
import static android.app.usage.UsageEvents.Event.CONTINUE_PREVIOUS_DAY;
import static android.app.usage.UsageEvents.Event.CONTINUING_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN;
import static android.app.usage.UsageEvents.Event.END_OF_DAY;
import static android.app.usage.UsageEvents.Event.FLUSH_TO_DISK;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_STOP;
import static android.app.usage.UsageEvents.Event.KEYGUARD_HIDDEN;
import static android.app.usage.UsageEvents.Event.KEYGUARD_SHOWN;
import static android.app.usage.UsageEvents.Event.NOTIFICATION_INTERRUPTION;
import static android.app.usage.UsageEvents.Event.ROLLOVER_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE;
import static android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE;
import static android.app.usage.UsageEvents.Event.SHORTCUT_INVOCATION;
import static android.app.usage.UsageEvents.Event.STANDBY_BUCKET_CHANGED;
import static android.app.usage.UsageEvents.Event.SYSTEM_INTERACTION;

import android.app.usage.ConfigurationStats;
import android.app.usage.EventList;
import android.app.usage.EventStats;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.List;

public class IntervalStats {
    public static final int CURRENT_MAJOR_VERSION = 1;
    public static final int CURRENT_MINOR_VERSION = 1;
    public int majorVersion = CURRENT_MAJOR_VERSION;
    public int minorVersion = CURRENT_MINOR_VERSION;
    public long beginTime;
    public long endTime;
    public long lastTimeSaved;
    public final EventTracker interactiveTracker = new EventTracker();
    public final EventTracker nonInteractiveTracker = new EventTracker();
    public final EventTracker keyguardShownTracker = new EventTracker();
    public final EventTracker keyguardHiddenTracker = new EventTracker();
    public final ArrayMap<String, UsageStats> packageStats = new ArrayMap<>();
    public final ArrayMap<Configuration, ConfigurationStats> configurations = new ArrayMap<>();
    public Configuration activeConfiguration;
    public EventList events = new EventList();

    // A string cache. This is important as when we're parsing XML files, we don't want to
    // keep hundreds of strings that have the same contents. We will read the string
    // and only keep it if it's not in the cache. The GC will take care of the
    // strings that had identical copies in the cache.
    public final ArraySet<String> mStringCache = new ArraySet<>();

    public static final class EventTracker {
        public long curStartTime;
        public long lastEventTime;
        public long duration;
        public int count;

        public void commitTime(long timeStamp) {
            if (curStartTime != 0) {
                duration += timeStamp - duration;
                curStartTime = 0;
            }
        }

        public void update(long timeStamp) {
            if (curStartTime == 0) {
                // If we aren't already running, time to bump the count.
                count++;
            }
            commitTime(timeStamp);
            curStartTime = timeStamp;
            lastEventTime = timeStamp;
        }

        void addToEventStats(List<EventStats> out, int event, long beginTime, long endTime) {
            if (count != 0 || duration != 0) {
                EventStats ev = new EventStats();
                ev.mEventType = event;
                ev.mCount = count;
                ev.mTotalTime = duration;
                ev.mLastEventTime = lastEventTime;
                ev.mBeginTimeStamp = beginTime;
                ev.mEndTimeStamp = endTime;
                out.add(ev);
            }
        }

    }

    public IntervalStats() {
    }

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
    Event buildEvent(String packageName, String className) {
        Event event = new Event();
        event.mPackage = getCachedStringRef(packageName);
        if (className != null) {
            event.mClass = getCachedStringRef(className);
        }
        return event;
    }

    /**
     * Builds a UsageEvents.Event from a proto, but does not add it internally.
     * Built here to take advantage of the cached String Refs
     */
    Event buildEvent(ProtoInputStream parser, List<String> stringPool)
            throws IOException {
        final Event event = new Event();
        while (true) {
            switch (parser.nextField()) {
                case (int) IntervalStatsProto.Event.PACKAGE:
                    event.mPackage = getCachedStringRef(
                            parser.readString(IntervalStatsProto.Event.PACKAGE));
                    break;
                case (int) IntervalStatsProto.Event.PACKAGE_INDEX:
                    event.mPackage = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.PACKAGE_INDEX) - 1));
                    break;
                case (int) IntervalStatsProto.Event.CLASS:
                    event.mClass = getCachedStringRef(
                            parser.readString(IntervalStatsProto.Event.CLASS));
                    break;
                case (int) IntervalStatsProto.Event.CLASS_INDEX:
                    event.mClass = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.CLASS_INDEX) - 1));
                    break;
                case (int) IntervalStatsProto.Event.TIME_MS:
                    event.mTimeStamp = beginTime + parser.readLong(
                            IntervalStatsProto.Event.TIME_MS);
                    break;
                case (int) IntervalStatsProto.Event.FLAGS:
                    event.mFlags = parser.readInt(IntervalStatsProto.Event.FLAGS);
                    break;
                case (int) IntervalStatsProto.Event.TYPE:
                    event.mEventType = parser.readInt(IntervalStatsProto.Event.TYPE);
                    break;
                case (int) IntervalStatsProto.Event.CONFIG:
                    event.mConfiguration = new Configuration();
                    event.mConfiguration.readFromProto(parser, IntervalStatsProto.Event.CONFIG);
                    break;
                case (int) IntervalStatsProto.Event.SHORTCUT_ID:
                    event.mShortcutId = parser.readString(
                            IntervalStatsProto.Event.SHORTCUT_ID).intern();
                    break;
                case (int) IntervalStatsProto.Event.STANDBY_BUCKET:
                    event.mBucketAndReason = parser.readInt(
                            IntervalStatsProto.Event.STANDBY_BUCKET);
                    break;
                case (int) IntervalStatsProto.Event.NOTIFICATION_CHANNEL:
                    event.mNotificationChannelId = parser.readString(
                            IntervalStatsProto.Event.NOTIFICATION_CHANNEL);
                    break;
                case (int) IntervalStatsProto.Event.NOTIFICATION_CHANNEL_INDEX:
                    event.mNotificationChannelId = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.NOTIFICATION_CHANNEL_INDEX)
                                    - 1));
                    break;
                case (int) IntervalStatsProto.Event.INSTANCE_ID:
                    event.mInstanceId = parser.readInt(IntervalStatsProto.Event.INSTANCE_ID);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    // Handle default values for certain events types
                    switch (event.mEventType) {
                        case CONFIGURATION_CHANGE:
                            if (event.mConfiguration == null) {
                                event.mConfiguration = new Configuration();
                            }
                            break;
                        case SHORTCUT_INVOCATION:
                            if (event.mShortcutId == null) {
                                event.mShortcutId = "";
                            }
                            break;
                        case NOTIFICATION_INTERRUPTION:
                            if (event.mNotificationChannelId == null) {
                                event.mNotificationChannelId = "";
                            }
                            break;
                    }
                    if (event.mTimeStamp == 0) {
                        //mTimestamp not set, assume default value 0 plus beginTime
                        event.mTimeStamp = beginTime;
                    }
                    return event;
            }
        }
    }

    private boolean isStatefulEvent(int eventType) {
        switch (eventType) {
            case ACTIVITY_RESUMED:
            case ACTIVITY_PAUSED:
            case ACTIVITY_STOPPED:
            case FOREGROUND_SERVICE_START:
            case FOREGROUND_SERVICE_STOP:
            case END_OF_DAY:
            case ROLLOVER_FOREGROUND_SERVICE:
            case CONTINUE_PREVIOUS_DAY:
            case CONTINUING_FOREGROUND_SERVICE:
            case DEVICE_SHUTDOWN:
                return true;
        }
        return false;
    }

    /**
     * Returns whether the event type is one caused by user visible
     * interaction. Excludes those that are internally generated.
     */
    private boolean isUserVisibleEvent(int eventType) {
        return eventType != SYSTEM_INTERACTION
                && eventType != STANDBY_BUCKET_CHANGED;
    }

    /**
     * Update the IntervalStats by a activity or foreground service event.
     * @param packageName package name of this event. Is null if event targets to all packages.
     * @param className class name of a activity or foreground service, could be null to if this
     *                  is sent to all activities/services in this package.
     * @param timeStamp Epoch timestamp in milliseconds.
     * @param eventType event type as in {@link Event}
     * @param instanceId if className is an activity, the hashCode of ActivityRecord's appToken.
     *                 if className is not an activity, instanceId is not used.
     * @hide
     */
    @VisibleForTesting
    public void update(String packageName, String className, long timeStamp, int eventType,
            int instanceId) {
        if (eventType == DEVICE_SHUTDOWN
                || eventType == FLUSH_TO_DISK) {
            // DEVICE_SHUTDOWN and FLUSH_TO_DISK are sent to all packages.
            final int size = packageStats.size();
            for (int i = 0; i < size; i++) {
                UsageStats usageStats = packageStats.valueAt(i);
                usageStats.update(null, timeStamp, eventType, instanceId);
            }
        } else if (eventType == ACTIVITY_DESTROYED) {
            UsageStats usageStats = packageStats.get(packageName);
            if (usageStats != null) {
                // If previous event is not ACTIVITY_STOPPED, convert ACTIVITY_DESTROYED
                // to ACTIVITY_STOPPED and add to event list.
                // Otherwise do not add anything to event list. (Because we want to save space
                // and we do not want a ACTIVITY_STOPPED followed by
                // ACTIVITY_DESTROYED in event list).
                final int index = usageStats.mActivities.indexOfKey(instanceId);
                if (index >= 0) {
                    final int type = usageStats.mActivities.valueAt(index);
                    if (type != ACTIVITY_STOPPED) {
                        Event event = new Event(ACTIVITY_STOPPED, timeStamp);
                        event.mPackage = packageName;
                        event.mClass = className;
                        event.mInstanceId = instanceId;
                        addEvent(event);
                    }
                }
                usageStats.update(className, timeStamp, ACTIVITY_DESTROYED, instanceId);
            }
        } else {
            UsageStats usageStats = getOrCreateUsageStats(packageName);
            usageStats.update(className, timeStamp, eventType, instanceId);
        }
        endTime = timeStamp;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public void addEvent(Event event) {
        // Cache common use strings
        event.mPackage = getCachedStringRef(event.mPackage);
        if (event.mClass != null) {
            event.mClass = getCachedStringRef(event.mClass);
        }
        if (event.mEventType == NOTIFICATION_INTERRUPTION) {
            event.mNotificationChannelId = getCachedStringRef(event.mNotificationChannelId);
        }
        events.insert(event);
    }

    void updateChooserCounts(String packageName, String category, String action) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        if (usageStats.mChooserCounts == null) {
            usageStats.mChooserCounts = new ArrayMap<>();
        }
        ArrayMap<String, Integer> chooserCounts;
        final int idx = usageStats.mChooserCounts.indexOfKey(action);
        if (idx < 0) {
            chooserCounts = new ArrayMap<>();
            usageStats.mChooserCounts.put(action, chooserCounts);
        } else {
            chooserCounts = usageStats.mChooserCounts.valueAt(idx);
        }
        int currentCount = chooserCounts.getOrDefault(category, 0);
        chooserCounts.put(category, currentCount + 1);
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

    void incrementAppLaunchCount(String packageName) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        usageStats.mAppLaunchCount += 1;
    }

    void commitTime(long timeStamp) {
        interactiveTracker.commitTime(timeStamp);
        nonInteractiveTracker.commitTime(timeStamp);
        keyguardShownTracker.commitTime(timeStamp);
        keyguardHiddenTracker.commitTime(timeStamp);
    }

    void updateScreenInteractive(long timeStamp) {
        interactiveTracker.update(timeStamp);
        nonInteractiveTracker.commitTime(timeStamp);
    }

    void updateScreenNonInteractive(long timeStamp) {
        nonInteractiveTracker.update(timeStamp);
        interactiveTracker.commitTime(timeStamp);
    }

    void updateKeyguardShown(long timeStamp) {
        keyguardShownTracker.update(timeStamp);
        keyguardHiddenTracker.commitTime(timeStamp);
    }

    void updateKeyguardHidden(long timeStamp) {
        keyguardHiddenTracker.update(timeStamp);
        keyguardShownTracker.commitTime(timeStamp);
    }

    void addEventStatsTo(List<EventStats> out) {
        interactiveTracker.addToEventStats(out, SCREEN_INTERACTIVE,
                beginTime, endTime);
        nonInteractiveTracker.addToEventStats(out, SCREEN_NON_INTERACTIVE,
                beginTime, endTime);
        keyguardShownTracker.addToEventStats(out, KEYGUARD_SHOWN,
                beginTime, endTime);
        keyguardHiddenTracker.addToEventStats(out, KEYGUARD_HIDDEN,
                beginTime, endTime);
    }

    private String getCachedStringRef(String str) {
        final int index = mStringCache.indexOf(str);
        if (index < 0) {
            mStringCache.add(str);
            return str;
        }
        return mStringCache.valueAt(index);
    }

    /**
     * When an IntervalStats object is deserialized, if the object's version number
     * is lower than current version number, optionally perform a upgrade.
     */
    void upgradeIfNeeded() {
        // We only uprade on majorVersion change, no need to upgrade on minorVersion change.
        if (!(majorVersion < CURRENT_MAJOR_VERSION)) {
            return;
        }
        /*
          Optional upgrade code here.
        */
        majorVersion = CURRENT_MAJOR_VERSION;
    }
}
