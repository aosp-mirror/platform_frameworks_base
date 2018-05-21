/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.os;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import android.os.SystemProperties;
import android.test.suitebuilder.annotation.SmallTest;

public class SystemPropertiesTest extends TestCase {
    private static final String KEY = "sys.testkey";
    private static final String PERSIST_KEY = "persist.sys.testkey";

    @SmallTest
    public void testStressPersistPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(PERSIST_KEY, Long.toString(i));
            long ret = SystemProperties.getLong(PERSIST_KEY, -1);
            assertEquals(i, ret);
        }
    }

    @SmallTest
    public void testStressMemoryPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(KEY, Long.toString(i));
            long ret = SystemProperties.getLong(KEY, -1);
            assertEquals(i, ret);
        }
    }

    @SmallTest
    public void testProperties() throws Exception {
        String value;

        SystemProperties.set(KEY, "");
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        // null default value is the same as "".
        SystemProperties.set(KEY, null);
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        SystemProperties.set(KEY, "SA");
        value = SystemProperties.get(KEY, "default");
        assertEquals("SA", value);

        value = SystemProperties.get(KEY);
        assertEquals("SA", value);

        SystemProperties.set(KEY, "");
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        // null value is the same as "".
        SystemProperties.set(KEY, "SA");
        SystemProperties.set(KEY, null);
        value = SystemProperties.get(KEY, "default");
        assertEquals("default", value);

        value = SystemProperties.get(KEY);
        assertEquals("", value);
    }

    private static void testInt(String setVal, int defValue, int expected) {
      SystemProperties.set(KEY, setVal);
      int value = SystemProperties.getInt(KEY, defValue);
      assertEquals(expected, value);
    }

    private static void testLong(String setVal, long defValue, long expected) {
      SystemProperties.set(KEY, setVal);
      long value = SystemProperties.getLong(KEY, defValue);
      assertEquals(expected, value);
    }

    @SmallTest
    public void testIntegralProperties() throws Exception {
        testInt("", 123, 123);
        testInt("", 0, 0);
        testInt("", -123, -123);

        testInt("123", 124, 123);
        testInt("0", 124, 0);
        testInt("-123", 124, -123);

        testLong("", 3147483647L, 3147483647L);
        testLong("", 0, 0);
        testLong("", -3147483647L, -3147483647L);

        testLong("3147483647", 124, 3147483647L);
        testLong("0", 124, 0);
        testLong("-3147483647", 124, -3147483647L);
    }

    @SmallTest
    @SuppressWarnings("null")
    public void testNullKey() throws Exception {
        try {
            SystemProperties.get(null);
            fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
        }

        try {
            SystemProperties.get(null, "default");
            fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
        }

        try {
            SystemProperties.set(null, "value");
            fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
        }

        try {
            SystemProperties.getInt(null, 0);
            fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
        }

        try {
            SystemProperties.getLong(null, 0);
            fail("Expected NullPointerException");
        } catch (NullPointerException npe) {
        }
    }

    @SmallTest
    public void testCallbacks() {
        // Latches are not really necessary, but are easy to use.
        final CountDownLatch wait1 = new CountDownLatch(1);
        final CountDownLatch wait2 = new CountDownLatch(1);

        Runnable r1 = new Runnable() {
            boolean done = false;
            @Override
            public void run() {
                if (done) {
                    return;
                }
                done = true;

                wait1.countDown();
                throw new RuntimeException("test");
            }
        };

        Runnable r2 = new Runnable() {
            @Override
            public void run() {
                wait2.countDown();
            }
        };

        SystemProperties.addChangeCallback(r1);
        SystemProperties.addChangeCallback(r2);

        SystemProperties.reportSyspropChanged();

        try {
            assertTrue(wait1.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("InterruptedException");
        }
        try {
            assertTrue(wait2.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("InterruptedException");
        }
    }
}
