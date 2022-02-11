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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.MessageRouterImpl;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class GarbageMonitorTest extends SysuiTestCase {

    @Mock private LeakReporter mLeakReporter;
    @Mock private TrackedGarbage mTrackedGarbage;
    @Mock private DumpManager mDumpManager;
    private GarbageMonitor mGarbageMonitor;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mGarbageMonitor =
                new GarbageMonitor(
                        mContext,
                        mFakeExecutor,
                        new MessageRouterImpl(mFakeExecutor),
                        new LeakDetector(null, mTrackedGarbage, null, mDumpManager),
                        mLeakReporter,
                        mDumpManager);
    }

    @Test
    public void testALittleGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(GarbageMonitor.GARBAGE_ALLOWANCE);

        mGarbageMonitor.reinspectGarbageAfterGc();

        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testTransientGarbage_doesntDump() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(GarbageMonitor.GARBAGE_ALLOWANCE + 1);

        // Start the leak monitor. Nothing gets reported immediately.
        mGarbageMonitor.startLeakMonitor();
        mFakeExecutor.runAllReady();
        verify(mLeakReporter, never()).dumpLeak(anyInt());

        // Garbage gets reset to 0 before the leak reporte actually gets called.
        when(mTrackedGarbage.countOldGarbage()).thenReturn(0);
        mFakeExecutor.advanceClockToLast();
        mFakeExecutor.runAllReady();

        // Therefore nothing gets dumped.
        verify(mLeakReporter, never()).dumpLeak(anyInt());
    }

    @Test
    public void testLotsOfPersistentGarbage_dumps() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(GarbageMonitor.GARBAGE_ALLOWANCE + 1);

        mGarbageMonitor.reinspectGarbageAfterGc();

        verify(mLeakReporter).dumpLeak(GarbageMonitor.GARBAGE_ALLOWANCE + 1);
    }

    @Test
    public void testLotsOfPersistentGarbage_dumpsAfterAtime() {
        when(mTrackedGarbage.countOldGarbage()).thenReturn(GarbageMonitor.GARBAGE_ALLOWANCE + 1);

        // Start the leak monitor. Nothing gets reported immediately.
        mGarbageMonitor.startLeakMonitor();
        mFakeExecutor.runAllReady();
        verify(mLeakReporter, never()).dumpLeak(anyInt());

        mFakeExecutor.advanceClockToLast();
        mFakeExecutor.runAllReady();

        verify(mLeakReporter).dumpLeak(GarbageMonitor.GARBAGE_ALLOWANCE + 1);
    }
}