/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.logging;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import junit.framework.TestCase;

public class LogBuilderTest extends TestCase {

    public void testSerialize() {
        LogBuilder builder = new LogBuilder(0);
        builder.addTaggedData(1, "one");
        builder.addTaggedData(2, "two");
        Object[] out = builder.serialize();
        assertEquals(1, out[0]);
        assertEquals("one", out[1]);
        assertEquals(2, out[2]);
        assertEquals("two", out[3]);
    }

    public void testSerializeDeserialize() {
        int category = 10;
        int type = 11;
        int subtype = 12;
        long timestamp = 1484669007890L;
        String packageName = "com.foo.bar";
        String counterName = "sheep";
        int bucket = 13;
        int value = 14;

        LogBuilder builder = new LogBuilder(category);
        builder.setType(type);
        builder.setSubtype(subtype);
        builder.setTimestamp(timestamp);
        builder.setPackageName(packageName);
        builder.setCounterName(counterName);
        builder.setCounterBucket(bucket);
        builder.setCounterValue(value);
        builder.addTaggedData(1, "one");
        builder.addTaggedData(2, "two");

        Object[] out = builder.serialize();
        LogBuilder parsed = new LogBuilder(out);

        assertEquals(category, parsed.getCategory());
        assertEquals(type, parsed.getType());
        assertEquals(subtype, parsed.getSubtype());
        assertEquals(timestamp, parsed.getTimestamp());
        assertEquals(packageName, parsed.getPackageName());
        assertEquals(counterName, parsed.getCounterName());
        assertEquals(bucket, parsed.getCounterBucket());
        assertEquals(value, parsed.getCounterValue());
        assertEquals("one", parsed.getTaggedData(1));
        assertEquals("two", parsed.getTaggedData(2));
    }

    public void testIntBucket() {
        LogBuilder builder = new LogBuilder(0);
        builder.setCounterBucket(100);
        assertEquals(100, builder.getCounterBucket());
        assertEquals(false, builder.isLongCounterBucket());
    }

    public void testLongBucket() {
        long longBucket = Long.MAX_VALUE;
        LogBuilder builder = new LogBuilder(0);
        builder.setCounterBucket(longBucket);
        assertEquals(longBucket, builder.getCounterBucket());
        assertEquals(true, builder.isLongCounterBucket());
    }

    public void testInvalidInputThrows() {
        LogBuilder builder = new LogBuilder(0);
        boolean threw = false;
        try {
            builder.addTaggedData(0, new Object());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
        assertEquals(2, builder.serialize().length);
    }

    public void testValidInputTypes() {
        LogBuilder builder = new LogBuilder(0);
        builder.addTaggedData(1, "onetwothree");
        builder.addTaggedData(2, 123);
        builder.addTaggedData(3, 123L);
        builder.addTaggedData(4, 123.0F);
        Object[] out = builder.serialize();
        assertEquals("onetwothree", out[1]);
        assertEquals(123, out[3]);
        assertEquals(123L, out[5]);
        assertEquals(123.0F, out[7]);
    }

    public void testCategoryDefault() {
        LogBuilder builder = new LogBuilder(10);
        Object[] out = builder.serialize();
        assertEquals(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY, out[0]);
        assertEquals(10, out[1]);
    }

    public void testGiantLogOmitted() {
        LogBuilder badBuilder = new LogBuilder(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            b.append("test, " + i);
        }
        badBuilder.addTaggedData(100, b.toString());
        assertTrue(badBuilder.serialize().length < LogBuilder.MAX_SERIALIZED_SIZE);
    }

}
