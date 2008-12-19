/*
 * Copyright (C) 2007 The Android Open Source Project
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

import com.google.android.collect.Lists;

import junit.framework.Assert;
import junit.framework.TestCase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * tests for {@link EventLog}
 */

public class EventLogTest extends TestCase {
    private static final int TEST_TAG = 42;

    public void testIllegalListTypesThrowException() throws Exception {
        try {
            EventLog.writeEvent(TEST_TAG, new EventLog.List(new Object()));
            fail("Can't create List with any old Object");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            EventLog.writeEvent(TEST_TAG, new EventLog.List((byte) 1));
            fail("Can't create List with any old byte");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    void assertIntInByteArrayEquals(int expected, byte[] buf, int pos) {
        ByteBuffer computedBuf = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        int computed = computedBuf.getInt(pos);
        Assert.assertEquals(expected, computed);
    }

    void assertLongInByteArrayEquals(long expected, byte[] buf, int pos) {
        ByteBuffer computedBuf = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        long computed = computedBuf.getLong(pos);
        Assert.assertEquals(expected, computed);
    }

    void assertStringInByteArrayEquals(String expected, byte[] buf, int pos) {
        byte[] expectedBytes = expected.getBytes();
        Assert.assertTrue(expectedBytes.length <= buf.length - pos);
        for (byte expectedByte : expectedBytes) {
            Assert.assertEquals(expectedByte, buf[pos++]);
        }
    }
}
