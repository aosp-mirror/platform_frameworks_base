/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Benchmark to evaluate the performance of References. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ReferencePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Object mObject;

    // How fast can references can be allocated?
    @Test
    public void timeAlloc() {
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            new PhantomReference(mObject, queue);
        }
    }

    // How fast can references can be allocated and manually enqueued?
    @Test
    public void timeAllocAndEnqueue() {
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            (new PhantomReference<Object>(mObject, queue)).enqueue();
        }
    }

    // How fast can references can be allocated, enqueued, and polled?
    @Test
    public void timeAllocEnqueueAndPoll() {
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            (new PhantomReference<Object>(mObject, queue)).enqueue();
            queue.poll();
        }
    }

    // How fast can references can be allocated, enqueued, and removed?
    @Test
    public void timeAllocEnqueueAndRemove() {
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            (new PhantomReference<Object>(mObject, queue)).enqueue();
            try {
                queue.remove();
            } catch (InterruptedException ie) {
            }
        }
    }

    private static class FinalizableObject {
        AtomicInteger mCount;

        FinalizableObject(AtomicInteger count) {
            this.mCount = count;
        }

        @Override
        protected void finalize() {
            mCount.incrementAndGet();
        }
    }

    // How fast does finalization run?
    @Test
    public void timeFinalization() {
        // Allocate a bunch of finalizable objects.
        int n = 0;
        AtomicInteger count = new AtomicInteger(0);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            n++;
            new FinalizableObject(count);
        }

        // Run GC so the objects will be collected for finalization.
        Runtime.getRuntime().gc();

        // Wait for finalization.
        Runtime.getRuntime().runFinalization();

        // Double check all the objects were finalized.
        int got = count.get();
        if (n != got) {
            throw new IllegalStateException(
                    String.format("Only %d of %d objects finalized?", got, n));
        }
    }
}
