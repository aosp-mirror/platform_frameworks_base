/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.shared.recents.model;


import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNull;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@SmallTest
@RunWith(MockitoJUnitRunner.class)
public class TaskKeyLruCacheTest extends SysuiTestCase {
    private static int sCacheSize = 3;
    private static int sIdTask1 = 1;
    private static int sIdTask2 = 2;
    private static int sIdTask3 = 3;
    private static int sIdUser1 = 1;

    TaskKeyLruCache.EvictionCallback mEvictionCallback;

    TaskKeyLruCache<Integer> mCache;
    private Task.TaskKey mKey1;
    private Task.TaskKey mKey2;
    private Task.TaskKey mKey3;

    @Before
    public void setup() {
        mEvictionCallback = mock(TaskKeyLruCache.EvictionCallback.class);
        mCache = new TaskKeyLruCache<>(sCacheSize, mEvictionCallback);

        mKey1 = new Task.TaskKey(sIdTask1, 0, null, null, sIdUser1, System.currentTimeMillis());
        mKey2 = new Task.TaskKey(sIdTask2, 0, null, null, sIdUser1, System.currentTimeMillis());
        mKey3 = new Task.TaskKey(sIdTask3, 0, null, null, sIdUser1, System.currentTimeMillis());
    }

    @Test
    public void addSingleItem_get_success() {
        mCache.put(mKey1, 1);

        assertEquals(1, (int) mCache.get(mKey1));
    }

    @Test
    public void addSingleItem_getUninsertedItem_returnsNull() {
        mCache.put(mKey1, 1);

        assertNull(mCache.get(mKey2));
    }

    @Test
    public void emptyCache_get_returnsNull() {
        assertNull(mCache.get(mKey1));
    }

    @Test
    public void updateItem_get_returnsSecond() {
        mCache.put(mKey1, 1);
        mCache.put(mKey1, 2);

        assertEquals(2, (int) mCache.get(mKey1));
        assertEquals(1, mCache.mKeys.size());
    }

    @Test
    public void fillCache_put_evictsOldest() {
        mCache.put(mKey1, 1);
        mCache.put(mKey2, 2);
        mCache.put(mKey3, 3);
        Task.TaskKey key4 = new Task.TaskKey(sIdTask3 + 1, 0,
                null, null, sIdUser1, System.currentTimeMillis());
        mCache.put(key4, 4);

        assertNull(mCache.get(mKey1));
        assertEquals(3, mCache.mKeys.size());
        assertEquals(mKey2, mCache.mKeys.valueAt(0));
        verify(mEvictionCallback, times(1)).onEntryEvicted(mKey1);
    }

    @Test
    public void fillCache_remove_success() {
        mCache.put(mKey1, 1);
        mCache.put(mKey2, 2);
        mCache.put(mKey3, 3);

        mCache.remove(mKey2);

        assertNull(mCache.get(mKey2));
        assertEquals(2, mCache.mKeys.size());
        verify(mEvictionCallback, times(0)).onEntryEvicted(mKey2);
    }

    @Test
    public void put_evictionCallback_notCalled() {
        mCache.put(mKey1, 1);
        verify(mEvictionCallback, times(0)).onEntryEvicted(mKey1);
    }

    @Test
    public void evictAll_evictionCallback_called() {
        mCache.put(mKey1, 1);
        mCache.evictAllCache();
        verify(mEvictionCallback, times(1)).onEntryEvicted(mKey1);
    }

    @Test
    public void trimAll_evictionCallback_called() {
        mCache.put(mKey1, 1);
        mCache.put(mKey2, 2);
        mCache.trimToSize(-1);
        verify(mEvictionCallback, times(1)).onEntryEvicted(mKey1);
        verify(mEvictionCallback, times(1)).onEntryEvicted(mKey2);

    }
}
