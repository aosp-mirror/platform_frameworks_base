/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import android.content.res.XmlResourceParser;
import android.test.AndroidTestCase;
import android.test.PerformanceTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Xml;
import com.google.wireless.gdata.data.Entry;
import com.google.wireless.gdata.data.Feed;
import com.google.wireless.gdata.parser.ParseException;
import com.google.wireless.gdata.parser.xml.XmlGDataParser;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Tests timing on parsing various formats of GData.
 */
public class GDataParseTest extends AndroidTestCase implements PerformanceTestCase {

    private static void parseXml(InputStream is) throws ParseException, IOException {
        XmlPullParser xmlParser = Xml.newPullParser();
        XmlGDataParser parser = new XmlGDataParser(is, xmlParser);
        Feed feed = parser.init();
        Entry entry = null;
        while (parser.hasMoreData()) {
            entry = parser.readNextEntry(entry);
        }
    }

    private static void parseXml(XmlPullParser xmlP) throws ParseException, IOException {
        XmlGDataParser parser = new XmlGDataParser(null /* in */, xmlP);
        Feed feed = parser.init();
        Entry entry = null;
        while (parser.hasMoreData()) {
            entry = parser.readNextEntry(entry);
        }
    }

    private static void dumpXml(XmlPullParser parser) throws
            XmlPullParserException, IOException {
        int eventType = parser.nextTag();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    parser.getName();
                    // System.out.print("<" + parser.getName());
                    int nattrs = parser.getAttributeCount();
                    for (int i = 0; i < nattrs; ++i) {
                        parser.getAttributeName(i);
                        parser.getAttributeValue(i);
                        // System.out.print(" " + parser.getAttributeName(i) + "=\""
                        //        + parser.getAttributeValue(i) + "\"");
                    }
                    // System.out.print(">");
                    break;
                case XmlPullParser.END_TAG:
                    parser.getName();
                    // System.out.print("</" + parser.getName() + ">");
                    break;
                case XmlPullParser.TEXT:
                    parser.getText();
                    // System.out.print(parser.getText());
                    break;
                default:
                    // do nothing
            }
            eventType = parser.next();
        }
    }

    private byte[] getBytesForResource(int resid) throws Exception {
        // all resources are written into a zip file, so the InputStream we
        // get for a resource is on top of zipped
        // data.  in order to compare performance of parsing unzipped vs.
        // zipped content, we first read the data into an in-memory buffer.
        InputStream zipIs = null;
        try {
            zipIs = mContext.getResources().openRawResource(resid);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte buf[] = new byte[1024];
            int bytesRead = zipIs.read(buf);
            while (bytesRead > 0) {
                baos.write(buf, 0, bytesRead);
                bytesRead = zipIs.read(buf);
            }
            return baos.toByteArray();
        } finally {
            if (zipIs != null) {
                zipIs.close();
            }
        }
    }

    public boolean isPerformanceOnly() {
        return true;
    }

    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        return 0;
    }

    @MediumTest
    public void testXml() throws Exception {
        InputStream is = new ByteArrayInputStream(getBytesForResource(R.raw.calendarxml));
        try {
            is.reset();
            parseXml(is);
        } finally {
            is.close();
        }
    }

    @MediumTest
    public void testXmlGzip() throws Exception {
        InputStream gzIs = new GZIPInputStream(
                new ByteArrayInputStream(getBytesForResource(R.raw.calendarxmlgz)));
        try {
            parseXml(gzIs);
        } finally {
            gzIs.close();
        }
    }

    @MediumTest
    public void testJson() throws Exception {
        String jsonString = new String(getBytesForResource(R.raw.calendarjs), "UTF-8");
        JSONTokener tokens = new JSONTokener(jsonString);
        assertNotNull(new JSONObject(tokens));
    }

    @SmallTest
    public void testBinaryXml() throws Exception {
        XmlResourceParser parser = mContext.getResources().getXml(R.xml.calendar);
        parseXml(parser);
        parser.close();
    }
}
