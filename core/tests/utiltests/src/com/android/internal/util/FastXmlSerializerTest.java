/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.Xml;

import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link FastXmlSerializer}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = Xml.class)
public class FastXmlSerializerTest {
    private static final String TAG = "FastXmlSerializerTest";

    private static final boolean ENABLE_DUMP = false; // DO NOT SUBMIT WITH TRUE.

    private static final String ROOT_TAG = "root";
    private static final String ATTR = "attr";

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testEmptyText() throws Exception {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        final XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, "utf-8");
        out.startDocument(null, true);
        out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        out.startTag(null, "string");
        out.attribute(null, "name", "meow");
        out.text("");
        out.endTag(null, "string");

        out.endDocument();

        assertEquals("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<string name=\"meow\"></string>\n", stream.toString());
    }

    private boolean checkPreserved(String description, String str) {
        boolean ok = true;
        byte[] data;
        try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(baos, StandardCharsets.UTF_16.name());
            out.startDocument(null, true);

            out.startTag(null, ROOT_TAG);
            out.attribute(null, ATTR, str);
            out.text(str);
            out.endTag(null, ROOT_TAG);

            out.endDocument();
            baos.flush();
            data = baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Unable to serialize: " + description, e);
            return false;
        }

        if (ENABLE_DUMP) {
            Log.d(TAG, "Dump:");
            Log.d(TAG, new String(data));
        }

        try (final ByteArrayInputStream baos = new ByteArrayInputStream(data)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(baos, StandardCharsets.UTF_16.name());

            int type;
            String tag = null;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    tag = parser.getName();
                    if (ROOT_TAG.equals(tag)) {
                        String read = parser.getAttributeValue(null, ATTR);
                        if (!str.equals(read)) {
                            Log.e(TAG, "Attribute not preserved: " + description
                                    + " input=\"" + str + "\", but read=\"" + read + "\"");
                            ok = false;
                        }
                    }
                }
                if (type == XmlPullParser.TEXT && ROOT_TAG.equals(tag)) {
                    String read = parser.getText();
                    if (!str.equals(parser.getText())) {
                        Log.e(TAG, "Text not preserved: " + description
                                + " input=\"" + str + "\", but read=\"" + read + "\"");
                        ok = false;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse: " + description, e);
            return false;
        }
        return ok;
    }

    private boolean check(String description, String str) throws Exception {
        boolean ok = false;
        ok |= checkPreserved(description, str);
        ok |= checkPreserved(description + " wrapped with spaces" ,"  " + str + "  ");
        return ok;
    }

    @Test
    @LargeTest
    public void testAllCharacters() throws Exception {
        boolean ok = true;
        for (int i = 0; i < 0xffff; i++) {
            if (0xd800 <= i && i <= 0xdfff) {
                // Surrogate pair characters.
                continue;
            }
            ok &= check("char: " + i, String.valueOf((char) i));
        }
        // Dangling surrogate pairs. We can't preserve them.
        assertFalse(check("+ud800", "\ud800"));
        assertFalse(check("+udc00", "\udc00"));

        for (int i = 0xd800; i < 0xdc00; i ++) {
            for (int j = 0xdc00; j < 0xe000; j++) {
                ok &= check("char: " + i, String.valueOf((char) i) + String.valueOf((char) j));
            }
        }
        assertTrue("Some tests failed.  See logcat for details.", ok);
    }
}
