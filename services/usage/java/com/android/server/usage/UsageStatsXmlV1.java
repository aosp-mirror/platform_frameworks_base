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

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.usage.ConfigurationStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.ComponentName;
import android.content.res.Configuration;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Locale;

/**
 * UsageStats reader/writer for version 1 of the XML format.
 */
final class UsageStatsXmlV1 {
    private static final String PACKAGE_TAG = "package";
    private static final String CONFIGURATION_TAG = "config";
    private static final String EVENT_LOG_TAG = "event-log";

    private static final String BEGIN_TIME_ATTR = "beginTime";
    private static final String END_TIME_ATTR = "endTime";
    private static final String NAME_ATTR = "name";
    private static final String PACKAGE_ATTR = "package";
    private static final String CLASS_ATTR = "class";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "totalTimeActive";
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String COUNT_ATTR = "count";
    private static final String ACTIVE_ATTR = "active";
    private static final String LAST_EVENT_ATTR = "lastEvent";
    private static final String TYPE_ATTR = "type";
    private static final String TIME_ATTR = "time";

    private static void loadUsageStats(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        final String name = parser.getAttributeValue(null, NAME_ATTR);
        if (name == null) {
            throw new ProtocolException("no " + NAME_ATTR + " attribute present");
        }

        UsageStats stats = statsOut.getOrCreateUsageStats(name);
        stats.mTotalTimeInForeground = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        stats.mLastTimeUsed = XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
        stats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
    }

    private static void loadConfigStats(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        final Configuration config = new Configuration();
        Configuration.readXmlAttrs(parser, config);

        ConfigurationStats configStats = statsOut.getOrCreateConfigurationStats(config);
        configStats.mLastTimeActive = XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
        configStats.mTotalTimeActive = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        configStats.mActivationCount = XmlUtils.readIntAttribute(parser, COUNT_ATTR);
        if (XmlUtils.readBooleanAttribute(parser, ACTIVE_ATTR)) {
            statsOut.activeConfiguration = configStats.mConfiguration;
        }
    }

    private static void loadEvent(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        String packageName = XmlUtils.readStringAttribute(parser, PACKAGE_ATTR);
        String className;
        if (packageName == null) {
            // Try getting the component name if it exists.
            final String componentName = XmlUtils.readStringAttribute(parser, NAME_ATTR);
            if (componentName == null) {
                throw new ProtocolException("no " + NAME_ATTR + " or " + PACKAGE_ATTR +
                        " attribute present");
            }
            ComponentName component = ComponentName.unflattenFromString(componentName);
            if (component == null) {
                throw new ProtocolException("ComponentName " + componentName + " is invalid");
            }

            packageName = component.getPackageName();
            className = component.getClassName();
        } else {
            className = XmlUtils.readStringAttribute(parser, CLASS_ATTR);
        }

        UsageEvents.Event event = statsOut.buildEvent(packageName, className);
        event.mEventType = XmlUtils.readIntAttribute(parser, TYPE_ATTR);
        event.mTimeStamp = XmlUtils.readLongAttribute(parser, TIME_ATTR);

        if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE) {
            event.mConfiguration = new Configuration();
            Configuration.readXmlAttrs(parser, event.mConfiguration);
        }

        if (statsOut.events == null) {
            statsOut.events = new TimeSparseArray<>();
        }
        statsOut.events.put(event.mTimeStamp, event);
    }

    private static void writeUsageStats(XmlSerializer xml, final UsageStats stats)
            throws IOException {
        xml.startTag(null, PACKAGE_TAG);
        XmlUtils.writeStringAttribute(xml, NAME_ATTR, stats.mPackageName);
        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, stats.mTotalTimeInForeground);
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR, stats.mLastTimeUsed);
        XmlUtils.writeIntAttribute(xml, LAST_EVENT_ATTR, stats.mLastEvent);
        xml.endTag(null, PACKAGE_TAG);
    }

    private static void writeConfigStats(XmlSerializer xml, final ConfigurationStats stats,
            boolean isActive) throws IOException {
        xml.startTag(null, CONFIGURATION_TAG);
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR, stats.mLastTimeActive);
        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, stats.mTotalTimeActive);
        XmlUtils.writeIntAttribute(xml, COUNT_ATTR, stats.mActivationCount);
        if (isActive) {
            XmlUtils.writeBooleanAttribute(xml, ACTIVE_ATTR, true);
        }

        // Now write the attributes representing the configuration object.
        Configuration.writeXmlAttrs(xml, stats.mConfiguration);

        xml.endTag(null, CONFIGURATION_TAG);
    }

    private static void writeEvent(XmlSerializer xml, final UsageEvents.Event event)
            throws IOException {
        xml.startTag(null, EVENT_LOG_TAG);
        XmlUtils.writeStringAttribute(xml, PACKAGE_ATTR, event.mPackage);
        if (event.mClass != null) {
            XmlUtils.writeStringAttribute(xml, CLASS_ATTR, event.mClass);
        }
        XmlUtils.writeIntAttribute(xml, TYPE_ATTR, event.mEventType);
        XmlUtils.writeLongAttribute(xml, TIME_ATTR, event.mTimeStamp);

        if (event.mEventType == UsageEvents.Event.CONFIGURATION_CHANGE
                && event.mConfiguration != null) {
            Configuration.writeXmlAttrs(xml, event.mConfiguration);
        }

        xml.endTag(null, EVENT_LOG_TAG);
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
        statsOut.stats.clear();
        statsOut.configurations.clear();
        statsOut.activeConfiguration = null;

        if (statsOut.events != null) {
            statsOut.events.clear();
        }

        statsOut.beginTime = XmlUtils.readLongAttribute(parser, BEGIN_TIME_ATTR);
        statsOut.endTime = XmlUtils.readLongAttribute(parser, END_TIME_ATTR);

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

                case CONFIGURATION_TAG:
                    loadConfigStats(parser, statsOut);
                    break;

                case EVENT_LOG_TAG:
                    loadEvent(parser, statsOut);
                    break;
            }
        }
    }

    /**
     * Writes the stats object to an XML file. The {@link FastXmlSerializer}
     * has already written the <code><usagestats></code> tag, but attributes may still
     * be added.
     *
     * @param serializer The serializer to which to write the stats data.
     * @param stats The stats object to write to the XML file.
     */
    public static void write(FastXmlSerializer serializer, IntervalStats stats) throws IOException {
        serializer.attribute(null, BEGIN_TIME_ATTR, Long.toString(stats.beginTime));
        serializer.attribute(null, END_TIME_ATTR, Long.toString(stats.endTime));

        final int statsCount = stats.stats.size();
        for (int i = 0; i < statsCount; i++) {
            writeUsageStats(serializer, stats.stats.valueAt(i));
        }

        final int configCount = stats.configurations.size();
        for (int i = 0; i < configCount; i++) {
            boolean active = stats.activeConfiguration.equals(stats.configurations.keyAt(i));
            writeConfigStats(serializer, stats.configurations.valueAt(i), active);
        }

        final int eventCount = stats.events != null ? stats.events.size() : 0;
        for (int i = 0; i < eventCount; i++) {
            writeEvent(serializer, stats.events.valueAt(i));
        }
    }

    private UsageStatsXmlV1() {
    }
}
