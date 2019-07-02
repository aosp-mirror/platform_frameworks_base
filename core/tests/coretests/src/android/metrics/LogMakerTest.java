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

package android.metrics;

import androidx.test.filters.LargeTest;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import junit.framework.TestCase;

@LargeTest
public class LogMakerTest extends TestCase {

    public void testSerialize() {
        LogMaker builder = new LogMaker(0);
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

        LogMaker builder = new LogMaker(category);
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
        LogMaker parsed = new LogMaker(out);

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
        LogMaker builder = new LogMaker(0);
        builder.setCounterBucket(100);
        assertEquals(100, builder.getCounterBucket());
        assertEquals(false, builder.isLongCounterBucket());
    }

    public void testLongBucket() {
        long longBucket = Long.MAX_VALUE;
        LogMaker builder = new LogMaker(0);
        builder.setCounterBucket(longBucket);
        assertEquals(longBucket, builder.getCounterBucket());
        assertEquals(true, builder.isLongCounterBucket());
    }

    public void testInvalidInputThrows() {
        LogMaker builder = new LogMaker(0);
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
        LogMaker builder = new LogMaker(0);
        builder.addTaggedData(1, "onetwothree");
        builder.addTaggedData(2, 123);
        builder.addTaggedData(3, 123L);
        builder.addTaggedData(4, 123.0F);
        builder.addTaggedData(5, null);
        Object[] out = builder.serialize();
        assertEquals("onetwothree", out[1]);
        assertEquals(123, out[3]);
        assertEquals(123L, out[5]);
        assertEquals(123.0F, out[7]);
    }

    public void testCategoryDefault() {
        LogMaker builder = new LogMaker(10);
        Object[] out = builder.serialize();
        assertEquals(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY, out[0]);
        assertEquals(10, out[1]);
    }

    public void testClearData() {
        LogMaker builder = new LogMaker(0);
        builder.addTaggedData(1, "onetwothree");
        builder.clearTaggedData(1);
        assertEquals(null, builder.getTaggedData(1));
    }

    public void testClearFieldLeavesOtherFieldsIntact() {
        LogMaker builder = new LogMaker(0);
        builder.setPackageName("package.name");
        builder.setSubtype(10);
        builder.clearPackageName();
        assertEquals(null, builder.getPackageName());
        assertEquals(10, builder.getSubtype());
    }

    public void testSetAndClearCategory() {
        LogMaker builder = new LogMaker(0);
        builder.setCategory(MetricsEvent.MAIN_SETTINGS);
        assertEquals(MetricsEvent.MAIN_SETTINGS, builder.getCategory());
        builder.clearCategory();
        assertEquals(MetricsEvent.VIEW_UNKNOWN, builder.getCategory());
    }

    public void testSetAndClearType() {
        LogMaker builder = new LogMaker(0);
        builder.setType(MetricsEvent.TYPE_OPEN);
        assertEquals(MetricsEvent.TYPE_OPEN, builder.getType());
        builder.clearType();
        assertEquals(MetricsEvent.TYPE_UNKNOWN, builder.getType());
    }

    public void testSetAndClearSubtype() {
        LogMaker builder = new LogMaker(0);
        builder.setSubtype(1);
        assertEquals(1, builder.getSubtype());
        builder.clearSubtype();
        assertEquals(0, builder.getSubtype());
    }

    public void testSetAndClearTimestamp() {
        LogMaker builder = new LogMaker(0);
        builder.setTimestamp(1);
        assertEquals(1, builder.getTimestamp());
        builder.clearTimestamp();
        assertEquals(0, builder.getTimestamp());
    }

    public void testSetAndClearPackageName() {
        LogMaker builder = new LogMaker(0);
        builder.setPackageName("package.name");
        assertEquals("package.name", builder.getPackageName());
        builder.clearPackageName();
        assertEquals(null, builder.getPackageName());
    }

    public void testSetAndClearPid() {
        LogMaker builder = new LogMaker(0);
        builder.setProcessId(1);
        assertEquals(1, builder.getProcessId());
        builder.clearProcessId();
        assertEquals(-1, builder.getProcessId());
    }

    public void testSetAndClearUid() {
        LogMaker builder = new LogMaker(0);
        builder.setUid(1);
        assertEquals(1, builder.getUid());
        builder.clearUid();
        assertEquals(-1, builder.getUid());
    }

    public void testGiantLogOmitted() {
        LogMaker badBuilder = new LogMaker(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            b.append("test, " + i);
        }
        badBuilder.addTaggedData(100, b.toString());
        assertTrue(badBuilder.serialize().length < LogMaker.MAX_SERIALIZED_SIZE);
    }

    public void testIdentityEquality() {
        LogMaker a = new LogMaker(0);
        a.addTaggedData(1, "onetwothree");
        a.addTaggedData(2, 123);
        a.addTaggedData(3, 123L);

        assertTrue("objects should be equal to themselves", a.isSubsetOf(a));
    }

    public void testExactEquality() {
        LogMaker a = new LogMaker(0);
        a.addTaggedData(1, "onetwothree");
        a.addTaggedData(2, 123);
        a.addTaggedData(3, 123L);
        LogMaker b = new LogMaker(0);
        b.addTaggedData(1, "onetwothree");
        b.addTaggedData(2, 123);
        b.addTaggedData(3, 123L);

        assertTrue("deep equality should be true", a.isSubsetOf(b));
        assertTrue("deep equality shoudl be true", b.isSubsetOf(a));
    }

    public void testSubsetEquality() {
        LogMaker a = new LogMaker(0);
        a.addTaggedData(1, "onetwothree");
        a.addTaggedData(2, 123);
        LogMaker b = new LogMaker(0);
        b.addTaggedData(1, "onetwothree");
        b.addTaggedData(2, 123);
        b.addTaggedData(3, 123L);

        assertTrue("a is a strict subset of b", a.isSubsetOf(b));
        assertTrue("b is not a strict subset of a", !b.isSubsetOf(a));
    }

    public void testInequality() {
        LogMaker a = new LogMaker(0);
        a.addTaggedData(1, "onetwofour");
        a.addTaggedData(2, 1234);
        LogMaker b = new LogMaker(0);
        b.addTaggedData(1, "onetwothree");
        b.addTaggedData(2, 123);
        b.addTaggedData(3, 123L);

        assertTrue("a is not a subset of b", !a.isSubsetOf(b));
        assertTrue("b is not a subset of a", !b.isSubsetOf(a));
    }

    public void testWildcardEquality() {
        LogMaker empty = new LogMaker(0);
        empty.clearTaggedData(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY);  //dirty trick
        LogMaker b = new LogMaker(0);
        b.addTaggedData(1, "onetwothree");
        b.addTaggedData(2, 123);
        b.addTaggedData(3, 123L);

        assertTrue("empty builder is a subset of anything", empty.isSubsetOf(b));
    }

    public void testNullEquality() {
        LogMaker a = new LogMaker(0);
        a.addTaggedData(1, "onetwofour");
        a.addTaggedData(2, 1234);

        assertTrue("a is not a subset of null", !a.isSubsetOf(null));
    }

    public void testMajorCategory() {
        LogMaker a = new LogMaker(1);
        LogMaker b = new LogMaker(2);
        assertFalse(a.isSubsetOf(b));
        assertFalse(b.isSubsetOf(a));
    }

    public void testConstructFromNull() {
        new LogMaker(null);
        // no promises, just don't throw
    }

    public void testConstructFromNullKey() {
        Object[] items = new Object[2];
        items[0] = null;
        items[1] = "foo";
        new LogMaker(items);
        // no promises, just don't throw
    }

    public void testConstructFromNullField() {
        Object[] items = new Object[2];
        items[0] = 10;
        items[1] = null;
        new LogMaker(items);
        // no promises, just don't throw
    }

    public void testConstructFromTruncatedArray() {
        Object[] items = new Object[1];
        items[0] = 10;
        new LogMaker(items);
        // no promises, just don't throw
    }
}
