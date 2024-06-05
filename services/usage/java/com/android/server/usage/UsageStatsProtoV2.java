/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event.UserInteractionEventExtrasToken;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.os.PersistableBundle;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * UsageStats reader/writer V2 for Protocol Buffer format.
 */
final class UsageStatsProtoV2 {
    private static final String TAG = "UsageStatsProtoV2";

    private static final long ONE_HOUR_MS = TimeUnit.HOURS.toMillis(1);

    // Static-only utility class.
    private UsageStatsProtoV2() {}

    private static UsageStats parseUsageStats(ProtoInputStream proto, final long beginTime)
            throws IOException {
        UsageStats stats = new UsageStats();
        // Time attributes stored is an offset of the beginTime.
        while (true) {
            switch (proto.nextField()) {
                case (int) UsageStatsObfuscatedProto.PACKAGE_TOKEN:
                    stats.mPackageToken = proto.readInt(
                            UsageStatsObfuscatedProto.PACKAGE_TOKEN) - 1;
                    break;
                case (int) UsageStatsObfuscatedProto.LAST_TIME_ACTIVE_MS:
                    stats.mLastTimeUsed = beginTime + proto.readLong(
                            UsageStatsObfuscatedProto.LAST_TIME_ACTIVE_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.TOTAL_TIME_ACTIVE_MS:
                    stats.mTotalTimeInForeground = proto.readLong(
                            UsageStatsObfuscatedProto.TOTAL_TIME_ACTIVE_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.APP_LAUNCH_COUNT:
                    stats.mAppLaunchCount = proto.readInt(
                            UsageStatsObfuscatedProto.APP_LAUNCH_COUNT);
                    break;
                case (int) UsageStatsObfuscatedProto.CHOOSER_ACTIONS:
                    try {
                        final long token = proto.start(UsageStatsObfuscatedProto.CHOOSER_ACTIONS);
                        loadChooserCounts(proto, stats);
                        proto.end(token);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read chooser counts for " + stats.mPackageToken);
                    }
                    break;
                case (int) UsageStatsObfuscatedProto.LAST_TIME_SERVICE_USED_MS:
                    stats.mLastTimeForegroundServiceUsed = beginTime + proto.readLong(
                            UsageStatsObfuscatedProto.LAST_TIME_SERVICE_USED_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.TOTAL_TIME_SERVICE_USED_MS:
                    stats.mTotalTimeForegroundServiceUsed = proto.readLong(
                            UsageStatsObfuscatedProto.TOTAL_TIME_SERVICE_USED_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.LAST_TIME_VISIBLE_MS:
                    stats.mLastTimeVisible = beginTime + proto.readLong(
                            UsageStatsObfuscatedProto.LAST_TIME_VISIBLE_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.TOTAL_TIME_VISIBLE_MS:
                    stats.mTotalTimeVisible = proto.readLong(
                            UsageStatsObfuscatedProto.TOTAL_TIME_VISIBLE_MS);
                    break;
                case (int) UsageStatsObfuscatedProto.LAST_TIME_COMPONENT_USED_MS:
                    stats.mLastTimeComponentUsed = beginTime + proto.readLong(
                            UsageStatsObfuscatedProto.LAST_TIME_COMPONENT_USED_MS);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return stats;
            }
        }
    }

    private static void loadCountAndTime(ProtoInputStream proto, long fieldId,
            IntervalStats.EventTracker tracker) {
        try {
            final long token = proto.start(fieldId);
            while (true) {
                switch (proto.nextField()) {
                    case (int) IntervalStatsObfuscatedProto.CountAndTime.COUNT:
                        tracker.count = proto.readInt(
                                IntervalStatsObfuscatedProto.CountAndTime.COUNT);
                        break;
                    case (int) IntervalStatsObfuscatedProto.CountAndTime.TIME_MS:
                        tracker.duration = proto.readLong(
                                IntervalStatsObfuscatedProto.CountAndTime.TIME_MS);
                        break;
                    case ProtoInputStream.NO_MORE_FIELDS:
                        proto.end(token);
                        return;
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to read event tracker " + fieldId, e);
        }
    }

    private static void loadChooserCounts(ProtoInputStream proto, UsageStats usageStats)
            throws IOException {
        int actionToken;
        SparseIntArray counts;
        if (proto.nextField(UsageStatsObfuscatedProto.ChooserAction.ACTION_TOKEN)) {
            // Fast path; this should work for most cases since the action token is written first
            actionToken = proto.readInt(UsageStatsObfuscatedProto.ChooserAction.ACTION_TOKEN) - 1;
            counts = usageStats.mChooserCountsObfuscated.get(actionToken);
            if (counts == null) {
                counts = new SparseIntArray();
                usageStats.mChooserCountsObfuscated.put(actionToken, counts);
            }
        } else {
            counts = new SparseIntArray();
        }

        while (true) {
            switch (proto.nextField()) {
                case (int) UsageStatsObfuscatedProto.ChooserAction.ACTION_TOKEN:
                    // Fast path failed for some reason, add the SparseIntArray object to usageStats
                    actionToken = proto.readInt(
                            UsageStatsObfuscatedProto.ChooserAction.ACTION_TOKEN) - 1;
                    usageStats.mChooserCountsObfuscated.put(actionToken, counts);
                    break;
                case (int) UsageStatsObfuscatedProto.ChooserAction.COUNTS:
                    final long token = proto.start(UsageStatsObfuscatedProto.ChooserAction.COUNTS);
                    loadCountsForAction(proto, counts);
                    proto.end(token);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return; // if the action was never read, the loaded counts will be ignored.
            }
        }
    }

    private static void loadCountsForAction(ProtoInputStream proto, SparseIntArray counts)
            throws IOException {
        int categoryToken = PackagesTokenData.UNASSIGNED_TOKEN;
        int count = 0;
        while (true) {
            switch (proto.nextField()) {
                case (int) UsageStatsObfuscatedProto.ChooserAction.CategoryCount.CATEGORY_TOKEN:
                    categoryToken = proto.readInt(
                            UsageStatsObfuscatedProto.ChooserAction.CategoryCount.CATEGORY_TOKEN)
                            - 1;
                    break;
                case (int) UsageStatsObfuscatedProto.ChooserAction.CategoryCount.COUNT:
                    count = proto.readInt(
                            UsageStatsObfuscatedProto.ChooserAction.CategoryCount.COUNT);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (categoryToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                        counts.put(categoryToken, count);
                    }
                    return;
            }
        }
    }

    private static void loadConfigStats(ProtoInputStream proto, IntervalStats stats)
            throws IOException {
        boolean configActive = false;
        final Configuration config = new Configuration();
        ConfigurationStats configStats = new ConfigurationStats();
        if (proto.nextField(IntervalStatsObfuscatedProto.Configuration.CONFIG)) {
            // Fast path; this should work since the configuration is written first
            config.readFromProto(proto, IntervalStatsObfuscatedProto.Configuration.CONFIG);
            configStats = stats.getOrCreateConfigurationStats(config);
        }

        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsObfuscatedProto.Configuration.CONFIG:
                    // Fast path failed from some reason, add ConfigStats object to statsOut now
                    config.readFromProto(proto, IntervalStatsObfuscatedProto.Configuration.CONFIG);
                    final ConfigurationStats temp = stats.getOrCreateConfigurationStats(config);
                    temp.mLastTimeActive = configStats.mLastTimeActive;
                    temp.mTotalTimeActive = configStats.mTotalTimeActive;
                    temp.mActivationCount = configStats.mActivationCount;
                    configStats = temp;
                    break;
                case (int) IntervalStatsObfuscatedProto.Configuration.LAST_TIME_ACTIVE_MS:
                    configStats.mLastTimeActive = stats.beginTime + proto.readLong(
                            IntervalStatsObfuscatedProto.Configuration.LAST_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsObfuscatedProto.Configuration.TOTAL_TIME_ACTIVE_MS:
                    configStats.mTotalTimeActive = proto.readLong(
                            IntervalStatsObfuscatedProto.Configuration.TOTAL_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsObfuscatedProto.Configuration.COUNT:
                    configStats.mActivationCount = proto.readInt(
                            IntervalStatsObfuscatedProto.Configuration.COUNT);
                    break;
                case (int) IntervalStatsObfuscatedProto.Configuration.ACTIVE:
                    configActive = proto.readBoolean(
                            IntervalStatsObfuscatedProto.Configuration.ACTIVE);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (configActive) {
                        stats.activeConfiguration = configStats.mConfiguration;
                    }
                    return;
            }
        }
    }

    private static UsageEvents.Event parseEvent(ProtoInputStream proto, long beginTime)
            throws IOException {
        final UsageEvents.Event event = new UsageEvents.Event();
        while (true) {
            switch (proto.nextField()) {
                case (int) EventObfuscatedProto.PACKAGE_TOKEN:
                    event.mPackageToken = proto.readInt(EventObfuscatedProto.PACKAGE_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.CLASS_TOKEN:
                    event.mClassToken = proto.readInt(EventObfuscatedProto.CLASS_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.TIME_MS:
                    event.mTimeStamp = beginTime + proto.readLong(EventObfuscatedProto.TIME_MS);
                    break;
                case (int) EventObfuscatedProto.FLAGS:
                    event.mFlags = proto.readInt(EventObfuscatedProto.FLAGS);
                    break;
                case (int) EventObfuscatedProto.TYPE:
                    event.mEventType = proto.readInt(EventObfuscatedProto.TYPE);
                    break;
                case (int) EventObfuscatedProto.CONFIG:
                    event.mConfiguration = new Configuration();
                    event.mConfiguration.readFromProto(proto, EventObfuscatedProto.CONFIG);
                    break;
                case (int) EventObfuscatedProto.SHORTCUT_ID_TOKEN:
                    event.mShortcutIdToken = proto.readInt(
                            EventObfuscatedProto.SHORTCUT_ID_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.STANDBY_BUCKET:
                    event.mBucketAndReason = proto.readInt(EventObfuscatedProto.STANDBY_BUCKET);
                    break;
                case (int) EventObfuscatedProto.NOTIFICATION_CHANNEL_ID_TOKEN:
                    event.mNotificationChannelIdToken = proto.readInt(
                            EventObfuscatedProto.NOTIFICATION_CHANNEL_ID_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.INSTANCE_ID:
                    event.mInstanceId = proto.readInt(EventObfuscatedProto.INSTANCE_ID);
                    break;
                case (int) EventObfuscatedProto.TASK_ROOT_PACKAGE_TOKEN:
                    event.mTaskRootPackageToken = proto.readInt(
                            EventObfuscatedProto.TASK_ROOT_PACKAGE_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.TASK_ROOT_CLASS_TOKEN:
                    event.mTaskRootClassToken = proto.readInt(
                            EventObfuscatedProto.TASK_ROOT_CLASS_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.LOCUS_ID_TOKEN:
                    event.mLocusIdToken = proto.readInt(
                            EventObfuscatedProto.LOCUS_ID_TOKEN) - 1;
                    break;
                case (int) EventObfuscatedProto.INTERACTION_EXTRAS:
                    try {
                        final long interactionExtrasToken = proto.start(
                                EventObfuscatedProto.INTERACTION_EXTRAS);
                        event.mUserInteractionExtrasToken = parseUserInteractionEventExtras(proto);
                        proto.end(interactionExtrasToken);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some user interaction extras from proto.", e);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return event.mPackageToken == PackagesTokenData.UNASSIGNED_TOKEN ? null : event;
            }
        }
    }

    static void writeOffsetTimestamp(ProtoOutputStream proto, long fieldId,
            long timestamp, long beginTime) {
        // timestamps will only be written if they're after the begin time
        // a grace period of one hour before the begin time is allowed because of rollover logic
        final long rolloverGracePeriod = beginTime - ONE_HOUR_MS;
        if (timestamp > rolloverGracePeriod) {
            // time attributes are stored as an offset of the begin time (given offset)
            proto.write(fieldId, getOffsetTimestamp(timestamp, beginTime));
        }
    }

    static long getOffsetTimestamp(long timestamp, long offset) {
        final long offsetTimestamp = timestamp - offset;
        // add one ms to timestamp if 0 to ensure it's written to proto (default values are ignored)
        return offsetTimestamp == 0 ? offsetTimestamp + 1 : offsetTimestamp;
    }

    private static void writeUsageStats(ProtoOutputStream proto, final long beginTime,
            final UsageStats stats) throws IllegalArgumentException {
        proto.write(UsageStatsObfuscatedProto.PACKAGE_TOKEN, stats.mPackageToken + 1);
        writeOffsetTimestamp(proto, UsageStatsObfuscatedProto.LAST_TIME_ACTIVE_MS,
                stats.mLastTimeUsed, beginTime);
        proto.write(UsageStatsObfuscatedProto.TOTAL_TIME_ACTIVE_MS, stats.mTotalTimeInForeground);
        writeOffsetTimestamp(proto, UsageStatsObfuscatedProto.LAST_TIME_SERVICE_USED_MS,
                stats.mLastTimeForegroundServiceUsed, beginTime);
        proto.write(UsageStatsObfuscatedProto.TOTAL_TIME_SERVICE_USED_MS,
                stats.mTotalTimeForegroundServiceUsed);
        writeOffsetTimestamp(proto, UsageStatsObfuscatedProto.LAST_TIME_VISIBLE_MS,
                stats.mLastTimeVisible, beginTime);
        proto.write(UsageStatsObfuscatedProto.TOTAL_TIME_VISIBLE_MS, stats.mTotalTimeVisible);
        writeOffsetTimestamp(proto, UsageStatsObfuscatedProto.LAST_TIME_COMPONENT_USED_MS,
                stats.mLastTimeComponentUsed, beginTime);
        proto.write(UsageStatsObfuscatedProto.APP_LAUNCH_COUNT, stats.mAppLaunchCount);
        try {
            writeChooserCounts(proto, stats);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write chooser counts for " + stats.mPackageName, e);
        }
    }

    private static void writeCountAndTime(ProtoOutputStream proto, long fieldId, int count,
            long time) throws IllegalArgumentException {
        final long token = proto.start(fieldId);
        proto.write(IntervalStatsObfuscatedProto.CountAndTime.COUNT, count);
        proto.write(IntervalStatsObfuscatedProto.CountAndTime.TIME_MS, time);
        proto.end(token);
    }

    private static void writeChooserCounts(ProtoOutputStream proto, final UsageStats stats)
            throws IllegalArgumentException {
        if (stats == null || stats.mChooserCountsObfuscated.size() == 0) {
            return;
        }
        final int chooserCountSize = stats.mChooserCountsObfuscated.size();
        for (int i = 0; i < chooserCountSize; i++) {
            final int action = stats.mChooserCountsObfuscated.keyAt(i);
            final SparseIntArray counts = stats.mChooserCountsObfuscated.valueAt(i);
            if (counts == null || counts.size() == 0) {
                continue;
            }
            final long token = proto.start(UsageStatsObfuscatedProto.CHOOSER_ACTIONS);
            proto.write(UsageStatsObfuscatedProto.ChooserAction.ACTION_TOKEN, action + 1);
            writeCountsForAction(proto, counts);
            proto.end(token);
        }
    }

    private static void writeCountsForAction(ProtoOutputStream proto, SparseIntArray counts)
            throws IllegalArgumentException {
        final int countsSize = counts.size();
        for (int i = 0; i < countsSize; i++) {
            final int category = counts.keyAt(i);
            final int count = counts.valueAt(i);
            if (count <= 0) {
                continue;
            }
            final long token = proto.start(UsageStatsObfuscatedProto.ChooserAction.COUNTS);
            proto.write(UsageStatsObfuscatedProto.ChooserAction.CategoryCount.CATEGORY_TOKEN,
                    category + 1);
            proto.write(UsageStatsObfuscatedProto.ChooserAction.CategoryCount.COUNT, count);
            proto.end(token);
        }
    }

    private static void writeConfigStats(ProtoOutputStream proto, final long statsBeginTime,
            final ConfigurationStats configStats, boolean isActive)
            throws IllegalArgumentException {
        configStats.mConfiguration.dumpDebug(proto,
                IntervalStatsObfuscatedProto.Configuration.CONFIG);
        writeOffsetTimestamp(proto, IntervalStatsObfuscatedProto.Configuration.LAST_TIME_ACTIVE_MS,
                configStats.mLastTimeActive, statsBeginTime);
        proto.write(IntervalStatsObfuscatedProto.Configuration.TOTAL_TIME_ACTIVE_MS,
                configStats.mTotalTimeActive);
        proto.write(IntervalStatsObfuscatedProto.Configuration.COUNT, configStats.mActivationCount);
        proto.write(IntervalStatsObfuscatedProto.Configuration.ACTIVE, isActive);
    }

    private static void writeEvent(ProtoOutputStream proto, final long statsBeginTime,
            final UsageEvents.Event event) throws IOException, IllegalArgumentException {
        proto.write(EventObfuscatedProto.PACKAGE_TOKEN, event.mPackageToken + 1);
        if (event.mClassToken != PackagesTokenData.UNASSIGNED_TOKEN) {
            proto.write(EventObfuscatedProto.CLASS_TOKEN, event.mClassToken + 1);
        }
        writeOffsetTimestamp(proto, EventObfuscatedProto.TIME_MS, event.mTimeStamp, statsBeginTime);
        proto.write(EventObfuscatedProto.FLAGS, event.mFlags);
        proto.write(EventObfuscatedProto.TYPE, event.mEventType);
        proto.write(EventObfuscatedProto.INSTANCE_ID, event.mInstanceId);
        if (event.mTaskRootPackageToken != PackagesTokenData.UNASSIGNED_TOKEN) {
            proto.write(EventObfuscatedProto.TASK_ROOT_PACKAGE_TOKEN,
                    event.mTaskRootPackageToken + 1);
        }
        if (event.mTaskRootClassToken != PackagesTokenData.UNASSIGNED_TOKEN) {
            proto.write(EventObfuscatedProto.TASK_ROOT_CLASS_TOKEN, event.mTaskRootClassToken + 1);
        }
        switch (event.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                if (event.mConfiguration != null) {
                    event.mConfiguration.dumpDebug(proto, EventObfuscatedProto.CONFIG);
                }
                break;
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                if (event.mBucketAndReason != 0) {
                    proto.write(EventObfuscatedProto.STANDBY_BUCKET, event.mBucketAndReason);
                }
                break;
            case UsageEvents.Event.SHORTCUT_INVOCATION:
                if (event.mShortcutIdToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                    proto.write(EventObfuscatedProto.SHORTCUT_ID_TOKEN, event.mShortcutIdToken + 1);
                }
                break;
            case UsageEvents.Event.LOCUS_ID_SET:
                if (event.mLocusIdToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                    proto.write(EventObfuscatedProto.LOCUS_ID_TOKEN, event.mLocusIdToken + 1);
                }
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                if (event.mNotificationChannelIdToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                    proto.write(EventObfuscatedProto.NOTIFICATION_CHANNEL_ID_TOKEN,
                            event.mNotificationChannelIdToken + 1);
                }
                break;
            case UsageEvents.Event.USER_INTERACTION:
                if (event.mUserInteractionExtrasToken != null) {
                    writeUserInteractionEventExtras(proto, EventObfuscatedProto.INTERACTION_EXTRAS,
                            event.mUserInteractionExtrasToken);
                }
                break;
        }
    }

    /**
     * Populates a tokenized version of interval stats from the input stream given.
     *
     * @param in the input stream from which to read events.
     * @param stats the interval stats object which will be populated.
     */
    public static void read(InputStream in, IntervalStats stats, boolean skipEvents)
            throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsObfuscatedProto.END_TIME_MS:
                    stats.endTime = stats.beginTime + proto.readLong(
                            IntervalStatsObfuscatedProto.END_TIME_MS);
                    break;
                case (int) IntervalStatsObfuscatedProto.MAJOR_VERSION:
                    stats.majorVersion = proto.readInt(IntervalStatsObfuscatedProto.MAJOR_VERSION);
                    break;
                case (int) IntervalStatsObfuscatedProto.MINOR_VERSION:
                    stats.minorVersion = proto.readInt(IntervalStatsObfuscatedProto.MINOR_VERSION);
                    break;
                case (int) IntervalStatsObfuscatedProto.INTERACTIVE:
                    loadCountAndTime(proto, IntervalStatsObfuscatedProto.INTERACTIVE,
                            stats.interactiveTracker);
                    break;
                case (int) IntervalStatsObfuscatedProto.NON_INTERACTIVE:
                    loadCountAndTime(proto, IntervalStatsObfuscatedProto.NON_INTERACTIVE,
                            stats.nonInteractiveTracker);
                    break;
                case (int) IntervalStatsObfuscatedProto.KEYGUARD_SHOWN:
                    loadCountAndTime(proto, IntervalStatsObfuscatedProto.KEYGUARD_SHOWN,
                            stats.keyguardShownTracker);
                    break;
                case (int) IntervalStatsObfuscatedProto.KEYGUARD_HIDDEN:
                    loadCountAndTime(proto, IntervalStatsObfuscatedProto.KEYGUARD_HIDDEN,
                            stats.keyguardHiddenTracker);
                    break;
                case (int) IntervalStatsObfuscatedProto.PACKAGES:
                    try {
                        final long packagesToken = proto.start(
                                IntervalStatsObfuscatedProto.PACKAGES);
                        UsageStats usageStats = parseUsageStats(proto, stats.beginTime);
                        proto.end(packagesToken);
                        if (usageStats.mPackageToken != PackagesTokenData.UNASSIGNED_TOKEN) {
                            stats.packageStatsObfuscated.put(usageStats.mPackageToken, usageStats);
                        }
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some usage stats from proto.", e);
                    }
                    break;
                case (int) IntervalStatsObfuscatedProto.CONFIGURATIONS:
                    try {
                        final long configsToken = proto.start(
                                IntervalStatsObfuscatedProto.CONFIGURATIONS);
                        loadConfigStats(proto, stats);
                        proto.end(configsToken);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some configuration stats from proto.", e);
                    }
                    break;
                case (int) IntervalStatsObfuscatedProto.EVENT_LOG:
                    if (skipEvents) {
                        break;
                    }
                    try {
                        final long eventsToken = proto.start(
                                IntervalStatsObfuscatedProto.EVENT_LOG);
                        UsageEvents.Event event = parseEvent(proto, stats.beginTime);
                        proto.end(eventsToken);
                        if (event != null) {
                            stats.events.insert(event);
                        }
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some events from proto.", e);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    // update the begin and end time stamps for all usage stats
                    final int usageStatsSize = stats.packageStatsObfuscated.size();
                    for (int i = 0; i < usageStatsSize; i++) {
                        final UsageStats usageStats = stats.packageStatsObfuscated.valueAt(i);
                        usageStats.mBeginTimeStamp = stats.beginTime;
                        usageStats.mEndTimeStamp = stats.endTime;
                    }
                    return;
            }
        }
    }

    /**
     * Writes the tokenized interval stats object to a ProtoBuf file.
     *
     * @param out the output stream to which to write the interval stats data.
     * @param stats the interval stats object to write to the proto file.
     */
    public static void write(OutputStream out, IntervalStats stats)
            throws IOException, IllegalArgumentException {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        proto.write(IntervalStatsObfuscatedProto.END_TIME_MS,
                getOffsetTimestamp(stats.endTime, stats.beginTime));
        proto.write(IntervalStatsObfuscatedProto.MAJOR_VERSION, stats.majorVersion);
        proto.write(IntervalStatsObfuscatedProto.MINOR_VERSION, stats.minorVersion);

        try {
            writeCountAndTime(proto, IntervalStatsObfuscatedProto.INTERACTIVE,
                    stats.interactiveTracker.count, stats.interactiveTracker.duration);
            writeCountAndTime(proto, IntervalStatsObfuscatedProto.NON_INTERACTIVE,
                    stats.nonInteractiveTracker.count, stats.nonInteractiveTracker.duration);
            writeCountAndTime(proto, IntervalStatsObfuscatedProto.KEYGUARD_SHOWN,
                    stats.keyguardShownTracker.count, stats.keyguardShownTracker.duration);
            writeCountAndTime(proto, IntervalStatsObfuscatedProto.KEYGUARD_HIDDEN,
                    stats.keyguardHiddenTracker.count, stats.keyguardHiddenTracker.duration);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write some interval stats trackers to proto.", e);
        }

        final int statsCount = stats.packageStatsObfuscated.size();
        for (int i = 0; i < statsCount; i++) {
            try {
                final long token = proto.start(IntervalStatsObfuscatedProto.PACKAGES);
                writeUsageStats(proto, stats.beginTime, stats.packageStatsObfuscated.valueAt(i));
                proto.end(token);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some usage stats to proto.", e);
            }
        }
        final int configCount = stats.configurations.size();
        for (int i = 0; i < configCount; i++) {
            boolean active = stats.activeConfiguration.equals(stats.configurations.keyAt(i));
            try {
                final long token = proto.start(IntervalStatsObfuscatedProto.CONFIGURATIONS);
                writeConfigStats(proto, stats.beginTime, stats.configurations.valueAt(i), active);
                proto.end(token);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some configuration stats to proto.", e);
            }
        }
        final int eventCount = stats.events.size();
        for (int i = 0; i < eventCount; i++) {
            try {
                final long token = proto.start(IntervalStatsObfuscatedProto.EVENT_LOG);
                writeEvent(proto, stats.beginTime, stats.events.get(i));
                proto.end(token);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some events to proto.", e);
            }
        }

        proto.flush();
    }

    /***** Read/Write obfuscated packages data logic. *****/

    private static void loadPackagesMap(ProtoInputStream proto,
            SparseArray<ArrayList<String>> tokensToPackagesMap) throws IOException {
        int key = PackagesTokenData.UNASSIGNED_TOKEN;
        final ArrayList<String> strings = new ArrayList<>();
        while (true) {
            switch (proto.nextField()) {
                case (int) ObfuscatedPackagesProto.PackagesMap.PACKAGE_TOKEN:
                    key = proto.readInt(ObfuscatedPackagesProto.PackagesMap.PACKAGE_TOKEN) - 1;
                    break;
                case (int) ObfuscatedPackagesProto.PackagesMap.STRINGS:
                    strings.add(proto.readString(ObfuscatedPackagesProto.PackagesMap.STRINGS));
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (key != PackagesTokenData.UNASSIGNED_TOKEN) {
                        tokensToPackagesMap.put(key, strings);
                    }
                    return;
            }
        }
    }

    /**
     * Populates the package mappings from the input stream given.
     *
     * @param in the input stream from which to read the mappings.
     * @param packagesTokenData the packages data object to which the data will be read to.
     */
    static void readObfuscatedData(InputStream in, PackagesTokenData packagesTokenData)
            throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        while (true) {
            switch (proto.nextField()) {
                case (int) ObfuscatedPackagesProto.COUNTER:
                    packagesTokenData.counter = proto.readInt(ObfuscatedPackagesProto.COUNTER);
                    break;
                case (int) ObfuscatedPackagesProto.PACKAGES_MAP:
                    final long token = proto.start(ObfuscatedPackagesProto.PACKAGES_MAP);
                    loadPackagesMap(proto, packagesTokenData.tokensToPackagesMap);
                    proto.end(token);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    /**
     * Writes the packages mapping data to a ProtoBuf file.
     *
     * @param out the output stream to which to write the mappings.
     * @param packagesTokenData the packages data object holding the data to write.
     */
    static void writeObfuscatedData(OutputStream out, PackagesTokenData packagesTokenData)
            throws IOException, IllegalArgumentException {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        proto.write(ObfuscatedPackagesProto.COUNTER, packagesTokenData.counter);

        final int mapSize = packagesTokenData.tokensToPackagesMap.size();
        for (int i = 0; i < mapSize; i++) {
            final long token = proto.start(ObfuscatedPackagesProto.PACKAGES_MAP);
            int packageToken = packagesTokenData.tokensToPackagesMap.keyAt(i);
            proto.write(ObfuscatedPackagesProto.PackagesMap.PACKAGE_TOKEN, packageToken + 1);

            final ArrayList<String> strings = packagesTokenData.tokensToPackagesMap.valueAt(i);
            final int listSize = strings.size();
            for (int j = 0; j < listSize; j++) {
                proto.write(ObfuscatedPackagesProto.PackagesMap.STRINGS, strings.get(j));
            }
            proto.end(token);
        }

        proto.flush();
    }

    /***** Read/Write pending events logic. *****/

    private static UsageEvents.Event parsePendingEvent(ProtoInputStream proto) throws IOException {
        final UsageEvents.Event event = new UsageEvents.Event();
        while (true) {
            switch (proto.nextField()) {
                case (int) PendingEventProto.PACKAGE_NAME:
                    event.mPackage = proto.readString(PendingEventProto.PACKAGE_NAME);
                    break;
                case (int) PendingEventProto.CLASS_NAME:
                    event.mClass = proto.readString(PendingEventProto.CLASS_NAME);
                    break;
                case (int) PendingEventProto.TIME_MS:
                    event.mTimeStamp = proto.readLong(PendingEventProto.TIME_MS);
                    break;
                case (int) PendingEventProto.FLAGS:
                    event.mFlags = proto.readInt(PendingEventProto.FLAGS);
                    break;
                case (int) PendingEventProto.TYPE:
                    event.mEventType = proto.readInt(PendingEventProto.TYPE);
                    break;
                case (int) PendingEventProto.CONFIG:
                    event.mConfiguration = new Configuration();
                    event.mConfiguration.readFromProto(proto, PendingEventProto.CONFIG);
                    break;
                case (int) PendingEventProto.SHORTCUT_ID:
                    event.mShortcutId = proto.readString(PendingEventProto.SHORTCUT_ID);
                    break;
                case (int) PendingEventProto.STANDBY_BUCKET:
                    event.mBucketAndReason = proto.readInt(PendingEventProto.STANDBY_BUCKET);
                    break;
                case (int) PendingEventProto.NOTIFICATION_CHANNEL_ID:
                    event.mNotificationChannelId = proto.readString(
                            PendingEventProto.NOTIFICATION_CHANNEL_ID);
                    break;
                case (int) PendingEventProto.INSTANCE_ID:
                    event.mInstanceId = proto.readInt(PendingEventProto.INSTANCE_ID);
                    break;
                case (int) PendingEventProto.TASK_ROOT_PACKAGE:
                    event.mTaskRootPackage = proto.readString(PendingEventProto.TASK_ROOT_PACKAGE);
                    break;
                case (int) PendingEventProto.TASK_ROOT_CLASS:
                    event.mTaskRootClass = proto.readString(PendingEventProto.TASK_ROOT_CLASS);
                    break;
                case (int) PendingEventProto.EXTRAS:
                    event.mExtras = parsePendingEventExtras(proto, PendingEventProto.EXTRAS);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    // Handle default values for certain events types
                    switch (event.mEventType) {
                        case UsageEvents.Event.CONFIGURATION_CHANGE:
                            if (event.mConfiguration == null) {
                                event.mConfiguration = new Configuration();
                            }
                            break;
                        case UsageEvents.Event.SHORTCUT_INVOCATION:
                            if (event.mShortcutId == null) {
                                event.mShortcutId = "";
                            }
                            break;
                        case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                            if (event.mNotificationChannelId == null) {
                                event.mNotificationChannelId = "";
                            }
                            break;
                    }
                    return event.mPackage == null ? null : event;
            }
        }
    }

    /**
     * Populates the list of pending events from the input stream given.
     *
     * @param in the input stream from which to read the pending events.
     * @param events the list of pending events to populate.
     */
    static void readPendingEvents(InputStream in, LinkedList<UsageEvents.Event> events)
            throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsObfuscatedProto.PENDING_EVENTS:
                    try {
                        final long token = proto.start(IntervalStatsObfuscatedProto.PENDING_EVENTS);
                        UsageEvents.Event event = parsePendingEvent(proto);
                        proto.end(token);
                        if (event != null) {
                            events.add(event);
                        }
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to parse some pending events from proto.", e);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    private static void writePendingEvent(ProtoOutputStream proto, UsageEvents.Event event)
            throws IOException, IllegalArgumentException {
        proto.write(PendingEventProto.PACKAGE_NAME, event.mPackage);
        if (event.mClass != null) {
            proto.write(PendingEventProto.CLASS_NAME, event.mClass);
        }
        proto.write(PendingEventProto.TIME_MS, event.mTimeStamp);
        proto.write(PendingEventProto.FLAGS, event.mFlags);
        proto.write(PendingEventProto.TYPE, event.mEventType);
        proto.write(PendingEventProto.INSTANCE_ID, event.mInstanceId);
        if (event.mTaskRootPackage != null) {
            proto.write(PendingEventProto.TASK_ROOT_PACKAGE, event.mTaskRootPackage);
        }
        if (event.mTaskRootClass != null) {
            proto.write(PendingEventProto.TASK_ROOT_CLASS, event.mTaskRootClass);
        }
        switch (event.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                if (event.mConfiguration != null) {
                    event.mConfiguration.dumpDebug(proto, PendingEventProto.CONFIG);
                }
                break;
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                if (event.mBucketAndReason != 0) {
                    proto.write(PendingEventProto.STANDBY_BUCKET, event.mBucketAndReason);
                }
                break;
            case UsageEvents.Event.SHORTCUT_INVOCATION:
                if (event.mShortcutId != null) {
                    proto.write(PendingEventProto.SHORTCUT_ID, event.mShortcutId);
                }
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                if (event.mNotificationChannelId != null) {
                    proto.write(PendingEventProto.NOTIFICATION_CHANNEL_ID,
                            event.mNotificationChannelId);
                }
                break;
            case UsageEvents.Event.USER_INTERACTION:
                if (event.mExtras != null && event.mExtras.size() != 0) {
                    writePendingEventExtras(proto, PendingEventProto.EXTRAS, event.mExtras);
                }
                break;
        }
    }

    /**
     * Writes the pending events to a ProtoBuf file.
     *
     * @param out the output stream to which to write the pending events.
     * @param events the list of pending events.
     */
    static void writePendingEvents(OutputStream out, LinkedList<UsageEvents.Event> events)
            throws IOException, IllegalArgumentException {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        final int eventCount = events.size();
        for (int i = 0; i < eventCount; i++) {
            try {
                final long token = proto.start(IntervalStatsObfuscatedProto.PENDING_EVENTS);
                writePendingEvent(proto, events.get(i));
                proto.end(token);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some pending events to proto.", e);
            }
        }
        proto.flush();
    }

    private static Pair<String, Long> parseGlobalComponentUsage(ProtoInputStream proto)
            throws IOException {
        String packageName = "";
        long time = 0;
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsObfuscatedProto.PackageUsage.PACKAGE_NAME:
                    packageName = proto.readString(
                            IntervalStatsObfuscatedProto.PackageUsage.PACKAGE_NAME);
                    break;
                case (int) IntervalStatsObfuscatedProto.PackageUsage.TIME_MS:
                    time = proto.readLong(IntervalStatsObfuscatedProto.PackageUsage.TIME_MS);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return new Pair<>(packageName, time);
            }
        }
    }

    /**
     * Populates the map of latest package usage from the input stream given.
     *
     * @param in the input stream from which to read the package usage.
     * @param lastTimeComponentUsedGlobal the map of package's global component usage to populate.
     */
    static void readGlobalComponentUsage(InputStream in,
            Map<String, Long> lastTimeComponentUsedGlobal) throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsObfuscatedProto.PACKAGE_USAGE:
                    try {
                        final long token = proto.start(IntervalStatsObfuscatedProto.PACKAGE_USAGE);
                        final Pair<String, Long> usage = parseGlobalComponentUsage(proto);
                        proto.end(token);
                        if (!usage.first.isEmpty() && usage.second > 0) {
                            lastTimeComponentUsedGlobal.put(usage.first, usage.second);
                        }
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to parse some package usage from proto.", e);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return;
            }
        }
    }

    /**
     * Writes the user-agnostic last time package usage to a ProtoBuf file.
     *
     * @param out the output stream to which to write the package usage
     * @param lastTimeComponentUsedGlobal the map storing the global component usage of packages
     */
    static void writeGlobalComponentUsage(OutputStream out,
            Map<String, Long> lastTimeComponentUsedGlobal) {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        final Map.Entry<String, Long>[] entries =
                (Map.Entry<String, Long>[]) lastTimeComponentUsedGlobal.entrySet().toArray();
        final int size = entries.length;
        for (int i = 0; i < size; ++i) {
            if (entries[i].getValue() <= 0) continue;
            final long token = proto.start(IntervalStatsObfuscatedProto.PACKAGE_USAGE);
            proto.write(IntervalStatsObfuscatedProto.PackageUsage.PACKAGE_NAME,
                    entries[i].getKey());
            proto.write(IntervalStatsObfuscatedProto.PackageUsage.TIME_MS, entries[i].getValue());
            proto.end(token);
        }
    }

    private static UserInteractionEventExtrasToken parseUserInteractionEventExtras(
            ProtoInputStream proto) throws IOException {
        UserInteractionEventExtrasToken interactionExtrasToken =
                new UserInteractionEventExtrasToken();
        while (true) {
            switch (proto.nextField()) {
                case (int) ObfuscatedUserInteractionExtrasProto.CATEGORY_TOKEN:
                    interactionExtrasToken.mCategoryToken = proto.readInt(
                            ObfuscatedUserInteractionExtrasProto.CATEGORY_TOKEN) - 1;
                    break;
                case (int) ObfuscatedUserInteractionExtrasProto.ACTION_TOKEN:
                    interactionExtrasToken.mActionToken = proto.readInt(
                            ObfuscatedUserInteractionExtrasProto.ACTION_TOKEN) - 1;
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    return interactionExtrasToken;
            }
        }
    }

    static void writeUserInteractionEventExtras(ProtoOutputStream proto, long fieldId,
            UserInteractionEventExtrasToken interactionExtras) {
        final long token = proto.start(fieldId);
        proto.write(ObfuscatedUserInteractionExtrasProto.CATEGORY_TOKEN,
                interactionExtras.mCategoryToken + 1);
        proto.write(ObfuscatedUserInteractionExtrasProto.ACTION_TOKEN,
                interactionExtras.mActionToken + 1);
        proto.end(token);
    }

    /**
     * Populates the extra details for pending interaction event from the protobuf stream.
     */
    private static PersistableBundle parsePendingEventExtras(ProtoInputStream proto, long fieldId)
            throws IOException {
        return PersistableBundle.readFromStream(new ByteArrayInputStream(proto.readBytes(fieldId)));
    }

    /**
     * Write the extra details for pending interaction event to a protobuf stream.
     */
    static void writePendingEventExtras(ProtoOutputStream proto, long fieldId,
            PersistableBundle eventExtras) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        eventExtras.writeToStream(baos);
        proto.write(fieldId, baos.toByteArray());
    }
}
