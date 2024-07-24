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
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.FileUtils;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

/**
 * Test class for {@link KernelCpuUidFreqTimeReader}.
 *
 * $ atest FrameworksCoreTests:com.android.internal.os.KernelCpuUidFreqTimeReaderTest
 */
@SmallTest
@RunWith(Parameterized.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuUidFreqTimeReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private File mTestDir;
    private File mTestFile;
    private KernelCpuUidFreqTimeReader mReader;
    private KernelCpuUidTestBpfMapReader mBpfMapReader;
    private VerifiableCallback mCallback;
    @Mock
    private PowerProfile mPowerProfile;
    private boolean mUseBpf;

    private Random mRand = new Random(12345);
    private final int[] mUids = {0, 1, 22, 333, 4444, 55555};

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Parameters(name="useBpf={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {true}, {false} });
    }

    public KernelCpuUidFreqTimeReaderTest(boolean useBpf) {
        mUseBpf = useBpf;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestDir = getContext().getDir("test", Context.MODE_PRIVATE);
        mTestFile = new File(mTestDir, "test.file");
        mBpfMapReader = new KernelCpuUidTestBpfMapReader();
        mReader = new KernelCpuUidFreqTimeReader(mTestFile.getAbsolutePath(),
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
        final long[] freqs = {110, 123, 145, 167, 289, 997};
        final long[][] times = increaseTime(new long[mUids.length][freqs.length]);

        setFreqsAndData(freqs, times);
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], times[i]);
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that readDelta also reads the frequencies if not already available.
        clearFreqsAndData();

        // Verify that a second call will only return deltas.
        mCallback.clear();
        final long[][] newTimes1 = increaseTime(times);
        setFreqsAndData(freqs, newTimes1);
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], subtract(newTimes1[i], times[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that there won't be a callback if the proc file values didn't change.
        mCallback.clear();
        mReader.readDelta(mCallback);
        mCallback.verifyNoMoreInteractions();

        // Verify that calling with a null callback doesn't result in any crashes
        mCallback.clear();
        final long[][] newTimes2 = increaseTime(newTimes1);
        setFreqsAndData(freqs, newTimes2);
        mReader.readDelta(null);
        mCallback.verifyNoMoreInteractions();

        // Verify that the readDelta call will only return deltas when
        // the previous call had null callback.
        mCallback.clear();
        final long[][] newTimes3 = increaseTime(newTimes2);
        setFreqsAndData(freqs, newTimes3);
        mReader.readDelta(mCallback);
        for (int i = 0; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], subtract(newTimes3[i], newTimes2[i]));
        }
        mCallback.verifyNoMoreInteractions();
        clearFreqsAndData();
    }

    @Test
    public void testReadAbsolute() throws Exception {
        final long[] freqs = {110, 123, 145, 167, 289, 997};
        final long[][] times1 = increaseTime(new long[mUids.length][freqs.length]);

        setFreqsAndData(freqs, times1);
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times1[i]);
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that readDelta also reads the frequencies if not already available.
        clearFreqsAndData();

        // Verify that a second call should still return absolute values
        mCallback.clear();
        final long[][] times2 = increaseTime(times1);
        setFreqsAndData(freqs, times2);
        mReader.readAbsolute(mCallback);
        for (int i = 0; i < mUids.length; i++) {
            mCallback.verify(mUids[i], times2[i]);
        }
        mCallback.verifyNoMoreInteractions();
        clearFreqsAndData();
    }

    @Test
    public void testReadDeltaWrongData() throws Exception {
        final long[] freqs = {110, 123, 145, 167, 289, 997};
        final long[][] times1 = increaseTime(new long[mUids.length][freqs.length]);

        setFreqsAndData(freqs, times1);
        mReader.readDelta(mCallback);

        // Verify that there should not be a callback for a particular UID if its time decreases.
        mCallback.clear();
        final long[][] times2 = increaseTime(times1);
        times2[0][0] = 1000;
        setFreqsAndData(freqs, times2);
        mReader.readDelta(mCallback);
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times2[i], times1[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that the internal state was not modified.
        mCallback.clear();
        final long[][] times3 = increaseTime(times2);
        times3[0] = increaseTime(times1)[0];
        setFreqsAndData(freqs, times3);
        mReader.readDelta(mCallback);
        mCallback.verify(mUids[0], subtract(times3[0], times1[0]));
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times3[i], times2[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that there is no callback if any value in the proc file is -ve.
        mCallback.clear();
        final long[][] times4 = increaseTime(times3);
        times4[0][0] *= -1;
        setFreqsAndData(freqs, times4);
        mReader.readDelta(mCallback);
        for (int i = 1; i < mUids.length; ++i) {
            mCallback.verify(mUids[i], subtract(times4[i], times3[i]));
        }
        mCallback.verifyNoMoreInteractions();

        // Verify that the internal state was not modified when the proc file had -ve value.
        mCallback.clear();
        final long[][] times5 = increaseTime(times4);
        times5[0] = increaseTime(times3)[0];
        setFreqsAndData(freqs, times5);
        mReader.readDelta(mCallback);
        mCallback.verify(mUids[0], subtract(times5[0], times3[0]));
        for (int i = 1; i < mUids.length; i++) {
            mCallback.verify(mUids[i], subtract(times5[i], times4[i]));
        }

        clearFreqsAndData();
    }

    private void setFreqs(long[] freqs) throws IOException {
        if (mUseBpf) {
            mBpfMapReader.setFreqs(freqs);
        } else {
            writeToFile(freqsLine(freqs));
        }
    }

    private void setFreqsAndData(long[] freqs, long[][] times) throws IOException {
        if (mUseBpf) {
            mBpfMapReader.setFreqs(freqs);
            SparseArray<long[]> data = new SparseArray<>();
            for (int i = 0; i < mUids.length; i++) {
                data.put(mUids[i], times[i]);
            }
            mBpfMapReader.setData(data);
        } else {
            writeToFile(freqsLine(freqs) + uidLines(mUids, times));
        }
    }

    private void clearFreqsAndData() {
        if (mUseBpf) {
            mBpfMapReader.setFreqs(null);
            mBpfMapReader.setData(new SparseArray<>());
        } else {
            assertTrue(mTestFile.delete());
        }
    }

    private String freqsLine(long[] freqs) {
        final StringBuilder sb = new StringBuilder();
        sb.append("uid:");
        for (int i = 0; i < freqs.length; ++i) {
            sb.append(" " + freqs[i]);
        }
        return sb.append('\n').toString();
    }

    private void setCpuClusterFreqs(int numClusters, int... clusterFreqs) {
        assertEquals(numClusters, clusterFreqs.length);
        when(mPowerProfile.getNumCpuClusters()).thenReturn(numClusters);
        for (int i = 0; i < numClusters; ++i) {
            when(mPowerProfile.getNumSpeedStepsInCpuCluster(i)).thenReturn(clusterFreqs[i]);
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
                newTime[i][j] = original[i][j] + mRand.nextInt(10000) * 10 + 10;
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

    private class KernelCpuUidTestBpfMapReader extends KernelCpuUidBpfMapReader {
        private long[] mCpuFreqs;
        private SparseArray<long[]> mNewData = new SparseArray<>();

        public void setData(SparseArray<long[]> data) {
            mNewData = data;
        }

        public void setFreqs(long[] freqs) {
            mCpuFreqs = freqs;
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
            return mCpuFreqs;
        }
    }
}
