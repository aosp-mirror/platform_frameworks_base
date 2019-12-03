/**
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

import static org.junit.Assert.assertArrayEquals;

import android.util.Xml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

public class XmlUtilsTest extends TestCase {

    // https://code.google.com/p/android/issues/detail?id=63717
    public void testMapWithNullKeys() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(null, "nullValue");
        map.put("foo", "fooValue");
        XmlUtils.writeMapXml(map, baos);

        InputStream mapInput = new ByteArrayInputStream(baos.toByteArray());
        HashMap<String, ?> deserialized = XmlUtils.readMapXml(mapInput);
        assertEquals("nullValue", deserialized.get(null));
        assertEquals("fooValue", deserialized.get("foo"));
    }

    public void testreadWriteXmlByteArrayValue() throws Exception {
        byte[] testByteArray = {0x1 , 0xa, 0xb, 0x9, 0x34, (byte) 0xaa, (byte) 0xba, (byte) 0x99};

        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        XmlSerializer serializer = new FastXmlSerializer();
        serializer.setOutput(baos, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        XmlUtils.writeValueXml(testByteArray,  "testByteArray", serializer);
        serializer.endDocument();

        InputStream bais = new ByteArrayInputStream(baos.toByteArray());
        XmlPullParser pullParser = Xml.newPullParser();
        pullParser.setInput(bais, StandardCharsets.UTF_8.name());
        String[] name = new String[1];
        byte[] testByteArrayDeserialized = (byte[]) XmlUtils.readValueXml(pullParser, name);
        assertEquals("testByteArray", name[0]);
        assertArrayEquals(testByteArray, testByteArrayDeserialized);
    }
}
