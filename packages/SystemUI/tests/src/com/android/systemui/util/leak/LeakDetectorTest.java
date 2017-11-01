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


import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.leak.ReferenceTestUtils.CollectionWaiter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LeakDetectorTest extends SysuiTestCase {

    private LeakDetector mLeakDetector;

    @Before
    public void setup() {
        mLeakDetector = LeakDetector.create();

        // Note: Do not try to factor out object / collection waiter creation. The optimizer will
        // try and cache accesses to fields and thus create a GC root for the duration of the test
        // method, thus breaking the test.
    }

    @Test
    public void trackInstance_doesNotLeakTrackedObject() {
        Object object = new Object();
        CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(object);

        mLeakDetector.trackInstance(object);
        object = null;
        collectionWaiter.waitForCollection();
    }

    @Test
    public void trackCollection_doesNotLeakTrackedObject() {
        Collection<?> object = new ArrayList<>();
        CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(object);

        mLeakDetector.trackCollection(object, "tag");
        object = null;
        collectionWaiter.waitForCollection();
    }

    @Test
    public void trackGarbage_doesNotLeakTrackedObject() {
        Object object = new Object();
        CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(object);

        mLeakDetector.trackGarbage(object);
        object = null;
        collectionWaiter.waitForCollection();
    }

    @Test
    public void testDump() throws Exception {
        Object o1 = new Object();
        Object o2 = new Object();
        Collection<Object> col1 = new ArrayList<>();

        mLeakDetector.trackInstance(o1);
        mLeakDetector.trackCollection(col1, "tag");
        mLeakDetector.trackGarbage(o2);

        FileOutputStream fos = new FileOutputStream("/dev/null");
        mLeakDetector.dump(fos.getFD(), new PrintWriter(fos), new String[0]);
    }

    @Test
    public void testDisabled() throws Exception {
        mLeakDetector = new LeakDetector(null, null, null);

        Object o1 = new Object();
        Object o2 = new Object();
        Collection<Object> col1 = new ArrayList<>();

        mLeakDetector.trackInstance(o1);
        mLeakDetector.trackCollection(col1, "tag");
        mLeakDetector.trackGarbage(o2);

        FileOutputStream fos = new FileOutputStream("/dev/null");
        mLeakDetector.dump(fos.getFD(), new PrintWriter(fos), new String[0]);
    }
}