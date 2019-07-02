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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Process;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.android.internal.os.KernelCpuThreadReader.ProcessCpuUsage;
import com.android.internal.os.KernelCpuThreadReader.ThreadCpuUsage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * End to end test for {@link KernelCpuThreadReader} that checks the accuracy of the reported times
 * by spawning threads that do a predictable amount of work
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class KernelCpuThreadReaderEndToEndTest {

    private static final int TIMED_NUM_SAMPLES = 5;
    private static final int TIMED_START_MILLIS = 500;
    private static final int TIMED_END_MILLIS = 2000;
    private static final int TIMED_INCREMENT_MILLIS = 500;
    private static final int TIMED_COMPARISON_DELTA_MILLIS = 200;

    private static final int ITERATIVE_NUM_SAMPLES = 100;
    private static final long ITERATIVE_LOW_ITERATIONS = (long) 1e8;
    private static final long ITERATIVE_HIGH_ITERATIONS = (long) 2e8;
    private static final double ITERATIONS_COMPARISONS_DELTA = 0.25;

    /**
     * Test that when we busy-wait for the thread-local time to reach N seconds, the time reported
     * is also N seconds. Takes ~10s.
     */
    @Test
    public void testTimedWork() throws InterruptedException {
        for (int millis = TIMED_START_MILLIS;
                millis <= TIMED_END_MILLIS;
                millis += TIMED_INCREMENT_MILLIS) {
            final Duration targetDuration = Duration.ofMillis(millis);
            final Runnable work = timedWork(targetDuration);
            Duration resultDuration = getAverageWorkTime(
                    work, String.format("timed%dms", millis), TIMED_NUM_SAMPLES);
            assertEquals(
                    "Time worked according to currentThreadTimeMillis doesn't match "
                            + "KernelCpuThreadReader",
                    targetDuration.toMillis(), resultDuration.toMillis(),
                    TIMED_COMPARISON_DELTA_MILLIS);
        }
    }

    /**
     * Test that when we scale up the amount of work by N, the time reported also scales by N. Takes
     * ~15s.
     */
    @Test
    public void testIterativeWork() throws InterruptedException {
        final Runnable lowAmountWork = iterativeWork(ITERATIVE_LOW_ITERATIONS);
        final Runnable highAmountWork = iterativeWork(ITERATIVE_HIGH_ITERATIONS);
        final Duration lowResultDuration =
                getAverageWorkTime(lowAmountWork, "iterlow", ITERATIVE_NUM_SAMPLES);
        final Duration highResultDuration =
                getAverageWorkTime(highAmountWork, "iterhigh", ITERATIVE_NUM_SAMPLES);
        assertEquals(
                "Work scale and CPU time scale do not match",
                ((double) ITERATIVE_HIGH_ITERATIONS) / ((double) ITERATIVE_LOW_ITERATIONS),
                ((double) highResultDuration.toMillis()) / ((double) lowResultDuration.toMillis()),
                ITERATIONS_COMPARISONS_DELTA);
    }

    /**
     * Run some work {@code numSamples} times, and take the average CPU duration used for that work
     * according to {@link KernelCpuThreadReader}
     */
    private Duration getAverageWorkTime(
            Runnable work, String tag, int numSamples) throws InterruptedException {
        // Count down every time a thread finishes work, so that we can wait for work to complete
        final CountDownLatch workFinishedLatch = new CountDownLatch(numSamples);
        // Count down once when threads can terminate (after we get them from
        // `KernelCpuThreadReader`)
        final CountDownLatch threadFinishedLatch = new CountDownLatch(1);

        // Start `NUM_SAMPLE` threads to do the work
        for (int i = 0; i < numSamples; i++) {
            final String threadName = String.format("%s%d", tag, i);
            // Check the thread name, as we rely on it later to identify threads
            assertTrue("Max name length for linux threads is 15", threadName.length() <= 15);
            doWork(work, threadName, workFinishedLatch, threadFinishedLatch);
        }

        // Wait for threads to finish
        workFinishedLatch.await();

        // Get thread data from KernelCpuThreadReader
        final KernelCpuThreadReader kernelCpuThreadReader =
                KernelCpuThreadReader.create(8, uid -> uid == Process.myUid());
        assertNotNull(kernelCpuThreadReader);
        kernelCpuThreadReader.setUidPredicate(uid -> uid == Process.myUid());
        final Optional<ProcessCpuUsage> currentProcessCpuUsage =
                kernelCpuThreadReader.getProcessCpuUsage().stream()
                        .filter(p -> p.processId == Process.myPid())
                        .findFirst();
        assertTrue(currentProcessCpuUsage.isPresent());

        // Threads can terminate, as we've finished crawling them from /proc
        threadFinishedLatch.countDown();

        // Check that we've got times for every thread we spawned
        final List<ThreadCpuUsage> threadCpuUsages =
                currentProcessCpuUsage.get().threadCpuUsages.stream()
                        .filter((thread) -> thread.threadName.startsWith(tag))
                        .collect(Collectors.toList());
        assertEquals(
                "Incorrect number of threads returned by KernelCpuThreadReader",
                numSamples, threadCpuUsages.size());

        // Calculate the average time spent working
        final OptionalDouble averageWorkTimeMillis = threadCpuUsages.stream()
                .mapToDouble((t) -> Arrays.stream(t.usageTimesMillis).sum())
                .average();
        assertTrue(averageWorkTimeMillis.isPresent());
        return Duration.ofMillis((long) averageWorkTimeMillis.getAsDouble());
    }

    /**
     * Work that lasts {@code duration} according to {@link SystemClock#currentThreadTimeMillis()}
     */
    private Runnable timedWork(Duration duration) {
        return () -> {
            // Busy loop until `duration` has elapsed for the thread timer
            final long startTimeMillis = SystemClock.currentThreadTimeMillis();
            final long durationMillis = duration.toMillis();
            while (true) {
                final long elapsedMillis = SystemClock.currentThreadTimeMillis() - startTimeMillis;
                if (elapsedMillis >= durationMillis) {
                    break;
                }
            }
        };
    }

    /**
     * Work that iterates {@code iterations} times
     */
    private Runnable iterativeWork(long iterations) {
        Consumer<Long> empty = (i) -> {
        };
        return () -> {
            long count = 0;
            for (long i = 0; i < iterations; i++) {
                // Alternate branching to reduce effect of branch prediction
                if (i % 2 == 0) {
                    count++;
                }
            }
            // Call empty function with value to avoid loop getting optimized away
            empty.accept(count);
        };
    }

    /**
     * Perform some work in another thread
     *
     * @param work                the work to perform
     * @param threadName          the name of the spawned thread
     * @param workFinishedLatch   latch to register that the work has been completed
     * @param threadFinishedLatch latch to pause termination of the thread until the latch is
     *                            decremented
     */
    private void doWork(
            Runnable work,
            String threadName,
            CountDownLatch workFinishedLatch,
            CountDownLatch threadFinishedLatch) {
        Runnable workWrapped = () -> {
            // Do the work
            work.run();
            // Notify that the work is finished
            workFinishedLatch.countDown();
            // Wait until `threadFinishLatch` has been released in order to keep the thread alive so
            // we can see it in `proc` filesystem
            try {
                threadFinishedLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        };
        new Thread(workWrapped, threadName).start();
    }
}
