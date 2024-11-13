/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

/**
 * Test class for {@link KernelCpuUidUserSysTimeReader}.
 *
 * $ atest FrameworksCoreTests:com.android.internal.os.KernelCpuUidUserSysTimeReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuUidUserSysTimeReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private File mTestDir;
    private File mTestFile;
    private KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader mReader;
    private VerifiableCallback mCallback;

    private final Random mRand = new Random(12345);
    private final int[] mUids = {0, 1, 22, 333, 4444, 55555};
    private final long[][] mInitialTimes = new long[][]{
            {15334000, 310964000},
            {537000, 114000},
            {40000, 10000},
            {170000, 57000},
            {5377000, 867000},
            {47000, 17000}
    };

    private final MockClock mMockClock = new MockClock();

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() {
        mTestDir = getContext().getDir("test", Context.MODE_PRIVATE);
        mTestFile = new File(mTestDir, "test.file");
        mReader = new KernelCpuUidUserSysTimeReader(
                new KernelCpuProcStringReader(mTestFile.getAbsolutePath(), mMockClock),
                false, mMockClock);
        mCallback = new VerifiableCallback();
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mTestDir);
        FileUtils.deleteContents(getContext().getFilesDir());
    }

    @Test
    public void testThrottler() throws Exception {
        mMockClock.realtime = 1000;

        mReader = new KernelCpuUidUserSysTimeReader(
                new KernelCpuProcStringReader(mTestFile.getAbsolutePath(), mMockClock),
                true, mMockClock);

        mReader.setThrottle(500);

        writeToFile(uidLines(mUids, mInitialTimes));
        mReader.readDelta(false, mCallback);
        assertEquals(6, mCallback.mData.size());

        long[][] times1 = increaseTime(mInitialTimes);
        writeToFile(uidLines(mUids, times1));
        mCallback.clear();
        mReader.readDelta(false, mCallback);
        assertEquals(0, mCallback.mData.size());

        mMockClock.realtime += 600;

        long[][] times2 = increaseTime(times1);
        writeToFile(uidLines(mUids, times2));
        mCallback.clear();
        mReader.readDelta(false, mCallback);
        assertEquals(6, mCallback.mData.size());

        long[][] times3 = increaseTime(times2);
        writeToFile(uidLines(mUids, times3));
        mCallback.clear();
        mReader.readDelta(false, mCallback);
        assertEquals(0, mCallback.mData.size());

        // Force the delta read, previously skipped increments should now be read
        mCallback.clear();
        mReader.readDelta(true, mCallback);
        assertEquals(6, mCallback.mData.size());

        mMockClock.realtime += 600;

        long[][] times4 = increaseTime(times3);
        writeToFile(uidLines(mUids, times4));
        mCallback.clear();
        mReader.readDelta(true, mCallback);
        assertEquals(6, mCallback.mData.size());

        // Don't force the delta read, throttle should be set from last read.
        long[][] times5 = increaseTime(times4);
        writeToFile(uidLines(mUids, times5));
        mCallback.clear();
        mReader.readDelta(false, mCallback);
        assertEquals(0, mCallback.mData.size());

        mMockClock.realtime += 600;

        mCallback.clear();
        mReader.readDelta(false, mCallback);
        assertEquals(6, mCallback.mData.size());
    }

    @Test
    public void testReadDelta() throws Exception {
        final long[][] times1 = mInitialTimes;
        writeToFile(uidLines(mUids, times1));
        mReader.readDelta(false, mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times1[i]);
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();

        // Verify that a second call will only return deltas.
        final long[][] times2 = increaseTime(times1);
        writeToFile(uidLines(mUids, times2));
        mReader.readDelta(false, mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times2[i], times1[i]));
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();

        // Verify that there won't be a callback if the proc file values didn't change.
        mReader.readDelta(false, mCallback);
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();

        // Verify that calling with a null callback doesn't result in any crashes
        final long[][] times3 = increaseTime(times2);
        writeToFile(uidLines(mUids, times3));
        mReader.readDelta(false, null);

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        final long[][] times4 = increaseTime(times3);
        writeToFile(uidLines(mUids, times4));
        mReader.readDelta(false, mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times4[i], times3[i]));
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();
        assertTrue(mTestFile.delete());
    }

    @Test
    public void testReadDeltaWrongData() throws Exception {
        final long[][] times1 = mInitialTimes;
        writeToFile(uidLines(mUids, times1));
        mReader.readDelta(false, mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times1[i]);
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();

        // Verify that there should not be a callback for a particular UID if its time decreases.
        final long[][] times2 = increaseTime(times1);
        times2[0][0] = 1000;
        writeToFile(uidLines(mUids, times2));
        mReader.readDelta(false, mCallback);
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times2[i], times1[i]));
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();
        assertTrue(mTestFile.delete());
    }

    @Test
    public void testReadAbsolute() throws Exception {
        final long[][] times1 = mInitialTimes;
        writeToFile(uidLines(mUids, times1));
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times1[i]);
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();

        // Verify that a second call should still return absolute values
        final long[][] times2 = increaseTime(times1);
        writeToFile(uidLines(mUids, times2));
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times2[i]);
        }
        mCallback.verifyNoMoreInteractions();
        mCallback.clear();
        assertTrue(mTestFile.delete());
    }

    private String uidLines(int[] uids, long[][] times) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < uids.length; i++) {
            sb.append(uids[i]).append(':');
            for (int j = 0; j < times[i].length; j++) {
                sb.append(' ').append(times[i][j]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void writeToFile(String s) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(s);
            w.flush();
        }
    }

    private long[][] increaseTime(long[][] original) {
        long[][] newTime = new long[original.length][original[0].length];
        for (int i = 0; i < original.length; i++) {
            for (int j = 0; j < original[0].length; j++) {
                newTime[i][j] = original[i][j] + mRand.nextInt(1000) * 1000 + 1000;
            }
        }
        return newTime;
    }

    private long[] subtract(long[] a1, long[] a2) {
        long[] val = new long[a1.length];
        for (int i = 0; i < val.length; ++i) {
            val[i] = a1[i] - a2[i];
        }
        return val;
    }

    private class VerifiableCallback implements KernelCpuUidTimeReader.Callback<long[]> {
        SparseArray<long[]> mData = new SparseArray<>();

        public void verify(int uid, long[] cpuTimes) {
            long[] array = mData.get(uid);
            assertNotNull(array);
            assertArrayEquals(cpuTimes, array);
            mData.remove(uid);
        }

        public void clear() {
            mData.clear();
        }

        @Override
        public void onUidCpuTime(int uid, long[] times) {
            long[] array = new long[times.length];
            System.arraycopy(times, 0, array, 0, array.length);
            mData.put(uid, array);
        }

        public void verifyNoMoreInteractions() {
            assertEquals(0, mData.size());
        }
    }
}
