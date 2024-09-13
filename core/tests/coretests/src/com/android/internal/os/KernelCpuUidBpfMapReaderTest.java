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

package com.android.internal.os;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.os.KernelCpuUidBpfMapReader.BpfMapIterator;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuUidBpfMapReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Random mRand = new Random(12345);
    private KernelCpuUidTestBpfMapReader mReader;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() {
        mReader =  new KernelCpuUidTestBpfMapReader();
    }

    /**
     * Tests that reading returns null if readBpfData() fails.
     */
    @Test
    public void testUnsuccessfulRead() {
        assertEquals(null, mReader.open());
    }

    /**
     * Tests that reading will always return null after 5 failures.
     */
    @Test
    public void testReadErrorsLimit() {
        for (int i = 0; i < 3; i++) {
            try (BpfMapIterator iter = mReader.open()) {
                assertNull(iter);
            }
        }

        SparseArray<long[]> data = new SparseArray<>();
        long[] times = {2};
        data.put(1, new long[]{2});
        mReader.setData(data);
        testOpenAndReadData(data);

        mReader.setData(null);
        for (int i = 0; i < 3; i++) {
            try (BpfMapIterator iter = mReader.open(true)) {
                assertNull(iter);
            }
        }
        mReader.setData(data);
        try (BpfMapIterator iter = mReader.open(true)) {
            assertNull(iter);
        }
    }

    /** Tests getNextUid functionality. */
    @Test
    public void testGetNextUid() {
        final SparseArray<long[]> data = getTestSparseArray(800, 50);
        mReader.setData(data);
        testOpenAndReadData(data);
    }

    @Test
    public void testConcurrent() throws Exception {
        final SparseArray<long[]> data = getTestSparseArray(200, 50);
        final SparseArray<long[]> data1 = getTestSparseArray(180, 70);
        final List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
        mReader.setData(data);
        // An additional thread for modifying the data.
        ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(11);
        final CountDownLatch ready = new CountDownLatch(10);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch modify = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(10);

        for (int i = 0; i < 5; i++) {
            threadPool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        testOpenAndReadData(data);
                    } catch (Throwable e) {
                        errs.add(e);
                    } finally {
                        done.countDown();
                    }
            });
            threadPool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // Wait for data modification.
                        modify.await();
                        testOpenAndReadData(data1);
                    } catch (Throwable e) {
                        errs.add(e);
                    } finally {
                        done.countDown();
                    }
             });
        }

        assertTrue("Prep timed out", ready.await(100, TimeUnit.MILLISECONDS));
        start.countDown();

        threadPool.schedule(() -> {
                mReader.setData(data1);
                modify.countDown();
        }, 600, TimeUnit.MILLISECONDS);

        assertTrue("Execution timed out", done.await(3, TimeUnit.SECONDS));
        threadPool.shutdownNow();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        errs.forEach(e -> e.printStackTrace(pw));

        assertTrue("All Exceptions:\n" + sw.toString(), errs.isEmpty());
    }

    @Test
    public void testRemoveUidsInRange() {
        final SparseArray<long[]> data = getTestSparseArray(200, 50);
        mReader.setData(data);
        testOpenAndReadData(data);
        SparseArray<long[]> changedData = new SparseArray<>();
        for (int i = 6; i < 200; i++) {
            changedData.put(i, data.get(i));
        }
        mReader.removeUidsInRange(0, 5);
        testOpenAndReadData(changedData);
    }

    @Test
    public void testRemoveUidsInRange_firstAndLastAbsent() {
        final SparseArray<long[]> data = getTestSparseArray(200, 50);
        data.delete(0);
        data.delete(5);
        mReader.setData(data);
        testOpenAndReadData(data);
        SparseArray<long[]> changedData = new SparseArray<>();
        for (int i = 6; i < 200; i++) {
            changedData.put(i, data.get(i));
        }
        mReader.removeUidsInRange(0, 5);
        testOpenAndReadData(changedData);
    }

    private void testOpenAndReadData(SparseArray<long[]> expectedData) {
        try (BpfMapIterator iter = mReader.open()) {
            long[] actual;
            if (expectedData.size() > 0) {
                actual = new long[expectedData.valueAt(0).length + 1];
            } else {
                actual = new long[0];
            }
            for (int i = 0; i < expectedData.size(); i++) {
                assertTrue(iter.getNextUid(actual));
                assertEquals(expectedData.keyAt(i), actual[0]);
                assertArrayEquals(expectedData.valueAt(i), Arrays.copyOfRange(actual, 1, actual.length));
            }
            assertFalse(iter.getNextUid(actual));
            assertFalse(iter.getNextUid(actual));
        }
    }


    private SparseArray<long[]> getTestSparseArray(int uids, int numPerUid) {
        SparseArray<long[]> data = new SparseArray<>();
        for (int i = 0; i < uids; i++) {
            data.put(i, mRand.longs(numPerUid, 0, Long.MAX_VALUE).toArray());
        }
        return data;
    }


    private class KernelCpuUidTestBpfMapReader extends KernelCpuUidBpfMapReader {
        private SparseArray<long[]> mNewData;

        public final void setData(SparseArray<long[]> newData) {
            mNewData = newData;
        }

        @Override
        public final boolean startTrackingBpfTimes() {
            return true;
        }

        @Override
        public final boolean readBpfData() {
            if (mNewData == null) {
                return false;
            }
            mData = mNewData;
            return true;
        }

        @Override
        public final long[] getDataDimensions() {
            return null;
        }

    }
}
