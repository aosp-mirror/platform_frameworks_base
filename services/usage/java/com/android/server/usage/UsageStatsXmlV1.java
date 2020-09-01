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
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.ProtocolException;

/**
 * UsageStats reader/writer for version 1 of the XML format.
 */
final class UsageStatsXmlV1 {
    private static final String TAG = "UsageStatsXmlV1";

    private static final String INTERACTIVE_TAG = "interactive";
    private static final String NON_INTERACTIVE_TAG = "non-interactive";
    private static final String KEYGUARD_SHOWN_TAG = "keyguard-shown";
    private static final String KEYGUARD_HIDDEN_TAG = "keyguard-hidden";

    private static final String PACKAGES_TAG = "packages";
    private static final String PACKAGE_TAG = "package";

    private static final String CHOOSER_COUNT_TAG = "chosen_action";
    private static final String CATEGORY_TAG = "category";
    private static final String NAME = "name";
    private static final String COUNT = "count";

    private static final String CONFIGURATIONS_TAG = "configurations";
    private static final String CONFIG_TAG = "config";

    private static final String EVENT_LOG_TAG = "event-log";
    private static final String EVENT_TAG = "event";

    // Attributes
    private static final String PACKAGE_ATTR = "package";
    private static final String FLAGS_ATTR = "flags";
    private static final String CLASS_ATTR = "class";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "timeActive";
    private static final String TOTAL_TIME_VISIBLE_ATTR = "timeVisible";
    private static final String TOTAL_TIME_SERVICE_USED_ATTR = "timeServiceUsed";
    private static final String COUNT_ATTR = "count";
    private static final String ACTIVE_ATTR = "active";
    private static final String LAST_EVENT_ATTR = "lastEvent";
    private static final String TYPE_ATTR = "type";
    private static final String INSTANCE_ID_ATTR = "instanceId";
    private static final String SHORTCUT_ID_ATTR = "shortcutId";
    private static final String STANDBY_BUCKET_ATTR = "standbyBucket";
    private static final String APP_LAUNCH_COUNT_ATTR = "appLaunchCount";
    private static final String NOTIFICATION_CHANNEL_ATTR = "notificationChannel";
    private static final String MAJOR_VERSION_ATTR = "majorVersion";
    private static final String MINOR_VERSION_ATTR = "minorVersion";

    // Time attributes stored as an offset of the beginTime.
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String LAST_TIME_VISIBLE_ATTR = "lastTimeVisible";
    private static final String LAST_TIME_SERVICE_USED_ATTR = "lastTimeServiceUsed";
    private static final String END_TIME_ATTR = "endTime";
    private static final String TIME_ATTR = "time";

    private static void loadUsageStats(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        final String pkg = parser.getAttributeValue(null, PACKAGE_ATTR);
        if (pkg == null) {
            throw new ProtocolException("no " + PACKAGE_ATTR + " attribute present");
        }
        final UsageStats stats = statsOut.getOrCreateUsageStats(pkg);

        // Apply the offset to the beginTime to find the absolute time.
        stats.mLastTimeUsed = statsOut.beginTime + XmlUtils.readLongAttribute(
                parser, LAST_TIME_ACTIVE_ATTR);

        try {
            stats.mLastTimeVisible = statsOut.beginTime + XmlUtils.readLongAttribute(
                    parser, LAST_TIME_VISIBLE_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse mLastTimeVisible");
        }

        try {
            stats.mLastTimeForegroundServiceUsed = statsOut.beginTime + XmlUtils.readLongAttribute(
                    parser, LAST_TIME_SERVICE_USED_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse mLastTimeForegroundServiceUsed");
        }

        stats.mTotalTimeInForeground = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);

        try {
            stats.mTotalTimeVisible = XmlUtils.readLongAttribute(parser, TOTAL_TIME_VISIBLE_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse mTotalTimeVisible");
        }

        try {
            stats.mTotalTimeForegroundServiceUsed = XmlUtils.readLongAttribute(parser,
                    TOTAL_TIME_SERVICE_USED_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse mTotalTimeForegroundServiceUsed");
        }

        stats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
        stats.mAppLaunchCount = XmlUtils.readIntAttribute(parser, APP_LAUNCH_COUNT_ATTR,
                0);
        int eventCode;
        while ((eventCode = parser.next()) != XmlPullParser.END_DOCUMENT) {
            final String tag = parser.getName();
            if (eventCode == XmlPullParser.END_TAG && tag.equals(PACKAGE_TAG)) {
                break;
            }
            if (eventCode != XmlPullParser.START_TAG) {
                continue;
            }
            if (tag.equals(CHOOSER_COUNT_TAG)) {
                String action = XmlUtils.readStringAttribute(parser, NAME);
                loadChooserCounts(parser, stats, action);
            }
        }
    }

    private static void loadCountAndTime(XmlPullParser parser,
            IntervalStats.EventTracker tracker)
            throws IOException, XmlPullParserException {
        tracker.count = XmlUtils.readIntAttribute(parser, COUNT_ATTR, 0);
        tracker.duration = XmlUtils.readLongAttribute(parser, TIME_ATTR, 0);
        XmlUtils.skipCurrentTag(parser);
    }

    private static void loadChooserCounts(
            XmlPullParser parser, UsageStats usageStats, String action)
            throws XmlPullParserException, IOException {
        if (action == null) {
            return;
        }
        if (usageStats.mChooserCounts == null) {
            usageStats.mChooserCounts = new ArrayMap<>();
        }
        if (!usageStats.mChooserCounts.containsKey(action)) {
            ArrayMap<String, Integer> counts = new ArrayMap<>();
            usageStats.mChooserCounts.put(action, counts);
        }

        int eventCode;
        while ((eventCode = parser.next()) != XmlPullParser.END_DOCUMENT) {
            final String tag = parser.getName();
            if (eventCode == XmlPullParser.END_TAG && tag.equals(CHOOSER_COUNT_TAG)) {
                break;
            }
            if (eventCode != XmlPullParser.START_TAG) {
                continue;
            }
            if (tag.equals(CATEGORY_TAG)) {
                String category = XmlUtils.readStringAttribute(parser, NAME);
                int count = XmlUtils.readIntAttribute(parser, COUNT);
                usageStats.mChooserCounts.get(action).put(category, count);
            }
        }
    }

    private static void loadConfigStats(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        final Configuration config = new Configuration();
        Configuration.readXmlAttrs(parser, config);

        final ConfigurationStats configStats = statsOut.getOrCreateConfigurationStats(config);

        // Apply the offset to the beginTime to find the absolute time.
        configStats.mLastTimeActive = statsOut.beginTime + XmlUtils.readLongAttribute(
                parser, LAST_TIME_ACTIVE_ATTR);

        configStats.mTotalTimeActive = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        configStats.mActivationCount = XmlUtils.readIntAttribute(parser, COUNT_ATTR);
        if (XmlUtils.readBooleanAttribute(parser, ACTIVE_ATTR)) {
            statsOut.activeConfiguration = configStats.mConfiguration;
        }
    }

    private static void loadEvent(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        final String packageName = XmlUtils.readStringAttribute(parser, PACKAGE_ATTR);
        if (packageName == null) {
            throw new ProtocolException("no " + PACKAGE_ATTR + " attribute present");
        }
        final String className = XmlUtils.readStringAttribute(parser, CLASS_ATTR);

        final UsageEvents.Event event = statsOut.buildEvent(packageName, className);

        event.mFlags = XmlUtils.readIntAttribute(parser, FLAGS_ATTR, 0);

        // Apply the offset to the beginTime to find the absolute time of this event.
        event.mTimeStamp = statsOut.beginTime + XmlUtils.readLongAttribute(parser, TIME_ATTR);

        event.mEventType = XmlUtils.readIntAttribute(parser, TYPE_ATTR);

        try {
            event.mInstanceId = XmlUtils.readIntAttribute(parser, INSTANCE_ID_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse mInstanceId");
        }

        switch (event.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                event.mConfiguration = new Configuration();
                Configuration.readXmlAttrs(parser, event.mConfiguration);
                break;
            case UsageEvents.Event.SHORTCUT_INVOCATION:
                final String id = XmlUtils.readStringAttribute(parser, SHORTCUT_ID_ATTR);
                event.mShortcutId = (id != null) ? id.intern() : null;
                break;
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                event.mBucketAndReason = XmlUtils.readIntAttribute(parser, STANDBY_BUCKET_ATTR, 0);
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                final String channelId =
                        XmlUtils.readStringAttribute(parser, NOTIFICATION_CHANNEL_ATTR);
                event.mNotificationChannelId = (channelId != null) ? channelId.intern() : null;
                break;
        }
        statsOut.addEvent(event);
    }

    /**
     * Reads from the {@link XmlPullParser}, assuming that it is already on the
     * <code><usagestats></code> tag.
     *
     * @param parser The parser from which to read events.
     * @param statsOut The stats object to populate with the data from the XML file.
     */
    public static void read(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        statsOut.packageStats.clear();
        statsOut.configurations.clear();
        statsOut.activeConfiguration = null;
        statsOut.events.clear();

        statsOut.endTime = statsOut.beginTime + XmlUtils.readLongAttribute(parser, END_TIME_ATTR);
        try {
            statsOut.majorVersion = XmlUtils.readIntAttribute(parser, MAJOR_VERSION_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse majorVersion");
        }

        try {
            statsOut.minorVersion = XmlUtils.readIntAttribute(parser, MINOR_VERSION_ATTR);
        } catch (IOException e) {
            Log.i(TAG, "Failed to parse minorVersion");
        }

        int eventCode;
        int outerDepth = parser.getDepth();
        while ((eventCode = parser.next()) != XmlPullParser.END_DOCUMENT
                && (eventCode != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (eventCode != XmlPullParser.START_TAG) {
                continue;
            }

            final String tag = parser.getName();
            switch (tag) {
                case INTERACTIVE_TAG:
                    loadCountAndTime(parser, statsOut.interactiveTracker);
                    break;

                case NON_INTERACTIVE_TAG:
                    loadCountAndTime(parser, statsOut.nonInteractiveTracker);
                    break;

                case KEYGUARD_SHOWN_TAG:
                    loadCountAndTime(parser, statsOut.keyguardShownTracker);
                    break;

                case KEYGUARD_HIDDEN_TAG:
                    loadCountAndTime(parser, statsOut.keyguardHiddenTracker);
                    break;

                case PACKAGE_TAG:
                    loadUsageStats(parser, statsOut);
                    break;

                case CONFIG_TAG:
                    loadConfigStats(parser, statsOut);
                    break;

                case EVENT_TAG:
                    loadEvent(parser, statsOut);
                    break;
            }
        }
    }

    private UsageStatsXmlV1() {
    }
}
