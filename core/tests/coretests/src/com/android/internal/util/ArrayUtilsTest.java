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

import android.test.suitebuilder.annotation.SmallTest;
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
}
