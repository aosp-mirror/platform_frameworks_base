/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.util;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class ArrayUtilsTest extends TestCase {

    @SmallTest
    public void testUnstableRemoveIf() throws Exception {
        java.util.function.Predicate<Object> isNull = new java.util.function.Predicate<Object>() {
            @Override
            public boolean test(Object o) {
                return o == null;
            }
        };

        final Object a = new Object();
        final Object b = new Object();
        final Object c = new Object();

        ArrayList<Object> collection = null;
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));

        collection = new ArrayList<>();
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));

        collection = new ArrayList<>(Collections.singletonList(a));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Collections.singletonList(null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());

        collection = new ArrayList<>(Arrays.asList(a, b));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, a));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());

        collection = new ArrayList<>(Arrays.asList(a, b, c));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(3, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));
        assertTrue(collection.contains(c));

        collection = new ArrayList<>(Arrays.asList(a, b, null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null, b));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(null, a, b));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null, a));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, a, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null, null));
        assertEquals(3, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenRangeInsideArray() {
        ArrayUtils.throwsIfOutOfBounds(10, 2, 6);
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenRangeIsWholeArray() {
        ArrayUtils.throwsIfOutOfBounds(10, 0, 10);
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyRangeAtStart() {
        ArrayUtils.throwsIfOutOfBounds(10, 0, 0);
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyRangeAtEnd() {
        ArrayUtils.throwsIfOutOfBounds(10, 10, 0);
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyArray() {
        ArrayUtils.throwsIfOutOfBounds(0, 0, 0);
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeStartNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, -1, 5);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenCountNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 5, -1);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeStartTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 11, 0);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeEndTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 5, 6);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenLengthNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(-1, 0, 0);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenOverflowRangeEndTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }
}
