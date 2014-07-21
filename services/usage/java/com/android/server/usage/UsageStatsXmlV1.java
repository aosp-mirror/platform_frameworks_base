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

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.ComponentName;

import java.io.IOException;
import java.net.ProtocolException;

/**
 * UsageStats reader/writer for version 1 of the XML format.
 */
final class UsageStatsXmlV1 {
    private static final String BEGIN_TIME_ATTR = "beginTime";
    private static final String END_TIME_ATTR = "endTime";
    private static final String PACKAGE_TAG = "package";
    private static final String NAME_ATTR = "name";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "totalTimeActive";
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String LAST_EVENT_ATTR = "lastEvent";
    private static final String EVENT_LOG_TAG = "event-log";
    private static final String TYPE_ATTR = "type";
    private static final String TIME_ATTR = "time";

    private static UsageStats readNextUsageStats(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            XmlUtils.nextElement(parser);
        }

        if (parser.getEventType() != XmlPullParser.START_TAG ||
                !parser.getName().equals(PACKAGE_TAG)) {
            return null;
        }

        final String name = parser.getAttributeValue(null, NAME_ATTR);
        if (name == null) {
            throw new ProtocolException("no " + NAME_ATTR + " attribute present");
        }

        UsageStats stats = new UsageStats();
        stats.mPackageName = name;
        stats.mTotalTimeInForeground = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        stats.mLastTimeUsed = XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
        stats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
        XmlUtils.skipCurrentTag(parser);
        return stats;
    }

    private static UsageEvents.Event readNextEvent(XmlPullParser parser, IntervalStats statsOut)
            throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            XmlUtils.nextElement(parser);
        }

        if (parser.getEventType() != XmlPullParser.START_TAG ||
                !parser.getName().equals(EVENT_LOG_TAG)) {
            return null;
        }

        final String componentName = XmlUtils.readStringAttribute(parser, NAME_ATTR);
        if (componentName == null) {
            throw new ProtocolException("no " + NAME_ATTR + " attribute present");
        }

        ComponentName component = statsOut.getCachedComponentName(componentName);
        if (component == null) {
            throw new ProtocolException("ComponentName " + componentName + " is invalid");
        }

        UsageEvents.Event event = new UsageEvents.Event();
        event.mComponent = component;
        event.mEventType = XmlUtils.readIntAttribute(parser, TYPE_ATTR);
        event.mTimeStamp = XmlUtils.readLongAttribute(parser, TIME_ATTR);
        XmlUtils.skipCurrentTag(parser);
        return event;
    }

    private static void writeUsageStats(FastXmlSerializer serializer, UsageStats stats)
            throws IOException {
        serializer.startTag(null, PACKAGE_TAG);
        serializer.attribute(null, NAME_ATTR, stats.mPackageName);
        serializer.attribute(null, TOTAL_TIME_ACTIVE_ATTR,
                Long.toString(stats.mTotalTimeInForeground));
        serializer.attribute(null, LAST_TIME_ACTIVE_ATTR, Long.toString(stats.mLastTimeUsed));
        serializer.attribute(null, LAST_EVENT_ATTR, Integer.toString(stats.mLastEvent));
        serializer.endTag(null, PACKAGE_TAG);
    }

    private static void writeEvent(FastXmlSerializer serializer, UsageEvents.Event event)
            throws IOException {
        serializer.startTag(null, EVENT_LOG_TAG);
        serializer.attribute(null, NAME_ATTR, event.getComponent().flattenToString());
        serializer.attribute(null, TYPE_ATTR, Integer.toString(event.getEventType()));
        serializer.attribute(null, TIME_ATTR, Long.toString(event.getTimeStamp()));
        serializer.endTag(null, EVENT_LOG_TAG);
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

        if (statsOut.events != null) {
            statsOut.events.clear();
        }

        statsOut.beginTime = XmlUtils.readLongAttribute(parser, BEGIN_TIME_ATTR);
        statsOut.endTime = XmlUtils.readLongAttribute(parser, END_TIME_ATTR);
        XmlUtils.nextElement(parser);

        UsageStats pkgStats;
        while ((pkgStats = readNextUsageStats(parser)) != null) {
            pkgStats.mBeginTimeStamp = statsOut.beginTime;
            pkgStats.mEndTimeStamp = statsOut.endTime;
            statsOut.stats.put(pkgStats.mPackageName, pkgStats);
        }

        UsageEvents.Event event;
        while ((event = readNextEvent(parser, statsOut)) != null) {
            if (statsOut.events == null) {
                statsOut.events = new TimeSparseArray<>();
            }
            statsOut.events.put(event.getTimeStamp(), event);
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

        if (stats.events != null) {
            final int eventCount = stats.events.size();
            for (int i = 0; i < eventCount; i++) {
                writeEvent(serializer, stats.events.valueAt(i));
            }
        }
    }

    private UsageStatsXmlV1() {
    }
}
