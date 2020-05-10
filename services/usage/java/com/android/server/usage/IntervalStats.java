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
import static android.app.usage.UsageEvents.Event.LOCUS_ID_SET;
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
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoInputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.List;

public class IntervalStats {
    private static final String TAG = "IntervalStats";

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
    /** @hide */
    public final SparseArray<UsageStats> packageStatsObfuscated = new SparseArray<>();
    public final ArrayMap<Configuration, ConfigurationStats> configurations = new ArrayMap<>();
    public Configuration activeConfiguration;
    public final EventList events = new EventList();

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
                duration += timeStamp - curStartTime;
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
                case (int) IntervalStatsProto.Event.TASK_ROOT_PACKAGE_INDEX:
                    event.mTaskRootPackage = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.TASK_ROOT_PACKAGE_INDEX) - 1));
                    break;
                case (int) IntervalStatsProto.Event.TASK_ROOT_CLASS_INDEX:
                    event.mTaskRootClass = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.TASK_ROOT_CLASS_INDEX) - 1));
                    break;
                case (int) IntervalStatsProto.Event.LOCUS_ID_INDEX:
                    event.mLocusId = getCachedStringRef(stringPool.get(
                            parser.readInt(IntervalStatsProto.Event.LOCUS_ID_INDEX) - 1));
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
                        case LOCUS_ID_SET:
                            if (event.mLocusId == null) {
                                event.mLocusId = "";
                            }
                            break;
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
        } else {
            UsageStats usageStats = getOrCreateUsageStats(packageName);
            usageStats.update(className, timeStamp, eventType, instanceId);
        }
        if (timeStamp > endTime) {
            endTime = timeStamp;
        }
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
        if (event.mTaskRootPackage != null) {
            event.mTaskRootPackage = getCachedStringRef(event.mTaskRootPackage);
        }
        if (event.mTaskRootClass != null) {
            event.mTaskRootClass = getCachedStringRef(event.mTaskRootClass);
        }
        if (event.mEventType == NOTIFICATION_INTERRUPTION) {
            event.mNotificationChannelId = getCachedStringRef(event.mNotificationChannelId);
        }
        events.insert(event);
        if (event.mTimeStamp > endTime) {
            endTime = event.mTimeStamp;
        }
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
        if (timeStamp > endTime) {
            endTime = timeStamp;
        }
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

    /**
     * Parses all of the tokens to strings in the obfuscated usage stats data. This includes
     * deobfuscating each of the package tokens and chooser actions and categories.
     *
     * @return {@code true} if any stats were omitted while deobfuscating, {@code false} otherwise.
     */
    private boolean deobfuscateUsageStats(PackagesTokenData packagesTokenData) {
        boolean dataOmitted = false;
        final int usageStatsSize = packageStatsObfuscated.size();
        for (int statsIndex = 0; statsIndex < usageStatsSize; statsIndex++) {
            final int packageToken = packageStatsObfuscated.keyAt(statsIndex);
            final UsageStats usageStats = packageStatsObfuscated.valueAt(statsIndex);
            usageStats.mPackageName = packagesTokenData.getPackageString(packageToken);
            if (usageStats.mPackageName == null) {
                Slog.e(TAG, "Unable to parse usage stats package " + packageToken);
                dataOmitted = true;
                continue;
            }

            // Update chooser counts
            final int chooserActionsSize = usageStats.mChooserCountsObfuscated.size();
            for (int actionIndex = 0; actionIndex < chooserActionsSize; actionIndex++) {
                final ArrayMap<String, Integer> categoryCountsMap = new ArrayMap<>();
                final int actionToken = usageStats.mChooserCountsObfuscated.keyAt(actionIndex);
                final String action = packagesTokenData.getString(packageToken, actionToken);
                if (action == null) {
                    Slog.i(TAG, "Unable to parse chooser action " + actionToken
                            + " for package " + packageToken);
                    continue;
                }
                final SparseIntArray categoryCounts =
                        usageStats.mChooserCountsObfuscated.valueAt(actionIndex);
                final int categoriesSize = categoryCounts.size();
                for (int categoryIndex = 0; categoryIndex < categoriesSize; categoryIndex++) {
                    final int categoryToken = categoryCounts.keyAt(categoryIndex);
                    final String category = packagesTokenData.getString(packageToken,
                            categoryToken);
                    if (category == null) {
                        Slog.i(TAG, "Unable to parse chooser category " + categoryToken
                                + " for package " + packageToken);
                        continue;
                    }
                    categoryCountsMap.put(category, categoryCounts.valueAt(categoryIndex));
                }
                usageStats.mChooserCounts.put(action, categoryCountsMap);
            }
            packageStats.put(usageStats.mPackageName, usageStats);
        }
        return dataOmitted;
    }

    /**
     * Parses all of the tokens to strings in the obfuscated events data. This includes
     * deobfuscating the package token, along with any class, task root package/class tokens, and
     * shortcut or notification channel tokens.
     *
     * @return {@code true} if any events were omitted while deobfuscating, {@code false} otherwise.
     */
    private boolean deobfuscateEvents(PackagesTokenData packagesTokenData) {
        boolean dataOmitted = false;
        for (int i = this.events.size() - 1; i >= 0; i--) {
            final Event event = this.events.get(i);
            final int packageToken = event.mPackageToken;
            event.mPackage = packagesTokenData.getPackageString(packageToken);
            if (event.mPackage == null) {
                Slog.e(TAG, "Unable to parse event package " + packageToken);
                this.events.remove(i);
                dataOmitted = true;
                continue;
            }

            if (event.mClassToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                event.mClass = packagesTokenData.getString(packageToken, event.mClassToken);
                if (event.mClass == null) {
                    Slog.i(TAG, "Unable to parse class " + event.mClassToken
                            + " for package " + packageToken);
                }
            }
            if (event.mTaskRootPackageToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                event.mTaskRootPackage = packagesTokenData.getString(packageToken,
                        event.mTaskRootPackageToken);
                if (event.mTaskRootPackage == null) {
                    Slog.i(TAG, "Unable to parse task root package " + event.mTaskRootPackageToken
                            + " for package " + packageToken);
                }
            }
            if (event.mTaskRootClassToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                event.mTaskRootClass = packagesTokenData.getString(packageToken,
                        event.mTaskRootClassToken);
                if (event.mTaskRootClass == null) {
                    Slog.i(TAG, "Unable to parse task root class " + event.mTaskRootClassToken
                            + " for package " + packageToken);
                }
            }
            switch (event.mEventType) {
                case CONFIGURATION_CHANGE:
                    if (event.mConfiguration == null) {
                        event.mConfiguration = new Configuration();
                    }
                    break;
                case SHORTCUT_INVOCATION:
                    event.mShortcutId = packagesTokenData.getString(packageToken,
                            event.mShortcutIdToken);
                    if (event.mShortcutId == null) {
                        Slog.e(TAG, "Unable to parse shortcut " + event.mShortcutIdToken
                                + " for package " + packageToken);
                        this.events.remove(i);
                        dataOmitted = true;
                        continue;
                    }
                    break;
                case NOTIFICATION_INTERRUPTION:
                    event.mNotificationChannelId = packagesTokenData.getString(packageToken,
                            event.mNotificationChannelIdToken);
                    if (event.mNotificationChannelId == null) {
                        Slog.e(TAG, "Unable to parse notification channel "
                                + event.mNotificationChannelIdToken + " for package "
                                + packageToken);
                        this.events.remove(i);
                        dataOmitted = true;
                        continue;
                    }
                    break;
                case LOCUS_ID_SET:
                    event.mLocusId = packagesTokenData.getString(packageToken, event.mLocusIdToken);
                    if (event.mLocusId == null) {
                        Slog.e(TAG, "Unable to parse locus " + event.mLocusIdToken
                                + " for package " + packageToken);
                        this.events.remove(i);
                        dataOmitted = true;
                        continue;
                    }
                    break;
            }
        }
        return dataOmitted;
    }

    /**
     * Parses the obfuscated tokenized data held in this interval stats object.
     *
     * @return {@code true} if any data was omitted while deobfuscating, {@code false} otherwise.
     * @hide
     */
    public boolean deobfuscateData(PackagesTokenData packagesTokenData) {
        final boolean statsOmitted = deobfuscateUsageStats(packagesTokenData);
        final boolean eventsOmitted = deobfuscateEvents(packagesTokenData);
        return statsOmitted || eventsOmitted;
    }

    /**
     * Obfuscates certain strings within each package stats such as the package name, and the
     * chooser actions and categories.
     */
    private void obfuscateUsageStatsData(PackagesTokenData packagesTokenData) {
        final int usageStatsSize = packageStats.size();
        for (int statsIndex = 0; statsIndex < usageStatsSize; statsIndex++) {
            final String packageName = packageStats.keyAt(statsIndex);
            final UsageStats usageStats = packageStats.valueAt(statsIndex);
            if (usageStats == null) {
                continue;
            }

            final int packageToken = packagesTokenData.getPackageTokenOrAdd(
                    packageName, usageStats.mEndTimeStamp);
            // don't obfuscate stats whose packages have been removed
            if (packageToken == PackagesTokenData.UNASSIGNED_TOKEN) {
                continue;
            }
            usageStats.mPackageToken = packageToken;
            // Update chooser counts.
            final int chooserActionsSize = usageStats.mChooserCounts.size();
            for (int actionIndex = 0; actionIndex < chooserActionsSize; actionIndex++) {
                final String action = usageStats.mChooserCounts.keyAt(actionIndex);
                final ArrayMap<String, Integer> categoriesMap =
                        usageStats.mChooserCounts.valueAt(actionIndex);
                if (categoriesMap == null) {
                    continue;
                }

                final SparseIntArray categoryCounts = new SparseIntArray();
                final int categoriesSize = categoriesMap.size();
                for (int categoryIndex = 0; categoryIndex < categoriesSize; categoryIndex++) {
                    String category = categoriesMap.keyAt(categoryIndex);
                    int categoryToken = packagesTokenData.getTokenOrAdd(packageToken, packageName,
                            category);
                    categoryCounts.put(categoryToken, categoriesMap.valueAt(categoryIndex));
                }
                int actionToken = packagesTokenData.getTokenOrAdd(packageToken, packageName,
                        action);
                usageStats.mChooserCountsObfuscated.put(actionToken, categoryCounts);
            }
            packageStatsObfuscated.put(packageToken, usageStats);
        }
    }

    /**
     * Obfuscates certain strings within an event such as the package name, the class name,
     * task root package and class names, and shortcut and notification channel ids.
     */
    private void obfuscateEventsData(PackagesTokenData packagesTokenData) {
        for (int i = events.size() - 1; i >= 0; i--) {
            final Event event = events.get(i);
            if (event == null) {
                continue;
            }

            final int packageToken = packagesTokenData.getPackageTokenOrAdd(
                    event.mPackage, event.mTimeStamp);
            // don't obfuscate events from packages that have been removed
            if (packageToken == PackagesTokenData.UNASSIGNED_TOKEN) {
                events.remove(i);
                continue;
            }
            event.mPackageToken = packageToken;
            if (!TextUtils.isEmpty(event.mClass)) {
                event.mClassToken = packagesTokenData.getTokenOrAdd(packageToken,
                        event.mPackage, event.mClass);
            }
            if (!TextUtils.isEmpty(event.mTaskRootPackage)) {
                event.mTaskRootPackageToken = packagesTokenData.getTokenOrAdd(packageToken,
                        event.mPackage, event.mTaskRootPackage);
            }
            if (!TextUtils.isEmpty(event.mTaskRootClass)) {
                event.mTaskRootClassToken = packagesTokenData.getTokenOrAdd(packageToken,
                        event.mPackage, event.mTaskRootClass);
            }
            switch (event.mEventType) {
                case SHORTCUT_INVOCATION:
                    if (!TextUtils.isEmpty(event.mShortcutId)) {
                        event.mShortcutIdToken = packagesTokenData.getTokenOrAdd(packageToken,
                                event.mPackage, event.mShortcutId);
                    }
                    break;
                case NOTIFICATION_INTERRUPTION:
                    if (!TextUtils.isEmpty(event.mNotificationChannelId)) {
                        event.mNotificationChannelIdToken = packagesTokenData.getTokenOrAdd(
                                packageToken, event.mPackage, event.mNotificationChannelId);
                    }
                    break;
                case LOCUS_ID_SET:
                    if (!TextUtils.isEmpty(event.mLocusId)) {
                        event.mLocusIdToken = packagesTokenData.getTokenOrAdd(packageToken,
                                event.mPackage, event.mLocusId);
                    }
                    break;
            }
        }
    }

    /**
     * Obfuscates the data in this instance of interval stats.
     *
     * @hide
     */
    public void obfuscateData(PackagesTokenData packagesTokenData) {
        obfuscateUsageStatsData(packagesTokenData);
        obfuscateEventsData(packagesTokenData);
    }
}
