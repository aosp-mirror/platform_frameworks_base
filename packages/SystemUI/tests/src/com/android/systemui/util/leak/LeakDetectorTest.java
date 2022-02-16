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


import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.leak.ReferenceTestUtils.CollectionWaiter;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LeakDetectorTest extends SysuiTestCase {

    private LeakDetector mLeakDetector;

    // The references for which collection is observed are stored in fields. The allocation and
    // of these references happens in separate methods (trackObjectWith/trackCollectionWith)
    // from where they are set to null. The generated code might keep the allocated reference
    // alive in a dex register when compiling in release mode. As R8 is used to compile this
    // test the --dontoptimize flag is also required to ensure that these methods are not
    // inlined, as that would defeat the purpose of having the mutation in methods.
    private Object mObject;
    private Collection<?> mCollection;

    private CollectionWaiter trackObjectWith(Consumer<Object> tracker) {
        mObject = new Object();
        CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(mObject);
        tracker.accept(mObject);
        return collectionWaiter;
    }

    private CollectionWaiter trackCollectionWith(
            BiConsumer<? super Collection<?>, String> tracker) {
        mCollection = new ArrayList<>();
        CollectionWaiter collectionWaiter = ReferenceTestUtils.createCollectionWaiter(mCollection);
        tracker.accept(mCollection, "tag");
        return collectionWaiter;
    }

    @Before
    public void setup() {
        mLeakDetector = LeakDetector.create();

        // Note: Do not try to factor out object / collection waiter creation. The optimizer will
        // try and cache accesses to fields and thus create a GC root for the duration of the test
        // method, thus breaking the test.
    }

    @Test
    public void trackInstance_doesNotLeakTrackedObject() {
        CollectionWaiter collectionWaiter = trackObjectWith(mLeakDetector::trackInstance);
        mObject = null;
        collectionWaiter.waitForCollection();
    }

    @Ignore("b/75329085")
    @Test
    public void trackCollection_doesNotLeakTrackedObject() {
        CollectionWaiter collectionWaiter = trackCollectionWith(mLeakDetector::trackCollection);
        mCollection = null;
        collectionWaiter.waitForCollection();
    }

    @Test
    public void trackGarbage_doesNotLeakTrackedObject() {
        CollectionWaiter collectionWaiter = trackObjectWith(mLeakDetector::trackGarbage);
        mObject = null;
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
