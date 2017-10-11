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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class GarbageMonitorTest extends SysuiTestCase {

    private LeakReporter mLeakReporter;
    private TrackedGarbage mTrackedGarbage;
    private TestableGarbageMonitor mGarbageMonitor;

    @Before
    public void setup() {
        mTrackedGarbage = mock(TrackedGarbage.class);
        mLeakReporter = mock(LeakReporter.class);
        mGarbageMonitor = new TestableGarbageMonitor(
                new LeakDetector(null, mTrackedGarbage, null),
                mLeakReporter);
    }

    @Test
    public void testCallbacks_getScheduled() {
        mGarbageMonitor.start();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
    }

    @Test
    public void testNoGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(0);

        mGarbageMonitor.start();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testALittleGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(4);

        mGarbageMonitor.start();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testTransientGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(100);

        mGarbageMonitor.start();
        mGarbageMonitor.runInspectCallback();

        when(mTrackedGarbage.countOldGarbage()).thenReturn(0);

        mGarbageMonitor.runReinspectCallback();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testLotsOfPersistentGarbage_dumps() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(100);

        mGarbageMonitor.start();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter).dumpLeak(anyInt());
    }

    private static class TestableGarbageMonitor extends GarbageMonitor {
        Runnable mInspectCallback;
        Runnable mReinspectCallback;

        public TestableGarbageMonitor(LeakDetector leakDetector,
                LeakReporter leakReporter) {
            super(null /* bgLooper */, leakDetector, leakReporter);
        }

        @Override
        void scheduleInspectGarbage(Runnable runnable) {
            assertNull("must not have more than one pending inspect callback", mInspectCallback);
            mInspectCallback = runnable;
        }

        void runInspectCallback() {
            assertNotNull("expected an inspect callback to be scheduled", mInspectCallback);
            Runnable callback = mInspectCallback;
            mInspectCallback = null;
            callback.run();
        }

        @Override
        void scheduleReinspectGarbage(Runnable runnable) {
            assertNull("must not have more than one reinspect callback", mReinspectCallback);
            mReinspectCallback = runnable;
        }

        void runReinspectCallback() {
            assertNotNull("expected a reinspect callback to be scheduled", mInspectCallback);
            maybeRunReinspectCallback();
        }

        void maybeRunReinspectCallback() {
            Runnable callback = mReinspectCallback;
            mReinspectCallback = null;
            if (callback != null) {
                callback.run();
            }
        }

        void runCallbacksOnce() {
            runInspectCallback();
            maybeRunReinspectCallback();
        }
    }
}