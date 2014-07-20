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

import android.app.usage.PackageUsageStats;
import android.app.usage.UsageStats;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;

public class UsageStatsXml {
    private static final String TAG = "UsageStatsXml";
    private static final int CURRENT_VERSION = 1;

    public static UsageStats read(AtomicFile file) throws IOException {
        try {
            FileInputStream in = file.openRead();
            try {
                return read(in);
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    // Empty
                }
            }
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "UsageStats Xml", e);
            throw e;
        }
    }

    private static final String USAGESTATS_TAG = "usagestats";
    private static final String VERSION_ATTR = "version";
    private static final String BEGIN_TIME_ATTR = "beginTime";
    private static final String END_TIME_ATTR = "endTime";
    private static final String PACKAGE_TAG = "package";
    private static final String NAME_ATTR = "name";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "totalTimeActive";
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String LAST_EVENT_ATTR = "lastEvent";

    public static UsageStats read(InputStream in) throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(in, "utf-8");
            XmlUtils.beginDocument(parser, USAGESTATS_TAG);
            String versionStr = parser.getAttributeValue(null, VERSION_ATTR);
            try {
                switch (Integer.parseInt(versionStr)) {
                    case 1:
                        return loadVersion1(parser);
                    default:
                        Slog.e(TAG, "Unrecognized version " + versionStr);
                        throw new IOException("Unrecognized version " + versionStr);
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Bad version");
                throw new IOException(e);
            }
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "Failed to parse Xml", e);
            throw new IOException(e);
        }
    }

    private static UsageStats loadVersion1(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        long beginTime = XmlUtils.readLongAttribute(parser, BEGIN_TIME_ATTR);
        long endTime = XmlUtils.readLongAttribute(parser, END_TIME_ATTR);
        UsageStats stats = UsageStats.create(beginTime, endTime);

        XmlUtils.nextElement(parser);

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            if (parser.getName().equals(PACKAGE_TAG)) {
                String name = parser.getAttributeValue(null, NAME_ATTR);
                if (name == null) {
                    throw new ProtocolException("no " + NAME_ATTR + " attribute present");
                }

                PackageUsageStats pkgStats = stats.getOrCreatePackageUsageStats(name);
                pkgStats.mTotalTimeSpent = XmlUtils.readLongAttribute(parser,
                        TOTAL_TIME_ACTIVE_ATTR);
                pkgStats.mLastTimeUsed = XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
                pkgStats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
            }

            // TODO(adamlesinski): Read in events here if there are any.

            XmlUtils.skipCurrentTag(parser);
        }
        return stats;
    }

    public static void write(UsageStats stats, AtomicFile file) throws IOException {
        FileOutputStream fos = file.startWrite();
        try {
            write(stats, fos);
            file.finishWrite(fos);
            fos = null;
        } finally {
            // When fos is null (successful write), this will no-op
            file.failWrite(fos);
        }
    }

    public static void write(UsageStats stats, OutputStream out) throws IOException {
        FastXmlSerializer xml = new FastXmlSerializer();
        xml.setOutput(out, "utf-8");
        xml.startDocument("utf-8", true);
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        xml.startTag(null, USAGESTATS_TAG);
        xml.attribute(null, VERSION_ATTR, Integer.toString(CURRENT_VERSION));
        xml.attribute(null, BEGIN_TIME_ATTR, Long.toString(stats.mBeginTimeStamp));
        xml.attribute(null, END_TIME_ATTR, Long.toString(stats.mEndTimeStamp));

        // Body of the stats
        final int pkgCount = stats.getPackageCount();
        for (int i = 0; i < pkgCount; i++) {
            final PackageUsageStats pkgStats = stats.getPackage(i);
            xml.startTag(null, PACKAGE_TAG);
            xml.attribute(null, NAME_ATTR, pkgStats.mPackageName);
            xml.attribute(null, TOTAL_TIME_ACTIVE_ATTR, Long.toString(pkgStats.mTotalTimeSpent));
            xml.attribute(null, LAST_TIME_ACTIVE_ATTR, Long.toString(pkgStats.mLastTimeUsed));
            xml.attribute(null, LAST_EVENT_ATTR, Integer.toString(pkgStats.mLastEvent));
            xml.endTag(null, PACKAGE_TAG);
        }

        // TODO(adamlesinski): Write out events here if there are any.

        xml.endTag(null, USAGESTATS_TAG);
        xml.endDocument();
    }
}
