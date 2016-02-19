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

import junit.framework.TestCase;

import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;

/**
 * Tests for {@link FastXmlSerializer}
 */
public class FastXmlSerializerTest extends TestCase {
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
                + "<string name=\"meow\"></string>", stream.toString());
    }
}
