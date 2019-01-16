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

import androidx.test.filters.LargeTest;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Tests for {@link LongSparseLongArray}.
 */
@LargeTest
public class LongSparseLongArrayTest extends TestCase {
    private static final String TAG = "LongSparseLongArrayTest";

    public void testSimplePut() throws Exception {
        final LongSparseLongArray array = new LongSparseLongArray(5);
        for (int i = 0; i < 48; i++) {
            final long value = 1 << i;
            array.put(value, value);
        }
        for (int i = 0; i < 48; i++) {
            final long value = 1 << i;
            assertEquals(value, array.get(value, -1));
            assertEquals(-1, array.get(-value, -1));
        }
    }

    public void testSimplePutBackwards() throws Exception {
        final LongSparseLongArray array = new LongSparseLongArray(5);
        for (int i = 47; i >= 0; i--) {
            final long value = 1 << i;
            array.put(value, value);
        }
        for (int i = 0; i < 48; i++) {
            final long value = 1 << i;
            assertEquals(value, array.get(value, -1));
            assertEquals(-1, array.get(-value, -1));
        }
    }

    public void testMiddleInsert() throws Exception {
        final LongSparseLongArray array = new LongSparseLongArray(5);
        for (int i = 0; i < 48; i++) {
            final long value = 1 << i;
            array.put(value, value);
        }
        final long special = (1 << 24) + 5;
        array.put(special, 1024);
        for (int i = 0; i < 48; i++) {
            final long value = 1 << i;
            assertEquals(value, array.get(value, -1));
            assertEquals(-1, array.get(-value, -1));
        }
        assertEquals(1024, array.get(special, -1));
    }

    public void testFuzz() throws Exception {
        final Random r = new Random();

        final HashMap<Long, Long> map = new HashMap<Long, Long>();
        final LongSparseLongArray array = new LongSparseLongArray(r.nextInt(128));

        for (int i = 0; i < 10240; i++) {
            if (r.nextBoolean()) {
                final long key = r.nextLong();
                final long value = r.nextLong();
                map.put(key, value);
                array.put(key, value);
            }
            if (r.nextBoolean() && map.size() > 0) {
                final int index = r.nextInt(map.size());
                final long key = getKeyAtIndex(map, index);
                map.remove(key);
                array.delete(key);
            }
        }

        Log.d(TAG, "verifying a map with " + map.size() + " entries");

        for (Map.Entry<Long, Long> e : map.entrySet()) {
            final long key = e.getKey();
            final long value = e.getValue();
            assertEquals(value, array.get(key));
        }
    }

    private static <E> E getKeyAtIndex(Map<E, ?> map, int index) {
        final Iterator<E> keys = map.keySet().iterator();
        for (int i = 0; i < index; i++) {
            keys.next();
        }
        return keys.next();
    }
}
