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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import junit.framework.TestCase;

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
}
