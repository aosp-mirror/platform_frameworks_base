/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.leak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.leak.ReferenceTestUtils.CollectionWaiter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WeakIdentityHashMapTest extends SysuiTestCase {

    WeakIdentityHashMap<Object, Object> mMap;

    @Before
    public void setup() {
        mMap = new WeakIdentityHashMap<>();
    }

    private CollectionWaiter addObjectToMap(WeakIdentityHashMap<Object, Object> map) {
      Object object = new Object();
      CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(object);
      map.put(object, "value");
      return collectionWaiter;
    }

    @Test
    public void testUsesIdentity() {
        String a1 = new String("a");
        String a2 = new String("a");
        assertNotSame(a1, a2);

        mMap.put(a1, "value1");
        mMap.put(a2, "value2");

        assertEquals("value1", mMap.get(a1));
        assertEquals("value2", mMap.get(a2));
    }

    @Test
    public void testWeaklyReferences() {
        // Allocate and add an object to the weak map in a separate method to avoid a live
        // reference to the allocated object in a dex register. As R8 is used to compile this
        // test the --dontoptimize flag is also required to ensure that the method is not
        // inlined, as that would defeat the purpose of having the allocation in a separate
        // method.
        CollectionWaiter collectionWaiter = addObjectToMap(mMap);

        // Wait until object has been collected. We'll also need to wait for mMap to become empty,
        // because our collection waiter may be told about the collection earlier than mMap.
        collectionWaiter.waitForCollection();
        ReferenceTestUtils.waitForCondition(mMap::isEmpty);

        assertEquals(0, mMap.size());
        assertTrue(mMap.isEmpty());
    }
}
