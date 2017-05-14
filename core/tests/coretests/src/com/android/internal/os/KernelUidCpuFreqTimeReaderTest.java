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
 * limitations under the License.
 */

package com.android.internal.os;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;

/**
 * Test class for {@link KernelUidCpuFreqTimeReader}.
 *
 * To run the tests, use
 *
 * runtest -c com.android.internal.os.KernelUidCpuFreqTimeReaderTest frameworks-core
 *
 * or the following steps:
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class com.android.internal.os.KernelUidCpuFreqTimeReaderTest -w \
 *     com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelUidCpuFreqTimeReaderTest {
    @Mock private BufferedReader mBufferedReader;
    @Mock private KernelUidCpuFreqTimeReader.Callback mCallback;

    private KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mKernelUidCpuFreqTimeReader = new KernelUidCpuFreqTimeReader();
    }

    @Test
    public void testReadDelta() throws Exception {
        final long[] freqs = {1, 12, 123, 1234, 12345, 123456};
        final int[] uids = {1, 22, 333, 4444, 5555};
        final long[][] times = new long[uids.length][freqs.length];
        for (int i = 0; i < uids.length; ++i) {
            for (int j = 0; j < freqs.length; ++j) {
                times[i][j] = uids[i] * freqs[j] * 10;
            }
        }
        final String[] uidsTimesLines = getUidTimesLines(uids, times);
        final String[] lines = new String[uidsTimesLines.length + 1];
        System.arraycopy(uidsTimesLines, 0, lines, 0, uidsTimesLines.length);
        lines[uidsTimesLines.length] = null;
        when(mBufferedReader.readLine())
                .thenReturn(getFreqsLine(freqs), lines);
        mKernelUidCpuFreqTimeReader.readDelta(mBufferedReader, mCallback);
        verify(mCallback).onCpuFreqs(freqs);
        for (int i = 0; i < uids.length; ++i) {
            verify(mCallback).onUidCpuFreqTime(uids[i], times[i]);
        }
        verifyNoMoreInteractions(mCallback);
    }

    private String getFreqsLine(long[] freqs) {
        final StringBuilder sb = new StringBuilder();
        sb.append("uid:");
        for (int i = 0; i < freqs.length; ++i) {
            sb.append(" " + freqs[i]);
        }
        return sb.toString();
    }

    private String[] getUidTimesLines(int[] uids, long[][] times) {
        final String[] lines = new String[uids.length];
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uids.length; ++i) {
            sb.setLength(0);
            sb.append(uids[i] + ":");
            for (int j = 0; j < times[i].length; ++j) {
                sb.append(" " + times[i][j] / 10);
            }
            lines[i] = sb.toString();
        }
        return lines;
    }
}
