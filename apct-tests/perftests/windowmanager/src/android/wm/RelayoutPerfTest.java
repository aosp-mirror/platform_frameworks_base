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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.content.Context;
import android.os.RemoteException;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowRelayoutResult;
import android.widget.LinearLayout;
import android.window.ClientWindowFrames;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.IntSupplier;

@RunWith(Parameterized.class)
@LargeTest
@Presubmit
public class RelayoutPerfTest extends WindowManagerPerfTestBase
        implements BenchmarkState.CustomizedIterationListener {
    private int mIteration;

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Rule
    public final ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);

    /** This is only a placement to match the input parameters from {@link #getParameters}. */
    @Parameterized.Parameter(0)
    public String testName;

    /** The visibilities to loop for relayout. */
    @Parameterized.Parameter(1)
    public int[] visibilities;

    /**
     * Each row will be mapped into {@link #testName} and {@link #visibilities} of a new test
     * instance according to the index of the parameter.
     */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> getParameters() {
        return Arrays.asList(new Object[][] {
                { "Visible", new int[] { View.VISIBLE } },
                { "Invisible~Visible", new int[] { View.INVISIBLE, View.VISIBLE } },
                { "Gone~Visible", new int[] { View.GONE, View.VISIBLE } },
                { "Gone~Invisible", new int[] { View.GONE, View.INVISIBLE } }
        });
    }

    @Test
    public void testRelayout() throws Throwable {
        final Activity activity = mActivityRule.getActivity();
        final ContentView contentView = new ContentView(activity);
        mActivityRule.runOnUiThread(() -> activity.setContentView(contentView));
        getInstrumentation().waitForIdleSync();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
        final RelayoutRunner relayoutRunner = new RelayoutRunner(activity, contentView.getWindow(),
                () -> visibilities[mIteration++ % visibilities.length]);
        relayoutRunner.runBenchmark(state);
    }

    @Override
    public void onStart(int iteration) {
        startProfiling(RelayoutPerfTest.class.getSimpleName() + "_" + testName
                + "_MethodTracing_" + iteration + ".trace");
    }

    @Override
    public void onFinished(int iteration) {
        stopProfiling();
    }

    /** A placeholder view to get IWindow. */
    private static class ContentView extends LinearLayout {
        ContentView(Context context) {
            super(context);
        }

        @Override
        protected IWindow getWindow() {
            return super.getWindow();
        }
    }

    private static class RelayoutRunner {
        final ClientWindowFrames mOutFrames = new ClientWindowFrames();
        final MergedConfiguration mOutMergedConfiguration = new MergedConfiguration();
        final InsetsState mOutInsetsState = new InsetsState();
        final InsetsSourceControl.Array mOutControls = new InsetsSourceControl.Array();
        final WindowRelayoutResult mOutRelayoutResult;
        final IWindow mWindow;
        final View mView;
        final WindowManager.LayoutParams mParams;
        final int mWidth;
        final int mHeight;
        final SurfaceControl mOutSurfaceControl;

        final IntSupplier mViewVisibility;
        int mRelayoutSeq;
        int mFlags;

        RelayoutRunner(Activity activity, IWindow window, IntSupplier visibilitySupplier) {
            mWindow = window;
            mView = activity.getWindow().getDecorView();
            mParams = (WindowManager.LayoutParams) mView.getLayoutParams();
            mWidth = mView.getMeasuredWidth();
            mHeight = mView.getMeasuredHeight();
            mOutSurfaceControl = mView.getViewRootImpl().getSurfaceControl();
            mViewVisibility = visibilitySupplier;
            mOutRelayoutResult = new WindowRelayoutResult(mOutFrames, mOutMergedConfiguration,
                            mOutSurfaceControl, mOutInsetsState, mOutControls);
        }

        void runBenchmark(BenchmarkState state) throws RemoteException {
            final IWindowSession session = WindowManagerGlobal.getWindowSession();
            while (state.keepRunning()) {
                mRelayoutSeq++;
                session.relayout(mWindow, mParams, mWidth, mHeight, mViewVisibility.getAsInt(),
                        mFlags, mRelayoutSeq, 0 /* lastSyncSeqId */, mOutRelayoutResult);
            }
        }
    }
}
