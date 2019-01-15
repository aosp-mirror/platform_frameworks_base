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

import static com.android.internal.util.ArrayUtils.concatElements;

import static org.junit.Assert.assertArrayEquals;

import junit.framework.TestCase;

/**
 * Tests for {@link ArrayUtils}
 */
public class ArrayUtilsTest extends TestCase {
    public void testContains() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();
        final Object D = new Object();

        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, A));
        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, B));
        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, C));
        assertTrue(ArrayUtils.contains(new Object[] { A, null, C }, null));

        assertFalse(ArrayUtils.contains(new Object[] { A, B, C }, null));
        assertFalse(ArrayUtils.contains(new Object[] { }, null));
        assertFalse(ArrayUtils.contains(new Object[] { null }, A));
    }

    public void testIndexOf() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();
        final Object D = new Object();

        assertEquals(0, ArrayUtils.indexOf(new Object[] { A, B, C }, A));
        assertEquals(1, ArrayUtils.indexOf(new Object[] { A, B, C }, B));
        assertEquals(2, ArrayUtils.indexOf(new Object[] { A, B, C }, C));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { A, B, C }, D));

        assertEquals(-1, ArrayUtils.indexOf(new Object[] { A, B, C }, null));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { }, A));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { }, null));

        assertEquals(0, ArrayUtils.indexOf(new Object[] { null, null }, null));
        assertEquals(1, ArrayUtils.indexOf(new Object[] { A, null, B }, null));
        assertEquals(2, ArrayUtils.indexOf(new Object[] { A, null, B }, B));
    }

    public void testContainsAll() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();

        assertTrue(ArrayUtils.containsAll(new Object[] { C, B, A }, new Object[] { A, B, C }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A, B }, new Object[] { A }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A }, new Object[] { A }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { null }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { null }, new Object[] { null }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A, null, C }, new Object[] { C, null }));

        assertFalse(ArrayUtils.containsAll(new Object[] { }, new Object[] { A }));
        assertFalse(ArrayUtils.containsAll(new Object[] { B }, new Object[] { A }));
        assertFalse(ArrayUtils.containsAll(new Object[] { }, new Object[] { null }));
        assertFalse(ArrayUtils.containsAll(new Object[] { A }, new Object[] { null }));
    }

    public void testContainsInt() throws Exception {
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 1));
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 2));
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 3));

        assertFalse(ArrayUtils.contains(new int[] { 1, 2, 3 }, 0));
        assertFalse(ArrayUtils.contains(new int[] { 1, 2, 3 }, 4));
        assertFalse(ArrayUtils.contains(new int[] { }, 2));
    }

    public void testAppendInt() throws Exception {
        assertArrayEquals(new int[] { 1 },
                ArrayUtils.appendInt(null, 1));
        assertArrayEquals(new int[] { 1 },
                ArrayUtils.appendInt(new int[] { }, 1));
        assertArrayEquals(new int[] { 1, 2 },
                ArrayUtils.appendInt(new int[] { 1 }, 2));
        assertArrayEquals(new int[] { 1, 2 },
                ArrayUtils.appendInt(new int[] { 1, 2 }, 1));
    }

    public void testRemoveInt() throws Exception {
        assertNull(ArrayUtils.removeInt(null, 1));
        assertArrayEquals(new int[] { },
                ArrayUtils.removeInt(new int[] { }, 1));
        assertArrayEquals(new int[] { 1, 2, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 4));
        assertArrayEquals(new int[] { 2, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 1));
        assertArrayEquals(new int[] { 1, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 2));
        assertArrayEquals(new int[] { 1, 2, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 3));
        assertArrayEquals(new int[] { 2, 3, 1 },
                ArrayUtils.removeInt(new int[] { 1, 2, 3, 1 }, 1));
    }

    public void testContainsLong() throws Exception {
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 1));
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 2));
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 3));

        assertFalse(ArrayUtils.contains(new long[] { 1, 2, 3 }, 0));
        assertFalse(ArrayUtils.contains(new long[] { 1, 2, 3 }, 4));
        assertFalse(ArrayUtils.contains(new long[] { }, 2));
    }

    public void testAppendLong() throws Exception {
        assertArrayEquals(new long[] { 1 },
                ArrayUtils.appendLong(null, 1));
        assertArrayEquals(new long[] { 1 },
                ArrayUtils.appendLong(new long[] { }, 1));
        assertArrayEquals(new long[] { 1, 2 },
                ArrayUtils.appendLong(new long[] { 1 }, 2));
        assertArrayEquals(new long[] { 1, 2 },
                ArrayUtils.appendLong(new long[] { 1, 2 }, 1));
    }

    public void testRemoveLong() throws Exception {
        assertNull(ArrayUtils.removeLong(null, 1));
        assertArrayEquals(new long[] { },
                ArrayUtils.removeLong(new long[] { }, 1));
        assertArrayEquals(new long[] { 1, 2, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 4));
        assertArrayEquals(new long[] { 2, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 1));
        assertArrayEquals(new long[] { 1, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 2));
        assertArrayEquals(new long[] { 1, 2, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 3));
        assertArrayEquals(new long[] { 2, 3, 1 },
                ArrayUtils.removeLong(new long[] { 1, 2, 3, 1 }, 1));
    }

    public void testConcatEmpty() throws Exception {
        assertArrayEquals(new Long[] {},
                concatElements(Long.class, null, null));
        assertArrayEquals(new Long[] {},
                concatElements(Long.class, new Long[] {}, null));
        assertArrayEquals(new Long[] {},
                concatElements(Long.class, null, new Long[] {}));
        assertArrayEquals(new Long[] {},
                concatElements(Long.class, new Long[] {}, new Long[] {}));
    }

    public void testconcatElements() throws Exception {
        assertArrayEquals(new Long[] { 1L },
                concatElements(Long.class, new Long[] { 1L }, new Long[] {}));
        assertArrayEquals(new Long[] { 1L },
                concatElements(Long.class, new Long[] {}, new Long[] { 1L }));
        assertArrayEquals(new Long[] { 1L, 2L },
                concatElements(Long.class, new Long[] { 1L }, new Long[] { 2L }));
        assertArrayEquals(new Long[] { 1L, 2L, 3L, 4L },
                concatElements(Long.class, new Long[] { 1L, 2L }, new Long[] { 3L, 4L }));
    }
}
