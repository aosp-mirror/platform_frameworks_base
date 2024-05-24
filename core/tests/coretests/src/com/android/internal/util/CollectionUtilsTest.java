/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class CollectionUtilsTest {
    private static final Object RED = new Object();
    private static final Object GREEN = new Object();
    private static final Object BLUE = new Object();

    @Test
    public void testList() throws Exception {
        List<Object> res = null;
        assertEquals(0, CollectionUtils.size(res));
        assertTrue(CollectionUtils.isEmpty(res));
        assertNull(CollectionUtils.firstOrNull(res));
        assertFalse(CollectionUtils.contains(res, RED));
        assertFalse(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.add(res, RED);
        assertEquals(1, CollectionUtils.size(res));
        assertFalse(CollectionUtils.isEmpty(res));
        assertEquals(RED, CollectionUtils.firstOrNull(res));
        assertTrue(CollectionUtils.contains(res, RED));
        assertFalse(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.add(res, GREEN);
        assertEquals(2, CollectionUtils.size(res));
        assertFalse(CollectionUtils.isEmpty(res));
        assertEquals(RED, CollectionUtils.firstOrNull(res));
        assertTrue(CollectionUtils.contains(res, RED));
        assertTrue(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.remove(res, GREEN);
        assertNotNull(res);
        res = CollectionUtils.remove(res, RED);
        assertNotNull(res);

        // Once drained we don't return to null
        assertEquals(0, CollectionUtils.size(res));
        assertTrue(CollectionUtils.isEmpty(res));
    }

    @Test
    public void testList_Dupes() throws Exception {
        List<Object> res = null;
        res = CollectionUtils.add(res, RED);
        res = CollectionUtils.add(res, RED);
        assertEquals(2, CollectionUtils.size(res));
    }

    @Test
    public void testSet() throws Exception {
        Set<Object> res = null;
        assertEquals(0, CollectionUtils.size(res));
        assertTrue(CollectionUtils.isEmpty(res));
        assertFalse(CollectionUtils.contains(res, RED));
        assertFalse(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.add(res, RED);
        assertEquals(1, CollectionUtils.size(res));
        assertFalse(CollectionUtils.isEmpty(res));
        assertTrue(CollectionUtils.contains(res, RED));
        assertFalse(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.add(res, GREEN);
        assertEquals(2, CollectionUtils.size(res));
        assertFalse(CollectionUtils.isEmpty(res));
        assertTrue(CollectionUtils.contains(res, RED));
        assertTrue(CollectionUtils.contains(res, GREEN));

        res = CollectionUtils.remove(res, GREEN);
        assertNotNull(res);
        res = CollectionUtils.remove(res, RED);
        assertNotNull(res);

        // Once drained we don't return to null
        assertEquals(0, CollectionUtils.size(res));
        assertTrue(CollectionUtils.isEmpty(res));
    }

    @Test
    public void testSet_Dupes() throws Exception {
        Set<Object> res = null;
        res = CollectionUtils.add(res, RED);
        res = CollectionUtils.add(res, RED);
        assertEquals(1, CollectionUtils.size(res));
    }

    @Test
    public void testEmptyIfNull() throws Exception {
        assertTrue(CollectionUtils.emptyIfNull((Set<Object>) null).isEmpty());
        assertTrue(CollectionUtils.emptyIfNull((List<Object>) null).isEmpty());
        assertTrue(CollectionUtils.emptyIfNull((Map<Object, Object>) null).isEmpty());
    }
}
