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

package android.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * A simple class that tests dependencies to java standard tools from the
 * Network stack. These tests are not meant to be comprehensive tests of
 * the relevant APIs : such tests belong in the relevant test suite for
 * these dependencies. Instead, this just makes sure coverage is present
 * by calling the methods in the exact way (or a representative way of how)
 * they are called in the network stack.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DependenciesTest {
    // Used to in ipmemorystore's RegularMaintenanceJobService to convert
    // 24 hours into seconds
    @Test
    public void testTimeUnit() {
        final int hours = 24;
        final long inSeconds = TimeUnit.HOURS.toMillis(hours);
        assertEquals(inSeconds, hours * 60 * 60 * 1000);
    }

    private byte[] makeTrivialArray(final int size) {
        final byte[] src = new byte[size];
        for (int i = 0; i < size; ++i) {
            src[i] = (byte) i;
        }
        return src;
    }

    // Used in ApfFilter to find an IP address from a byte array
    @Test
    public void testArrays() {
        final int size = 128;
        final byte[] src = makeTrivialArray(size);

        // Test copy
        final int copySize = 16;
        final int offset = 24;
        final byte[] expected = new byte[copySize];
        for (int i = 0; i < copySize; ++i) {
            expected[i] = (byte) (offset + i);
        }

        final byte[] copy = Arrays.copyOfRange(src, offset, offset + copySize);
        assertArrayEquals(expected, copy);
        assertArrayEquals(new byte[0], Arrays.copyOfRange(src, size, size));
    }

    // Used mainly in the Dhcp code
    @Test
    public void testCopyOf() {
        final byte[] src = makeTrivialArray(128);
        final byte[] copy = Arrays.copyOf(src, src.length);
        assertArrayEquals(src, copy);
        assertFalse(src == copy);

        assertArrayEquals(new byte[0], Arrays.copyOf(src, 0));

        final int excess = 16;
        final byte[] biggerCopy = Arrays.copyOf(src, src.length + excess);
        for (int i = src.length; i < src.length + excess; ++i) {
            assertEquals(0, biggerCopy[i]);
        }
        for (int i = src.length - 1; i >= 0; --i) {
            assertEquals(src[i], biggerCopy[i]);
        }
    }

    // Used mainly in DnsUtils but also various other places
    @Test
    public void testAsList() {
        final int size = 24;
        final Object[] src = new Object[size];
        final ArrayList<Object> expected = new ArrayList<>(size);
        for (int i = 0; i < size; ++i) {
            final Object o = new Object();
            src[i] = o;
            expected.add(o);
        }
        assertEquals(expected, Arrays.asList(src));
    }
}
