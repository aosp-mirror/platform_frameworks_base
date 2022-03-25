/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.wm;

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

import android.os.RemoteException;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.ManualBenchmarkState.ManualBenchmarkTest;
import android.perftests.utils.PerfManualStatusReporter;
import android.view.Display;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import androidx.test.filters.LargeTest;

import com.android.internal.view.BaseIWindow;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class WindowAddRemovePerfTest extends WindowManagerPerfTestBase
        implements ManualBenchmarkState.CustomizedIterationListener {

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @BeforeClass
    public static void setUpClass() {
        // Get the permission to use most window types.
        getUiAutomation().adoptShellPermissionIdentity();
    }

    @AfterClass
    public static void tearDownClass() {
        getUiAutomation().dropShellPermissionIdentity();
    }

    /** The last customized iterations will provide the information of method profiling. */
    @Override
    public void onStart(int iteration) {
        startProfiling(WindowAddRemovePerfTest.class.getSimpleName()
                + "_MethodTracing_" + iteration + ".trace");
    }

    @Override
    public void onFinished(int iteration) {
        stopProfiling();
    }

    @Test
    @ManualBenchmarkTest(warmupDurationNs = TIME_1_S_IN_NS, targetTestDurationNs = TIME_5_S_IN_NS)
    public void testAddRemoveWindow() throws Throwable {
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
        new TestWindow().runBenchmark(state);
    }

    private static class TestWindow extends BaseIWindow {
        final WindowManager.LayoutParams mLayoutParams = new WindowManager.LayoutParams();
        final InsetsVisibilities mRequestedVisibilities = new InsetsVisibilities();
        final InsetsState mOutInsetsState = new InsetsState();
        final InsetsSourceControl[] mOutControls = new InsetsSourceControl[0];

        TestWindow() {
            mLayoutParams.setTitle(TestWindow.class.getName());
            mLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            // Simulate as common phone window.
            mLayoutParams.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        }

        void runBenchmark(ManualBenchmarkState state) throws RemoteException {
            final IWindowSession session = WindowManagerGlobal.getWindowSession();
            long elapsedTimeNs = 0;
            while (state.keepRunning(elapsedTimeNs)) {
                // InputChannel cannot be reused.
                final InputChannel inputChannel = new InputChannel();

                long startTime = SystemClock.elapsedRealtimeNanos();
                session.addToDisplay(this, mLayoutParams, View.VISIBLE,
                        Display.DEFAULT_DISPLAY, mRequestedVisibilities, inputChannel,
                        mOutInsetsState, mOutControls);
                final long elapsedTimeNsOfAdd = SystemClock.elapsedRealtimeNanos() - startTime;
                state.addExtraResult("add", elapsedTimeNsOfAdd);

                startTime = SystemClock.elapsedRealtimeNanos();
                session.remove(this);
                final long elapsedTimeNsOfRemove = SystemClock.elapsedRealtimeNanos() - startTime;
                state.addExtraResult("remove", elapsedTimeNsOfRemove);

                elapsedTimeNs = elapsedTimeNsOfAdd + elapsedTimeNsOfRemove;
                inputChannel.dispose();
            }
        }
    }
}
