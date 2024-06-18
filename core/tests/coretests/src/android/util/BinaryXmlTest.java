/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util;

import static android.util.XmlTest.assertNext;
import static android.util.XmlTest.buildPersistableBundle;
import static android.util.XmlTest.doPersistableBundleRead;
import static android.util.XmlTest.doPersistableBundleWrite;
import static android.util.XmlTest.doVerifyRead;
import static android.util.XmlTest.doVerifyWrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.os.PersistableBundle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class BinaryXmlTest {
    private static final int MAX_UNSIGNED_SHORT = 65_535;

    /**
     * Verify that we can write and read large numbers of interned
     * {@link String} values.
     */
    @Test
    public void testLargeInterned_Binary() throws Exception {
        // We're okay with the tag itself being interned
        final int count = (1 << 16) - 2;

        final TypedXmlSerializer out = Xml.newBinarySerializer();
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        out.setOutput(os, StandardCharsets.UTF_8.name());
        out.startTag(null, "tag");
        for (int i = 0; i < count; i++) {
            out.attribute(null, "name" + i, "value");
        }
        out.endTag(null, "tag");
        out.flush();

        final TypedXmlPullParser in = Xml.newBinaryPullParser();
        final ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
        in.setInput(is, StandardCharsets.UTF_8.name());
        assertNext(in, START_TAG, "tag");
        assertEquals(count, in.getAttributeCount());
    }

    @Test
    public void testTranscode_FastToBinary() throws Exception {
        doTranscode(Xml.newFastSerializer(), Xml.newFastPullParser(),
                Xml.newBinarySerializer(), Xml.newBinaryPullParser());
    }

    @Test
    public void testTranscode_BinaryToFast() throws Exception {
        doTranscode(Xml.newBinarySerializer(), Xml.newBinaryPullParser(),
                Xml.newFastSerializer(), Xml.newFastPullParser());
    }

    /**
     * Verify that a complex {@link PersistableBundle} can be transcoded using
     * the two given formats with the original structure intact.
     */
    private static void doTranscode(TypedXmlSerializer firstOut, TypedXmlPullParser firstIn,
            TypedXmlSerializer secondOut, TypedXmlPullParser secondIn) throws Exception {
        final PersistableBundle expected = buildPersistableBundle();
        final byte[] firstRaw = doPersistableBundleWrite(firstOut, expected);

        // Perform actual transcoding between the two formats
        final ByteArrayInputStream is = new ByteArrayInputStream(firstRaw);
        firstIn.setInput(is, StandardCharsets.UTF_8.name());
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        secondOut.setOutput(os, StandardCharsets.UTF_8.name());
        Xml.copy(firstIn, secondOut);

        // Yes, this string-based check is fragile, but kindofEquals() is broken
        // when working with nested objects and arrays
        final PersistableBundle actual = doPersistableBundleRead(secondIn, os.toByteArray());
        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    public void testResolve_File() throws Exception {
        {
            final File file = File.createTempFile("fast", ".xml");
            try (OutputStream os = new FileOutputStream(file)) {
                TypedXmlSerializer xml = Xml.newFastSerializer();
                xml.setOutput(os, StandardCharsets.UTF_8.name());
                doVerifyWrite(xml);
            }
            try (InputStream is = new FileInputStream(file)) {
                doVerifyRead(Xml.resolvePullParser(is));
            }
        }
        {
            final File file = File.createTempFile("binary", ".xml");
            try (OutputStream os = new FileOutputStream(file)) {
                TypedXmlSerializer xml = Xml.newBinarySerializer();
                xml.setOutput(os, StandardCharsets.UTF_8.name());
                doVerifyWrite(xml);
            }
            try (InputStream is = new FileInputStream(file)) {
                doVerifyRead(Xml.resolvePullParser(is));
            }
        }
    }

    @Test
    public void testResolve_Memory() throws Exception {
        {
            final byte[] data;
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                TypedXmlSerializer xml = Xml.newFastSerializer();
                xml.setOutput(os, StandardCharsets.UTF_8.name());
                doVerifyWrite(xml);
                data = os.toByteArray();
            }
            try (InputStream is = new ByteArrayInputStream(data) {
                @Override
                public boolean markSupported() {
                    return false;
                }
            }) {
                doVerifyRead(Xml.resolvePullParser(is));
            }
        }
        {
            final byte[] data;
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                TypedXmlSerializer xml = Xml.newBinarySerializer();
                xml.setOutput(os, StandardCharsets.UTF_8.name());
                doVerifyWrite(xml);
                data = os.toByteArray();
            }
            try (InputStream is = new ByteArrayInputStream(data) {
                @Override
                public boolean markSupported() {
                    return false;
                }
            }) {
                doVerifyRead(Xml.resolvePullParser(is));
            }
        }
    }

    @Test
    public void testAttributeBytes_BinaryDataOverflow() throws Exception {
        final TypedXmlSerializer out = Xml.newBinarySerializer();
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        out.setOutput(os, StandardCharsets.UTF_8.name());

        final byte[] testBytes = new byte[MAX_UNSIGNED_SHORT + 1];
        assertThrows(IOException.class,
                () -> out.attributeBytesHex(/* namespace */ null, /* name */ "attributeBytesHex",
                        testBytes));

        assertThrows(IOException.class,
                () -> out.attributeBytesBase64(/* namespace */ null, /* name */
                        "attributeBytesBase64", testBytes));
    }

    @Test
    public void testAttributeBytesHex_MaximumBinaryData() throws Exception {
        final TypedXmlSerializer out = Xml.newBinarySerializer();
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        out.setOutput(os, StandardCharsets.UTF_8.name());

        final byte[] testBytes = new byte[MAX_UNSIGNED_SHORT];
        try {
            out.attributeBytesHex(/* namespace */ null, /* name */ "attributeBytesHex", testBytes);
        } catch (Exception e) {
            fail("testAttributeBytesHex fails with exception: " + e.toString());
        }
    }

    @Test
    public void testAttributeBytesBase64_MaximumBinaryData() throws Exception {
        final TypedXmlSerializer out = Xml.newBinarySerializer();
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        out.setOutput(os, StandardCharsets.UTF_8.name());

        final byte[] testBytes = new byte[MAX_UNSIGNED_SHORT];
        try {
            out.attributeBytesBase64(/* namespace */ null, /* name */ "attributeBytesBase64",
                    testBytes);
        } catch (Exception e) {
            fail("testAttributeBytesBase64 fails with exception: " + e.toString());
        }
    }
}
