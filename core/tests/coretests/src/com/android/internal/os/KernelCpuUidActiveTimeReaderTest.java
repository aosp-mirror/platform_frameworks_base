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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;
import android.util.SparseLongArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

/**
 * Test class for {@link KernelCpuUidActiveTimeReader}.
 *
 * $ atest FrameworksCoreTests:com.android.internal.os.KernelCpuUidActiveTimeReaderTest
 */
@SmallTest
@RunWith(Parameterized.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuUidActiveTimeReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private File mTestDir;
    private File mTestFile;
    private KernelCpuUidActiveTimeReader mReader;
    private KernelCpuUidTestBpfMapReader mBpfMapReader;
    private VerifiableCallback mCallback;

    private Random mRand = new Random(12345);
    protected boolean mUseBpf;
    private final int mCpus = 4;
    private final String mHeadline = "cpus: 4\n";
    private final int[] mUids = {0, 1, 22, 333, 4444, 55555};

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Parameters(name="useBpf={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {true}, {false} });
    }

    public KernelCpuUidActiveTimeReaderTest(boolean useBpf) {
        mUseBpf = useBpf;
    }

    @Before
    public void setUp() {
        mTestDir = getContext().getDir("test", Context.MODE_PRIVATE);
        mTestFile = new File(mTestDir, "test.file");
        mBpfMapReader = new KernelCpuUidTestBpfMapReader();
        mReader = new KernelCpuUidActiveTimeReader(
                new KernelCpuProcStringReader(mTestFile.getAbsolutePath()), mBpfMapReader, false);
        mCallback = new VerifiableCallback();
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mTestDir);
        FileUtils.deleteContents(getContext().getFilesDir());
    }

    @Test
    public void testReadDelta() throws Exception {
        final long[][] times = increaseTime(new long[mUids.length][mCpus]);
        setCpusAndData(times);
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], getActiveTime(times[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that a second call will only return deltas.
        mCallback.clear();
        final long[][] newTimes1 = increaseTime(times);
        setCpusAndData(newTimes1);
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], getActiveTime(newTimes1[i]) - getActiveTime(times[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that there won't be a callback if the proc file values didn't change.
        mCallback.clear();
        mReader.readDelta(mCallback);
        mCallback.verifyNoMoreInteractions();

        // Verify that calling with a null callback doesn't result in any crashes
        mCallback.clear();
        final long[][] newTimes2 = increaseTime(newTimes1);
        setCpusAndData(newTimes2);
        mReader.readDelta(null);
        mCallback.verifyNoMoreInteractions();

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        mCallback.clear();
        final long[][] newTimes3 = increaseTime(newTimes2);
        setCpusAndData(newTimes3);
        writeToFile(mHeadline + uidLines(mUids, newTimes3));
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], getActiveTime(newTimes3[i]) - getActiveTime(newTimes2[i]));
        }
        mCallback.verifyNoMoreInteractions();
        clearCpusAndData();
    }

    @Test
    public void testReadAbsolute() throws Exception {
        final long[][] times1 = increaseTime(new long[mUids.length][mCpus]);
        setCpusAndData(times1);
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times1[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that a second call should still return absolute values
        mCallback.clear();
        final long[][] times2 = increaseTime(times1);
        setCpusAndData(times2);
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times2[i]));
        }
        mCallback.verifyNoMoreInteractions();
        clearCpusAndData();
    }

    @Test
    public void testReadDeltaDecreasedTime() throws Exception {
        final long[][] times1 = increaseTime(new long[mUids.length][mCpus]);
        setCpusAndData(times1);
        mReader.readDelta(mCallback);

        // Verify that there should not be a callback for a particular UID if its time decreases.
        mCallback.clear();
        final long[][] times2 = increaseTime(times1);
        System.arraycopy(times1[0], 0, times2[0], 0, mCpus);
        times2[0][0] = 100;
        setCpusAndData(times2);
        mReader.readDelta(mCallback);
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times2[i]) - getActiveTime(times1[i]));
        }
        mCallback.verifyNoMoreInteractions();
        clearCpusAndData();

        // Verify that the internal state was not modified.
        mCallback.clear();
        final long[][] times3 = increaseTime(times2);
        times3[0] = increaseTime(times1)[0];
        setCpusAndData(times3);
        mReader.readDelta(mCallback);
        mCallback.verify(mUids[0], getActiveTime(times3[0]) - getActiveTime(times1[0]));
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times3[i]) - getActiveTime(times2[i]));
        }
        mCallback.verifyNoMoreInteractions();
    }

    @Test
    public void testReadDeltaNegativeTime() throws Exception {
        final long[][] times1 = increaseTime(new long[mUids.length][mCpus]);
        setCpusAndData(times1);
        mReader.readDelta(mCallback);

        // Verify that there should not be a callback for a particular UID if its time is -ve.
        mCallback.clear();
        final long[][] times2 = increaseTime(times1);
        times2[0][0] *= -1;
        setCpusAndData(times2);
        mReader.readDelta(mCallback);
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times2[i]) - getActiveTime(times1[i]));
        }
        mCallback.verifyNoMoreInteractions();
        clearCpusAndData();

        // Verify that the internal state was not modified.
        mCallback.clear();
        final long[][] times3 = increaseTime(times2);
        times3[0] = increaseTime(times1)[0];
        setCpusAndData(times3);
        mReader.readDelta(mCallback);
        mCallback.verify(mUids[0], getActiveTime(times3[0]) - getActiveTime(times1[0]));
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], getActiveTime(times3[i]) - getActiveTime(times2[i]));
        }
        mCallback.verifyNoMoreInteractions();
    }

    private void setCpusAndData(long[][] times) throws IOException {
        if (mUseBpf) {
            mBpfMapReader.setCpus(new long[]{ mCpus });
            SparseArray<long[]> data = new SparseArray<>();
            for (int i = 0; i < mUids.length; i++) {
                data.put(mUids[i], times[i]);
            }
            mBpfMapReader.setData(data);
        } else {
            writeToFile(mHeadline + uidLines(mUids, times));
        }
    }

    private void clearCpusAndData() {
        if (mUseBpf) {
            mBpfMapReader.setCpus(null);
            mBpfMapReader.setData(new SparseArray<>());
        } else {
            assertTrue(mTestFile.delete());
        }
    }

    private String uidLines(int[] uids, long[][] times) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < uids.length; i++) {
            sb.append(uids[i]).append(':');
            for (int j = 0; j < times[i].length; j++) {
                sb.append(' ').append(times[i][j] / 10);
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
                newTime[i][j] = original[i][j] + mRand.nextInt(10000) * 1000 + 1000;
            }
        }
        return newTime;
    }

    private long getActiveTime(long[] times) {
        return times[0] + times[1] / 2 + times[2] / 3 + times[3] / 4;
    }

    private class VerifiableCallback implements KernelCpuUidTimeReader.Callback<Long> {
        SparseLongArray mData = new SparseLongArray();

        public void verify(int uid, long time) {
            assertEquals(time, mData.get(uid));
            mData.delete(uid);
        }

        public void clear() {
            mData.clear();
        }

        @Override
        public void onUidCpuTime(int uid, Long time) {
            mData.put(uid, time);
        }

        public void verifyNoMoreInteractions() {
            assertEquals(0, mData.size());
        }
    }

    private class KernelCpuUidTestBpfMapReader extends KernelCpuUidBpfMapReader {
        private long[] mCpus;
        private SparseArray<long[]> mNewData = new SparseArray<>();

        public void setData(SparseArray<long[]> data) {
            mNewData = data;
        }

        public void setCpus(long[] cpus) {
            mCpus = cpus;
        }

        @Override
        public final boolean startTrackingBpfTimes() {
            return true;
        }

        @Override
        protected final boolean readBpfData() {
            if (!mUseBpf) {
                return false;
            }
            mData = mNewData;
            return true;
        }

        @Override
        public final long[] getDataDimensions() {
            return mCpus;
        }
    }
}
