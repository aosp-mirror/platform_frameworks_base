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

import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.*;

public class UsageStatsXml {
    private static final String TAG = "UsageStatsXml";
    private static final int CURRENT_VERSION = 1;
    private static final String USAGESTATS_TAG = "usagestats";
    private static final String VERSION_ATTR = "version";
    static final String CHECKED_IN_SUFFIX = "-c";

    public static long parseBeginTime(AtomicFile file) throws IOException {
        return parseBeginTime(file.getBaseFile());
    }

    public static long parseBeginTime(File file) throws IOException {
        String name = file.getName();

        // Eat as many occurrences of -c as possible. This is due to a bug where -c
        // would be appended more than once to a checked-in file, causing a crash
        // on boot when indexing files since Long.parseLong() will puke on anything but
        // a number.
        while (name.endsWith(CHECKED_IN_SUFFIX)) {
            name = name.substring(0, name.length() - CHECKED_IN_SUFFIX.length());
        }

        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            throw new IOException(e);
        }
    }

    public static void read(AtomicFile file, IntervalStats statsOut) throws IOException {
        try {
            FileInputStream in = file.openRead();
            try {
                statsOut.beginTime = parseBeginTime(file);
                read(in, statsOut);
                statsOut.lastTimeSaved = file.getLastModifiedTime();
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

    public static void write(AtomicFile file, IntervalStats stats) throws IOException {
        FileOutputStream fos = file.startWrite();
        try {
            write(fos, stats);
            file.finishWrite(fos);
            fos = null;
        } finally {
            // When fos is null (successful write), this will no-op
            file.failWrite(fos);
        }
    }

    private static void read(InputStream in, IntervalStats statsOut) throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(in, "utf-8");
            XmlUtils.beginDocument(parser, USAGESTATS_TAG);
            String versionStr = parser.getAttributeValue(null, VERSION_ATTR);
            try {
                switch (Integer.parseInt(versionStr)) {
                    case 1:
                        UsageStatsXmlV1.read(parser, statsOut);
                        break;

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

    private static void write(OutputStream out, IntervalStats stats) throws IOException {
        FastXmlSerializer xml = new FastXmlSerializer();
        xml.setOutput(out, "utf-8");
        xml.startDocument("utf-8", true);
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        xml.startTag(null, USAGESTATS_TAG);
        xml.attribute(null, VERSION_ATTR, Integer.toString(CURRENT_VERSION));

        UsageStatsXmlV1.write(xml, stats);

        xml.endTag(null, USAGESTATS_TAG);
        xml.endDocument();
    }
}
