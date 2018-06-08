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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class GarbageMonitorTest extends SysuiTestCase {

    private LeakReporter mLeakReporter;
    private TrackedGarbage mTrackedGarbage;
    private TestableGarbageMonitor mGarbageMonitor;

    @Before
    public void setup() {
        mTrackedGarbage = mock(TrackedGarbage.class);
        mLeakReporter = mock(LeakReporter.class);
        mGarbageMonitor =
                new TestableGarbageMonitor(
                        mContext,
                        TestableLooper.get(this).getLooper(),
                        new LeakDetector(null, mTrackedGarbage, null),
                        mLeakReporter);
    }

    @Test
    public void testCallbacks_getScheduled() {
        mGarbageMonitor.startLeakMonitor();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
    }

    @Test
    public void testNoGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(0);

        mGarbageMonitor.startLeakMonitor();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testALittleGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(4);

        mGarbageMonitor.startLeakMonitor();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testTransientGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(100);

        mGarbageMonitor.startLeakMonitor();
        mGarbageMonitor.runInspectCallback();

        when(mTrackedGarbage.countOldGarbage()).thenReturn(0);

        mGarbageMonitor.runReinspectCallback();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testLotsOfPersistentGarbage_dumps() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(100);

        mGarbageMonitor.startLeakMonitor();
        mGarbageMonitor.runCallbacksOnce();

        verify(mLeakReporter).dumpLeak(anyInt());
    }

    private static class TestableGarbageMonitor extends GarbageMonitor {
        public TestableGarbageMonitor(
                Context context,
                Looper looper,
                LeakDetector leakDetector,
                LeakReporter leakReporter) {
            super(context, looper, leakDetector, leakReporter);
        }

        void runInspectCallback() {
            startLeakMonitor();
        }

        void runReinspectCallback() {
            reinspectGarbageAfterGc();
        }

        void runCallbacksOnce() {
            // Note that TestableLooper doesn't currently support delayed messages so we need to run
            // callbacks explicitly.
            runInspectCallback();
            runReinspectCallback();
        }
    }
}