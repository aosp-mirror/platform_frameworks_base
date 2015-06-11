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

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.usage.ConfigurationStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.text.TextUtils;

import java.io.IOException;
import java.net.ProtocolException;

/**
 * UsageStats reader/writer for version 1 of the XML format.
 */
final class UsageStatsXmlV1 {
    private static final String PACKAGES_TAG = "packages";
    private static final String PACKAGE_TAG = "package";

    private static final String CONFIGURATIONS_TAG = "configurations";
    private static final String CONFIG_TAG = "config";

    private static final String EVENT_LOG_TAG = "event-log";
    private static final String EVENT_TAG = "event";

    // Attributes
    private static final String PACKAGE_ATTR = "package";
    private static final String CLASS_ATTR = "class";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "timeActive";
    private static final String COUNT_ATTR = "count";
    private static final String ACTIVE_ATTR = "active";
    private static final String LAST_EVENT_ATTR = "lastEvent";
    private static final String TYPE_ATTR = "type";

    // Time attributes stored as an offset of the beginTime.
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String LAST_TIME_ACTIVE_SYSTEM_ATTR = "lastTimeActiveSystem";
    private static final String BEGIN_IDLE_TIME_ATTR = "beginIdleTime";
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

        final String lastTimeUsedSystem = parser.getAttributeValue(null,
                LAST_TIME_ACTIVE_SYSTEM_ATTR);
        if (TextUtils.isEmpty(lastTimeUsedSystem)) {
            // If the field isn't present, use the old one.
            stats.mLastTimeSystemUsed = stats.mLastTimeUsed;
        } else {
            stats.mLastTimeSystemUsed = statsOut.beginTime + Long.parseLong(lastTimeUsedSystem);
        }

        final String beginIdleTime = parser.getAttributeValue(null, BEGIN_IDLE_TIME_ATTR);
        if (!TextUtils.isEmpty(beginIdleTime)) {
            stats.mBeginIdleTime = Long.parseLong(beginIdleTime);
        }
        stats.mTotalTimeInForeground = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        stats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
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

        // Apply the offset to the beginTime to find the absolute time of this event.
        event.mTimeStamp = statsOut.beginTime + XmlUtils.readLongAttribute(parser, TIME_ATTR);

        event.mEventType = XmlUtils.readIntAttribute(parser, TYPE_ATTR);
        if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE) {
            event.mConfiguration = new Configuration();
            Configuration.readXmlAttrs(parser, event.mConfiguration);
        }

        if (statsOut.events == null) {
            statsOut.events = new TimeSparseArray<>();
        }
        statsOut.events.put(event.mTimeStamp, event);
    }

    private static void writeUsageStats(XmlSerializer xml, final IntervalStats stats,
            final UsageStats usageStats) throws IOException {
        xml.startTag(null, PACKAGE_TAG);

        // Write the time offset.
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR,
                usageStats.mLastTimeUsed - stats.beginTime);
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_SYSTEM_ATTR,
                usageStats.mLastTimeSystemUsed - stats.beginTime);

        XmlUtils.writeStringAttribute(xml, PACKAGE_ATTR, usageStats.mPackageName);
        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, usageStats.mTotalTimeInForeground);
        XmlUtils.writeIntAttribute(xml, LAST_EVENT_ATTR, usageStats.mLastEvent);
        XmlUtils.writeLongAttribute(xml, BEGIN_IDLE_TIME_ATTR, usageStats.mBeginIdleTime);

        xml.endTag(null, PACKAGE_TAG);
    }

    private static void writeConfigStats(XmlSerializer xml, final IntervalStats stats,
            final ConfigurationStats configStats, boolean isActive) throws IOException {
        xml.startTag(null, CONFIG_TAG);

        // Write the time offset.
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR,
                configStats.mLastTimeActive - stats.beginTime);

        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, configStats.mTotalTimeActive);
        XmlUtils.writeIntAttribute(xml, COUNT_ATTR, configStats.mActivationCount);
        if (isActive) {
            XmlUtils.writeBooleanAttribute(xml, ACTIVE_ATTR, true);
        }

        // Now write the attributes representing the configuration object.
        Configuration.writeXmlAttrs(xml, configStats.mConfiguration);

        xml.endTag(null, CONFIG_TAG);
    }

    private static void writeEvent(XmlSerializer xml, final IntervalStats stats,
            final UsageEvents.Event event) throws IOException {
        xml.startTag(null, EVENT_TAG);

        // Store the time offset.
        XmlUtils.writeLongAttribute(xml, TIME_ATTR, event.mTimeStamp - stats.beginTime);

        XmlUtils.writeStringAttribute(xml, PACKAGE_ATTR, event.mPackage);
        if (event.mClass != null) {
            XmlUtils.writeStringAttribute(xml, CLASS_ATTR, event.mClass);
        }
        XmlUtils.writeIntAttribute(xml, TYPE_ATTR, event.mEventType);

        if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE
                && event.mConfiguration != null) {
            Configuration.writeXmlAttrs(xml, event.mConfiguration);
        }

        xml.endTag(null, EVENT_TAG);
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

        if (statsOut.events != null) {
            statsOut.events.clear();
        }

        statsOut.endTime = statsOut.beginTime + XmlUtils.readLongAttribute(parser, END_TIME_ATTR);

        int eventCode;
        int outerDepth = parser.getDepth();
        while ((eventCode = parser.next()) != XmlPullParser.END_DOCUMENT
                && (eventCode != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (eventCode != XmlPullParser.START_TAG) {
                continue;
            }

            final String tag = parser.getName();
            switch (tag) {
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

    /**
     * Writes the stats object to an XML file. The {@link XmlSerializer}
     * has already written the <code><usagestats></code> tag, but attributes may still
     * be added.
     *
     * @param xml The serializer to which to write the packageStats data.
     * @param stats The stats object to write to the XML file.
     */
    public static void write(XmlSerializer xml, IntervalStats stats) throws IOException {
        XmlUtils.writeLongAttribute(xml, END_TIME_ATTR, stats.endTime - stats.beginTime);

        xml.startTag(null, PACKAGES_TAG);
        final int statsCount = stats.packageStats.size();
        for (int i = 0; i < statsCount; i++) {
            writeUsageStats(xml, stats, stats.packageStats.valueAt(i));
        }
        xml.endTag(null, PACKAGES_TAG);


        xml.startTag(null, CONFIGURATIONS_TAG);
        final int configCount = stats.configurations.size();
        for (int i = 0; i < configCount; i++) {
            boolean active = stats.activeConfiguration.equals(stats.configurations.keyAt(i));
            writeConfigStats(xml, stats, stats.configurations.valueAt(i), active);
        }
        xml.endTag(null, CONFIGURATIONS_TAG);

        xml.startTag(null, EVENT_LOG_TAG);
        final int eventCount = stats.events != null ? stats.events.size() : 0;
        for (int i = 0; i < eventCount; i++) {
            writeEvent(xml, stats, stats.events.valueAt(i));
        }
        xml.endTag(null, EVENT_LOG_TAG);
    }

    private UsageStatsXmlV1() {
    }
}
