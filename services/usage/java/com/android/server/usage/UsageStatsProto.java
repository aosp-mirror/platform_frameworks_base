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
 * limitations under the License.
 */
package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;

/**
 * UsageStats reader/writer for Protocol Buffer format
 */
final class UsageStatsProto {
    private static String TAG = "UsageStatsProto";

    // Static-only utility class.
    private UsageStatsProto() {}

    private static List<String> readStringPool(ProtoInputStream proto) throws IOException {

        final long token = proto.start(IntervalStatsProto.STRINGPOOL);
        List<String> stringPool;
        if (proto.nextField(IntervalStatsProto.StringPool.SIZE)) {
            stringPool = new ArrayList(proto.readInt(IntervalStatsProto.StringPool.SIZE));
        } else {
            stringPool = new ArrayList();
        }
        while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (proto.getFieldNumber()) {
                case (int) IntervalStatsProto.StringPool.STRINGS:
                    stringPool.add(proto.readString(IntervalStatsProto.StringPool.STRINGS));
                    break;
            }
        }
        proto.end(token);
        return stringPool;
    }

    private static void loadUsageStats(ProtoInputStream proto, long fieldId,
            IntervalStats statsOut, List<String> stringPool)
            throws IOException {

        final long token = proto.start(fieldId);
        UsageStats stats;
        if (proto.nextField(IntervalStatsProto.UsageStats.PACKAGE_INDEX)) {
            // Fast path reading the package name index. Most cases this should work since it is
            // written first
            stats = statsOut.getOrCreateUsageStats(
                    stringPool.get(proto.readInt(IntervalStatsProto.UsageStats.PACKAGE_INDEX) - 1));
        } else if (proto.nextField(IntervalStatsProto.UsageStats.PACKAGE)) {
            // No package index, try package name instead
            stats = statsOut.getOrCreateUsageStats(
                    proto.readString(IntervalStatsProto.UsageStats.PACKAGE));
        } else {
            // Temporarily store collected data to a UsageStats object. This is not efficient.
            stats = new UsageStats();
        }

        while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (proto.getFieldNumber()) {
                case (int) IntervalStatsProto.UsageStats.PACKAGE:
                    // Fast track failed from some reason, add UsageStats object to statsOut now
                    UsageStats tempPackage = statsOut.getOrCreateUsageStats(
                            proto.readString(IntervalStatsProto.UsageStats.PACKAGE));
                    tempPackage.mLastTimeUsed = stats.mLastTimeUsed;
                    tempPackage.mTotalTimeInForeground = stats.mTotalTimeInForeground;
                    tempPackage.mLastEvent = stats.mLastEvent;
                    tempPackage.mAppLaunchCount = stats.mAppLaunchCount;
                    stats = tempPackage;
                    break;
                case (int) IntervalStatsProto.UsageStats.PACKAGE_INDEX:
                    // Fast track failed from some reason, add UsageStats object to statsOut now
                    UsageStats tempPackageIndex = statsOut.getOrCreateUsageStats(stringPool.get(
                            proto.readInt(IntervalStatsProto.UsageStats.PACKAGE_INDEX) - 1));
                    tempPackageIndex.mLastTimeUsed = stats.mLastTimeUsed;
                    tempPackageIndex.mTotalTimeInForeground = stats.mTotalTimeInForeground;
                    tempPackageIndex.mLastEvent = stats.mLastEvent;
                    tempPackageIndex.mAppLaunchCount = stats.mAppLaunchCount;
                    stats = tempPackageIndex;
                    break;
                case (int) IntervalStatsProto.UsageStats.LAST_TIME_ACTIVE_MS:
                    // Time attributes stored is an offset of the beginTime.
                    stats.mLastTimeUsed = statsOut.beginTime + proto.readLong(
                            IntervalStatsProto.UsageStats.LAST_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsProto.UsageStats.TOTAL_TIME_ACTIVE_MS:
                    stats.mTotalTimeInForeground = proto.readLong(
                            IntervalStatsProto.UsageStats.TOTAL_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsProto.UsageStats.LAST_EVENT:
                    stats.mLastEvent =
                            proto.readInt(IntervalStatsProto.UsageStats.LAST_EVENT);
                    break;
                case (int) IntervalStatsProto.UsageStats.APP_LAUNCH_COUNT:
                    stats.mAppLaunchCount = proto.readInt(
                            IntervalStatsProto.UsageStats.APP_LAUNCH_COUNT);
                    break;
                case (int) IntervalStatsProto.UsageStats.CHOOSER_ACTIONS:
                    try {
                        final long chooserToken = proto.start(
                                IntervalStatsProto.UsageStats.CHOOSER_ACTIONS);
                        loadChooserCounts(proto, stats);
                        proto.end(chooserToken);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read chooser counts for " + stats.mPackageName, e);
                    }
                    break;
                case (int) IntervalStatsProto.UsageStats.LAST_TIME_SERVICE_USED_MS:
                    // Time attributes stored is an offset of the beginTime.
                    stats.mLastTimeForegroundServiceUsed = statsOut.beginTime + proto.readLong(
                            IntervalStatsProto.UsageStats.LAST_TIME_SERVICE_USED_MS);
                    break;
                case (int) IntervalStatsProto.UsageStats.TOTAL_TIME_SERVICE_USED_MS:
                    stats.mTotalTimeForegroundServiceUsed = proto.readLong(
                            IntervalStatsProto.UsageStats.TOTAL_TIME_SERVICE_USED_MS);
                    break;
                case (int) IntervalStatsProto.UsageStats.LAST_TIME_VISIBLE_MS:
                    // Time attributes stored is an offset of the beginTime.
                    stats.mLastTimeVisible = statsOut.beginTime + proto.readLong(
                            IntervalStatsProto.UsageStats.LAST_TIME_VISIBLE_MS);
                    break;
                case (int) IntervalStatsProto.UsageStats.TOTAL_TIME_VISIBLE_MS:
                    stats.mTotalTimeVisible = proto.readLong(
                            IntervalStatsProto.UsageStats.TOTAL_TIME_VISIBLE_MS);
                    break;
            }
        }
        proto.end(token);
    }

    private static void loadCountAndTime(ProtoInputStream proto, long fieldId,
            IntervalStats.EventTracker tracker) {
        try {
            final long token = proto.start(fieldId);
            while (true) {
                switch (proto.nextField()) {
                    case (int) IntervalStatsProto.CountAndTime.COUNT:
                        tracker.count = proto.readInt(IntervalStatsProto.CountAndTime.COUNT);
                        break;
                    case (int) IntervalStatsProto.CountAndTime.TIME_MS:
                        tracker.duration = proto.readLong(IntervalStatsProto.CountAndTime.TIME_MS);
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
        if (usageStats.mChooserCounts == null) {
            usageStats.mChooserCounts = new ArrayMap<>();
        }
        String action = null;
        ArrayMap<String, Integer> counts;
        if (proto.nextField(IntervalStatsProto.UsageStats.ChooserAction.NAME)) {
            // Fast path reading the action name. Most cases this should work since it is written
            // first
            action = proto.readString(IntervalStatsProto.UsageStats.ChooserAction.NAME);
            counts = usageStats.mChooserCounts.get(action);
            if (counts == null) {
                counts = new ArrayMap<>();
                usageStats.mChooserCounts.put(action, counts);
            }
        } else {
            // Temporarily store collected data to an ArrayMap. This is not efficient.
            counts = new ArrayMap<>();
        }

        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsProto.UsageStats.ChooserAction.NAME:
                    // Fast path failed from some reason, add the ArrayMap object to usageStats now
                    action = proto.readString(IntervalStatsProto.UsageStats.ChooserAction.NAME);
                    usageStats.mChooserCounts.put(action, counts);
                    break;
                case (int) IntervalStatsProto.UsageStats.ChooserAction.COUNTS:
                    final long token = proto.start(
                            IntervalStatsProto.UsageStats.ChooserAction.COUNTS);
                    loadCountsForAction(proto, counts);
                    proto.end(token);
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (action == null) {
                        // default string
                        usageStats.mChooserCounts.put("", counts);
                    }
                    return;
            }
        }
    }

    private static void loadCountsForAction(ProtoInputStream proto,
            ArrayMap<String, Integer> counts) throws IOException {
        String category = null;
        int count = 0;
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.NAME:
                    category = proto.readString(
                            IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.NAME);
                    break;
                case (int) IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.COUNT:
                    count = proto.readInt(
                            IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.COUNT);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (category == null) {
                        counts.put("", count);
                    } else {
                        counts.put(category, count);
                    }
                    return;
            }
        }
    }

    private static void loadConfigStats(ProtoInputStream proto, long fieldId,
            IntervalStats statsOut) throws IOException {
        final long token = proto.start(fieldId);
        boolean configActive = false;
        final Configuration config = new Configuration();
        ConfigurationStats configStats;
        if (proto.nextField(IntervalStatsProto.Configuration.CONFIG)) {
            // Fast path reading the configuration. Most cases this should work since it is
            // written first
            config.readFromProto(proto, IntervalStatsProto.Configuration.CONFIG);
            configStats = statsOut.getOrCreateConfigurationStats(config);
        } else {
            // Temporarily store collected data to a ConfigurationStats object. This is not
            // efficient.
            configStats = new ConfigurationStats();
        }
        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsProto.Configuration.CONFIG:
                    // Fast path failed from some reason, add ConfigStats object to statsOut now
                    config.readFromProto(proto, IntervalStatsProto.Configuration.CONFIG);
                    final ConfigurationStats temp = statsOut.getOrCreateConfigurationStats(config);
                    temp.mLastTimeActive = configStats.mLastTimeActive;
                    temp.mTotalTimeActive = configStats.mTotalTimeActive;
                    temp.mActivationCount = configStats.mActivationCount;
                    configStats = temp;
                    break;
                case (int) IntervalStatsProto.Configuration.LAST_TIME_ACTIVE_MS:
                    configStats.mLastTimeActive = statsOut.beginTime + proto.readLong(
                            IntervalStatsProto.Configuration.LAST_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsProto.Configuration.TOTAL_TIME_ACTIVE_MS:
                    configStats.mTotalTimeActive = proto.readLong(
                            IntervalStatsProto.Configuration.TOTAL_TIME_ACTIVE_MS);
                    break;
                case (int) IntervalStatsProto.Configuration.COUNT:
                    configStats.mActivationCount = proto.readInt(
                            IntervalStatsProto.Configuration.COUNT);
                    break;
                case (int) IntervalStatsProto.Configuration.ACTIVE:
                    configActive = proto.readBoolean(IntervalStatsProto.Configuration.ACTIVE);
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    if (configActive) {
                        statsOut.activeConfiguration = configStats.mConfiguration;
                    }
                    proto.end(token);
                    return;
            }
        }
    }

    private static void loadEvent(ProtoInputStream proto, long fieldId, IntervalStats statsOut,
            List<String> stringPool) throws IOException {
        final long token = proto.start(fieldId);
        UsageEvents.Event event = statsOut.buildEvent(proto, stringPool);
        proto.end(token);
        if (event.mPackage == null) {
            throw new ProtocolException("no package field present");
        }
        statsOut.events.insert(event);
    }

    private static void writeStringPool(ProtoOutputStream proto, final IntervalStats stats)
            throws IllegalArgumentException {
        final long token = proto.start(IntervalStatsProto.STRINGPOOL);
        final int size = stats.mStringCache.size();
        proto.write(IntervalStatsProto.StringPool.SIZE, size);
        for (int i = 0; i < size; i++) {
            proto.write(IntervalStatsProto.StringPool.STRINGS, stats.mStringCache.valueAt(i));
        }
        proto.end(token);
    }

    private static void writeUsageStats(ProtoOutputStream proto, long fieldId,
            final IntervalStats stats, final UsageStats usageStats)
            throws IllegalArgumentException {
        final long token = proto.start(fieldId);
        // Write the package name first, so loadUsageStats can avoid creating an extra object
        final int packageIndex = stats.mStringCache.indexOf(usageStats.mPackageName);
        if (packageIndex >= 0) {
            proto.write(IntervalStatsProto.UsageStats.PACKAGE_INDEX, packageIndex + 1);
        } else {
            // Package not in Stringpool for some reason, write full string instead
            proto.write(IntervalStatsProto.UsageStats.PACKAGE, usageStats.mPackageName);
        }
        UsageStatsProtoV2.writeOffsetTimestamp(proto,
                IntervalStatsProto.UsageStats.LAST_TIME_ACTIVE_MS,
                usageStats.mLastTimeUsed, stats.beginTime);
        proto.write(IntervalStatsProto.UsageStats.TOTAL_TIME_ACTIVE_MS,
                usageStats.mTotalTimeInForeground);
        proto.write(IntervalStatsProto.UsageStats.LAST_EVENT,
                usageStats.mLastEvent);
        UsageStatsProtoV2.writeOffsetTimestamp(proto,
                IntervalStatsProto.UsageStats.LAST_TIME_SERVICE_USED_MS,
                usageStats.mLastTimeForegroundServiceUsed, stats.beginTime);
        proto.write(IntervalStatsProto.UsageStats.TOTAL_TIME_SERVICE_USED_MS,
                usageStats.mTotalTimeForegroundServiceUsed);
        UsageStatsProtoV2.writeOffsetTimestamp(proto,
                IntervalStatsProto.UsageStats.LAST_TIME_VISIBLE_MS,
                usageStats.mLastTimeVisible, stats.beginTime);
        proto.write(IntervalStatsProto.UsageStats.TOTAL_TIME_VISIBLE_MS,
                usageStats.mTotalTimeVisible);
        proto.write(IntervalStatsProto.UsageStats.APP_LAUNCH_COUNT, usageStats.mAppLaunchCount);
        try {
            writeChooserCounts(proto, usageStats);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write chooser counts for " + usageStats.mPackageName, e);
        }
        proto.end(token);
    }

    private static void writeCountAndTime(ProtoOutputStream proto, long fieldId, int count,
            long time) throws IllegalArgumentException {
        final long token = proto.start(fieldId);
        proto.write(IntervalStatsProto.CountAndTime.COUNT, count);
        proto.write(IntervalStatsProto.CountAndTime.TIME_MS, time);
        proto.end(token);
    }


    private static void writeChooserCounts(ProtoOutputStream proto, final UsageStats usageStats)
            throws IllegalArgumentException {
        if (usageStats == null || usageStats.mChooserCounts == null
                || usageStats.mChooserCounts.keySet().isEmpty()) {
            return;
        }
        final int chooserCountSize = usageStats.mChooserCounts.size();
        for (int i = 0; i < chooserCountSize; i++) {
            final String action = usageStats.mChooserCounts.keyAt(i);
            final ArrayMap<String, Integer> counts = usageStats.mChooserCounts.valueAt(i);
            if (action == null || counts == null || counts.isEmpty()) {
                continue;
            }
            final long token = proto.start(IntervalStatsProto.UsageStats.CHOOSER_ACTIONS);
            proto.write(IntervalStatsProto.UsageStats.ChooserAction.NAME, action);
            writeCountsForAction(proto, counts);
            proto.end(token);
        }
    }

    private static void writeCountsForAction(ProtoOutputStream proto,
            ArrayMap<String, Integer> counts) throws IllegalArgumentException {
        final int countsSize = counts.size();
        for (int i = 0; i < countsSize; i++) {
            String key = counts.keyAt(i);
            int count = counts.valueAt(i);
            if (count > 0) {
                final long token = proto.start(IntervalStatsProto.UsageStats.ChooserAction.COUNTS);
                proto.write(IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.NAME, key);
                proto.write(IntervalStatsProto.UsageStats.ChooserAction.CategoryCount.COUNT, count);
                proto.end(token);
            }
        }
    }

    private static void writeConfigStats(ProtoOutputStream proto, long fieldId,
            final IntervalStats stats, final ConfigurationStats configStats, boolean isActive)
            throws IllegalArgumentException {
        final long token = proto.start(fieldId);
        configStats.mConfiguration.dumpDebug(proto, IntervalStatsProto.Configuration.CONFIG);
        UsageStatsProtoV2.writeOffsetTimestamp(proto,
                IntervalStatsProto.Configuration.LAST_TIME_ACTIVE_MS,
                configStats.mLastTimeActive, stats.beginTime);
        proto.write(IntervalStatsProto.Configuration.TOTAL_TIME_ACTIVE_MS,
                configStats.mTotalTimeActive);
        proto.write(IntervalStatsProto.Configuration.COUNT, configStats.mActivationCount);
        proto.write(IntervalStatsProto.Configuration.ACTIVE, isActive);
        proto.end(token);
    }

    private static void writeEvent(ProtoOutputStream proto, long fieldId, final IntervalStats stats,
            final UsageEvents.Event event) throws IllegalArgumentException {
        final long token = proto.start(fieldId);
        final int packageIndex = stats.mStringCache.indexOf(event.mPackage);
        if (packageIndex >= 0) {
            proto.write(IntervalStatsProto.Event.PACKAGE_INDEX, packageIndex + 1);
        } else {
            // Package not in Stringpool for some reason, write full string instead
            proto.write(IntervalStatsProto.Event.PACKAGE, event.mPackage);
        }
        if (event.mClass != null) {
            final int classIndex = stats.mStringCache.indexOf(event.mClass);
            if (classIndex >= 0) {
                proto.write(IntervalStatsProto.Event.CLASS_INDEX, classIndex + 1);
            } else {
                // Class not in Stringpool for some reason, write full string instead
                proto.write(IntervalStatsProto.Event.CLASS, event.mClass);
            }
        }
        UsageStatsProtoV2.writeOffsetTimestamp(proto, IntervalStatsProto.Event.TIME_MS,
                event.mTimeStamp, stats.beginTime);
        proto.write(IntervalStatsProto.Event.FLAGS, event.mFlags);
        proto.write(IntervalStatsProto.Event.TYPE, event.mEventType);
        proto.write(IntervalStatsProto.Event.INSTANCE_ID, event.mInstanceId);
        if (event.mTaskRootPackage != null) {
            final int taskRootPackageIndex = stats.mStringCache.indexOf(event.mTaskRootPackage);
            if (taskRootPackageIndex >= 0) {
                proto.write(IntervalStatsProto.Event.TASK_ROOT_PACKAGE_INDEX,
                        taskRootPackageIndex + 1);
            }
        }
        if (event.mTaskRootClass != null) {
            final int taskRootClassIndex = stats.mStringCache.indexOf(event.mTaskRootClass);
            if (taskRootClassIndex >= 0) {
                proto.write(IntervalStatsProto.Event.TASK_ROOT_CLASS_INDEX,
                        taskRootClassIndex + 1);
            }
        }
        switch (event.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                if (event.mConfiguration != null) {
                    event.mConfiguration.dumpDebug(proto, IntervalStatsProto.Event.CONFIG);
                }
                break;
            case UsageEvents.Event.SHORTCUT_INVOCATION:
                if (event.mShortcutId != null) {
                    proto.write(IntervalStatsProto.Event.SHORTCUT_ID, event.mShortcutId);
                }
                break;
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                if (event.mBucketAndReason != 0) {
                    proto.write(IntervalStatsProto.Event.STANDBY_BUCKET, event.mBucketAndReason);
                }
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                if (event.mNotificationChannelId != null) {
                    final int channelIndex = stats.mStringCache.indexOf(
                            event.mNotificationChannelId);
                    if (channelIndex >= 0) {
                        proto.write(IntervalStatsProto.Event.NOTIFICATION_CHANNEL_INDEX,
                                channelIndex + 1);
                    } else {
                        // Channel not in Stringpool for some reason, write full string instead
                        proto.write(IntervalStatsProto.Event.NOTIFICATION_CHANNEL,
                                event.mNotificationChannelId);
                    }
                }
                break;
        }
        proto.end(token);
    }

    /**
     * Reads from the {@link ProtoInputStream}.
     *
     * @param proto    The proto from which to read events.
     * @param statsOut The stats object to populate with the data from the XML file.
     */
    public static void read(InputStream in, IntervalStats statsOut) throws IOException {
        final ProtoInputStream proto = new ProtoInputStream(in);
        List<String> stringPool = null;

        statsOut.packageStats.clear();
        statsOut.configurations.clear();
        statsOut.activeConfiguration = null;
        statsOut.events.clear();

        while (true) {
            switch (proto.nextField()) {
                case (int) IntervalStatsProto.END_TIME_MS:
                    statsOut.endTime = statsOut.beginTime + proto.readLong(
                            IntervalStatsProto.END_TIME_MS);
                    break;
                case (int) IntervalStatsProto.MAJOR_VERSION:
                    statsOut.majorVersion = proto.readInt(
                            IntervalStatsProto.MAJOR_VERSION);
                    break;
                case (int) IntervalStatsProto.MINOR_VERSION:
                    statsOut.minorVersion = proto.readInt(
                            IntervalStatsProto.MINOR_VERSION);
                    break;
                case (int) IntervalStatsProto.INTERACTIVE:
                    loadCountAndTime(proto, IntervalStatsProto.INTERACTIVE,
                            statsOut.interactiveTracker);
                    break;
                case (int) IntervalStatsProto.NON_INTERACTIVE:
                    loadCountAndTime(proto, IntervalStatsProto.NON_INTERACTIVE,
                            statsOut.nonInteractiveTracker);
                    break;
                case (int) IntervalStatsProto.KEYGUARD_SHOWN:
                    loadCountAndTime(proto, IntervalStatsProto.KEYGUARD_SHOWN,
                            statsOut.keyguardShownTracker);
                    break;
                case (int) IntervalStatsProto.KEYGUARD_HIDDEN:
                    loadCountAndTime(proto, IntervalStatsProto.KEYGUARD_HIDDEN,
                            statsOut.keyguardHiddenTracker);
                    break;
                case (int) IntervalStatsProto.STRINGPOOL:
                    try {
                        stringPool = readStringPool(proto);
                        statsOut.mStringCache.addAll(stringPool);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read string pool from proto.", e);
                    }
                    break;
                case (int) IntervalStatsProto.PACKAGES:
                    try {
                        loadUsageStats(proto, IntervalStatsProto.PACKAGES, statsOut, stringPool);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some usage stats from proto.", e);
                    }
                    break;
                case (int) IntervalStatsProto.CONFIGURATIONS:
                    try {
                        loadConfigStats(proto, IntervalStatsProto.CONFIGURATIONS, statsOut);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some configuration stats from proto.", e);
                    }
                    break;
                case (int) IntervalStatsProto.EVENT_LOG:
                    try {
                        loadEvent(proto, IntervalStatsProto.EVENT_LOG, statsOut, stringPool);
                    } catch (IOException e) {
                        Slog.e(TAG, "Unable to read some events from proto.", e);
                    }
                    break;
                case ProtoInputStream.NO_MORE_FIELDS:
                    statsOut.upgradeIfNeeded();
                    return;
            }
        }
    }

    /**
     * Writes the stats object to an ProtoBuf file.
     *
     * @param proto The serializer to which to write the packageStats data.
     * @param stats The stats object to write to the XML file.
     */
    public static void write(OutputStream out, IntervalStats stats)
            throws IOException, IllegalArgumentException {
        final ProtoOutputStream proto = new ProtoOutputStream(out);
        proto.write(IntervalStatsProto.END_TIME_MS,
                UsageStatsProtoV2.getOffsetTimestamp(stats.endTime, stats.beginTime));
        proto.write(IntervalStatsProto.MAJOR_VERSION, stats.majorVersion);
        proto.write(IntervalStatsProto.MINOR_VERSION, stats.minorVersion);
        // String pool should be written before the rest of the usage stats
        try {
            writeStringPool(proto, stats);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write string pool to proto.", e);
        }

        try {
            writeCountAndTime(proto, IntervalStatsProto.INTERACTIVE, stats.interactiveTracker.count,
                    stats.interactiveTracker.duration);
            writeCountAndTime(proto, IntervalStatsProto.NON_INTERACTIVE,
                    stats.nonInteractiveTracker.count, stats.nonInteractiveTracker.duration);
            writeCountAndTime(proto, IntervalStatsProto.KEYGUARD_SHOWN,
                    stats.keyguardShownTracker.count, stats.keyguardShownTracker.duration);
            writeCountAndTime(proto, IntervalStatsProto.KEYGUARD_HIDDEN,
                    stats.keyguardHiddenTracker.count, stats.keyguardHiddenTracker.duration);
        }  catch (IllegalArgumentException e) {
            Slog.e(TAG, "Unable to write some interval stats trackers to proto.", e);
        }

        final int statsCount = stats.packageStats.size();
        for (int i = 0; i < statsCount; i++) {
            try {
                writeUsageStats(proto, IntervalStatsProto.PACKAGES, stats,
                        stats.packageStats.valueAt(i));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some usage stats to proto.", e);
            }
        }
        final int configCount = stats.configurations.size();
        for (int i = 0; i < configCount; i++) {
            boolean active = stats.activeConfiguration.equals(stats.configurations.keyAt(i));
            try {
                writeConfigStats(proto, IntervalStatsProto.CONFIGURATIONS, stats,
                        stats.configurations.valueAt(i), active);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some configuration stats to proto.", e);
            }
        }
        final int eventCount = stats.events.size();
        for (int i = 0; i < eventCount; i++) {
            try {
                writeEvent(proto, IntervalStatsProto.EVENT_LOG, stats, stats.events.get(i));
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Unable to write some events to proto.", e);
            }
        }

        proto.flush();
    }
}
