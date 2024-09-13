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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import static java.util.stream.Collectors.toList;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuThreadReaderDiffTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private MockitoSession mMockingSessions;
    @Mock KernelCpuThreadReader mMockReader;

    @Before
    public void setUp() {
        mMockingSessions = mockitoSession().initMocks(this).startMocking();
    }

    @After
    public void tearDown() {
        if (mMockingSessions != null) {
            mMockingSessions.finishMocking();
        }
    }

    @Test
    public void test_empty() {
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isEmpty();
    }

    @Test
    public void test_simple() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {100, 100, 100}))
                .thenReturn(createProcess(new int[] {150, 160, 170}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Arrays.asList(50, 60, 70));
    }

    @Test
    public void test_failure() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {1}))
                .thenReturn(createProcess(new int[] {2}))
                .thenThrow(new RuntimeException())
                .thenReturn(createProcess(new int[] {4}))
                .thenReturn(createProcess(new int[] {6}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Collections.singletonList(1));
        assertThrows(
                RuntimeException.class,
                () -> cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()));
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Collections.singletonList(2));
    }

    @Test
    public void test_twoFailures() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {1}))
                .thenReturn(createProcess(new int[] {2}))
                .thenThrow(new RuntimeException())
                .thenThrow(new RuntimeException())
                .thenReturn(createProcess(new int[] {4}))
                .thenReturn(createProcess(new int[] {6}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Collections.singletonList(1));
        assertThrows(
                RuntimeException.class,
                () -> cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()));
        assertThrows(
                RuntimeException.class,
                () -> cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()));
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Collections.singletonList(2));
    }

    @Test
    public void test_negativeDiff() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {2}))
                .thenReturn(createProcess(new int[] {1}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();
        assertThat(cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()))
                .containsExactly(Collections.singletonList(-1));
    }

    @Test
    public void test_threshold() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {1}))
                .thenReturn(createProcess(new int[] {10}))
                .thenReturn(createProcess(new int[] {12}))
                .thenReturn(createProcess(new int[] {20}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 5);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1)).containsExactly(Collections.singletonList(9));
        assertThat(threadNames(processes1)).containsExactly("thread0");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes2 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes2)).containsExactly(Collections.singletonList(2));
        assertThat(threadNames(processes2)).containsExactly("__OTHER_THREADS");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes3 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes3)).containsExactly(Collections.singletonList(8));
        assertThat(threadNames(processes3)).containsExactly("thread0");
    }

    @Test
    public void test_newThread() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {1}))
                .thenReturn(createProcess(new int[] {2}))
                .thenReturn(createProcess(new int[] {4}, new int[] {5}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1)).containsExactly(Collections.singletonList(1));
        assertThat(threadNames(processes1)).containsExactly("thread0");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes2 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes2))
                .containsExactly(Collections.singletonList(2), Collections.singletonList(5));
        assertThat(threadNames(processes2)).containsExactly("thread0", "thread1");
    }

    @Test
    public void test_stoppedThread() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {1}, new int[] {1}))
                .thenReturn(createProcess(new int[] {2}, new int[] {3}))
                .thenReturn(createProcess(new int[] {4}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1))
                .containsExactly(Collections.singletonList(1), Collections.singletonList(2));
        assertThat(threadNames(processes1)).containsExactly("thread0", "thread1");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes2 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes2)).containsExactly(Collections.singletonList(2));
        assertThat(threadNames(processes2)).containsExactly("thread0");
    }

    @Test
    public void test_nonNegativeOtherThreads() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {0}, new int[] {0}))
                .thenReturn(createProcess(new int[] {4}, new int[] {4}))
                .thenReturn(createProcess(new int[] {10}, new int[] {7}))
                .thenReturn(createProcess(new int[] {20}, new int[] {15}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 5);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1)).containsExactly(Collections.singletonList(8));
        assertThat(threadNames(processes1)).containsExactly("__OTHER_THREADS");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes2 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes2))
                .containsExactly(Collections.singletonList(6), Collections.singletonList(3));
        assertThat(threadNames(processes2)).containsExactly("thread0", "__OTHER_THREADS");

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes3 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes3))
                .containsExactly(Collections.singletonList(10), Collections.singletonList(8));
        assertThat(threadNames(processes3)).containsExactly("thread0", "thread1");
    }

    @Test
    public void test_otherThreadsOnZeroDiff() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {0}))
                .thenReturn(createProcess(new int[] {0}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 5);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1)).containsExactly(Collections.singletonList(0));
        assertThat(threadNames(processes1)).containsExactly("__OTHER_THREADS");
    }

    @Test
    public void test_failureAndNewThread() {
        when(mMockReader.getProcessCpuUsage())
                .thenReturn(createProcess(new int[] {0}))
                .thenThrow(new RuntimeException())
                .thenReturn(createProcess(new int[] {1}, new int[] {10}))
                .thenReturn(createProcess(new int[] {2}, new int[] {12}));
        KernelCpuThreadReaderDiff kernelCpuThreadReaderDiff =
                new KernelCpuThreadReaderDiff(mMockReader, 0);
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        assertThrows(
                RuntimeException.class,
                () -> cpuUsages(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()));
        assertThat(kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed()).isNull();

        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processes1 =
                kernelCpuThreadReaderDiff.getProcessCpuUsageDiffed();
        assertThat(cpuUsages(processes1))
                .containsExactly(Collections.singletonList(1), Collections.singletonList(2));
        assertThat(threadNames(processes1)).containsExactly("thread0", "thread1");
    }

    private ArrayList<KernelCpuThreadReader.ProcessCpuUsage> createProcess(
            int[]... cpuUsageMillis) {
        ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages = new ArrayList<>();
        for (int i = 0; i < cpuUsageMillis.length; i++) {
            int[] cpuUsage = cpuUsageMillis[i];
            threadCpuUsages.add(
                    new KernelCpuThreadReader.ThreadCpuUsage(0, "thread" + i, cpuUsage));
        }
        return new ArrayList<>(
                Collections.singletonList(
                        new KernelCpuThreadReader.ProcessCpuUsage(
                                0, "process", 0, threadCpuUsages)));
    }

    private Collection<Collection<Integer>> cpuUsages(
            Collection<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages) {
        return processCpuUsages.stream()
                .flatMap(p -> p.threadCpuUsages.stream())
                .map(t -> Arrays.stream(t.usageTimesMillis).boxed().collect(toList()))
                .collect(toList());
    }

    private Collection<String> threadNames(
            Collection<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages) {
        return processCpuUsages.stream()
                .flatMap(p -> p.threadCpuUsages.stream())
                .map(t -> t.threadName)
                .collect(toList());
    }
}
