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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.ravenwood.RavenwoodRule;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Rule;
import org.junit.Test;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SystemPropertiesTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setSystemPropertyMutable(KEY, null)
            .setSystemPropertyMutable(UNSET_KEY, null)
            .setSystemPropertyMutable(PERSIST_KEY, null)
            .build();

    private static final String KEY = "sys.testkey";
    private static final String UNSET_KEY = "Aiw7woh6ie4toh7W";
    private static final String PERSIST_KEY = "persist.sys.testkey";

    @Test
    @SmallTest
    public void testStressPersistPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(PERSIST_KEY, Long.toString(i));
            long ret = SystemProperties.getLong(PERSIST_KEY, -1);
            assertEquals(i, ret);
        }
    }

    @Test
    @SmallTest
    public void testStressMemoryPropertyConsistency() throws Exception {
        for (int i = 0; i < 100; ++i) {
            SystemProperties.set(KEY, Long.toString(i));
            long ret = SystemProperties.getLong(KEY, -1);
            assertEquals(i, ret);
        }
    }

    @Test
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

    @Test
    @SmallTest
    public void testHandle() throws Exception {
        String value;
        SystemProperties.Handle handle = SystemProperties.find("doesnotexist_2341431");
        assertNull(handle);
        SystemProperties.set(KEY, "abc");
        handle = SystemProperties.find(KEY);
        assertNotNull(handle);
        value = handle.get();
        assertEquals("abc", value);
        SystemProperties.set(KEY, "blarg");
        value = handle.get();
        assertEquals("blarg", value);
        SystemProperties.set(KEY, "1");
        assertEquals(1, handle.getInt(-1));
        assertEquals(1, handle.getLong(-1));
        assertEquals(true, handle.getBoolean(false));
        SystemProperties.set(KEY, "");
        assertEquals(12345, handle.getInt(12345));
    }

    @Test
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

    @Test
    @SmallTest
    public void testUnset() throws Exception {
        assertEquals("abc", SystemProperties.get(UNSET_KEY, "abc"));
        assertEquals(true, SystemProperties.getBoolean(UNSET_KEY, true));
        assertEquals(false, SystemProperties.getBoolean(UNSET_KEY, false));
        assertEquals(5, SystemProperties.getInt(UNSET_KEY, 5));
        assertEquals(-10, SystemProperties.getLong(UNSET_KEY, -10));
    }

    @Test
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

    @Test
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

    @Test
    @SmallTest
    public void testDigestOf() {
        final String empty = SystemProperties.digestOf();
        final String finger = SystemProperties.digestOf("ro.build.fingerprint");
        final String fingerBrand = SystemProperties.digestOf(
                "ro.build.fingerprint", "ro.product.brand");
        final String brandFinger = SystemProperties.digestOf(
                "ro.product.brand", "ro.build.fingerprint");

        // Shouldn't change over time
        assertTrue(Objects.equals(finger, SystemProperties.digestOf("ro.build.fingerprint")));

        // Different properties means different results
        assertFalse(Objects.equals(empty, finger));
        assertFalse(Objects.equals(empty, fingerBrand));
        assertFalse(Objects.equals(finger, fingerBrand));

        // Same properties means same result
        assertTrue(Objects.equals(fingerBrand, brandFinger));
    }
}
