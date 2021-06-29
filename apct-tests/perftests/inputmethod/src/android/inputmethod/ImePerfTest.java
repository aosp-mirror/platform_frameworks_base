/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.inputmethod;

import static android.perftests.utils.ManualBenchmarkState.StatsReport;
import static android.perftests.utils.PerfTestActivity.ID_EDITOR;
import static android.perftests.utils.TestUtils.getOnMainSync;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertTrue;

import android.annotation.UiThread;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Process;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.ManualBenchmarkState.ManualBenchmarkTest;
import android.perftests.utils.PerfManualStatusReporter;
import android.perftests.utils.TraceMarkParser;
import android.perftests.utils.TraceMarkParser.TraceMarkSlice;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.LargeTest;

import com.android.compatibility.common.util.PollingCheck;

import junit.framework.Assert;

import org.junit.Rule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Measure the performance of internal methods in Input Method framework by trace tag. */
@LargeTest
public class ImePerfTest extends ImePerfTestBase
        implements ManualBenchmarkState.CustomizedIterationListener {
    private static final String TAG = ImePerfTest.class.getSimpleName();
    private static final long ANIMATION_NOT_STARTED = -1;

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @Rule
    public final PerfTestActivityRule mActivityRule = new PerfTestActivityRule();

    /**
     * IMF common methods to log for show/hide in trace.
     */
    private String[] mCommonMethods = {
            "IC.pendingAnim",
            "IMMS.applyImeVisibility",
            "applyPostLayoutPolicy",
            "applyWindowSurfaceChanges",
            "ISC.onPostLayout"
    };

    /** IMF show methods to log in trace. */
    private String[] mShowMethods = {
            "IC.showRequestFromIme",
            "IC.showRequestFromApi",
            "IC.showRequestFromApiToImeReady",
            "IC.pendingAnim",
            "IMMS.applyImeVisibility",
            "IMMS.showMySoftInput",
            "IMMS.showSoftInput",
            "IMS.showSoftInput",
            "IMS.startInput",
            "WMS.showImePostLayout",
            "IMS.updateFullscreenMode",
            "IMS.onComputeInsets",
            "IMS.showWindow"
    };

    /** IMF show methods to log in trace. */
    private String[] mShowMethodsCold = {
            "IMS.bindInput",
            "IMS.initializeInternal",
            "IMS.restartInput",
            "IMS.onCreate",
            "IMS.initSoftInputWindow",
            "IMS.resetStateForNewConfiguration",
            "IMMS.onServiceConnected",
            "IMMS.sessionCreated",
            "IMMS.startInputOrWindowGainedFocus"
    };

    /** IMF hide lifecycle methods to log in trace. */
    private String[] mHideMethods = {
            "IC.hideRequestFromIme",
            "IC.hideRequestFromApi",
            "IMMS.hideMySoftInput",
            "IMMS.hideSoftInput",
            "IMS.hideSoftInput",
            "WMS.hideIme"
    };

    /**
     * IMF methods to log in trace.
     */
    private TraceMarkParser mTraceMethods;

    private boolean mIsTraceStarted;

    /**
     * Ime Session for {@link BaselineIme}.
     */
    private static class ImeSession implements AutoCloseable {

        private static final long TIMEOUT = 2000;
        private final ComponentName mImeName;
        private Context mContext = getInstrumentation().getContext();

        ImeSession(ComponentName ime) throws Exception {
            mImeName = ime;
            // using adb, enable and set Baseline IME.
            executeShellCommand("ime reset");
            executeShellCommand("ime enable " + ime.flattenToShortString());
            executeShellCommand("ime set " + ime.flattenToShortString());
            PollingCheck.check("Make sure that BaselineIme becomes available "
                    + getCurrentInputMethodId(), TIMEOUT,
                    () -> ime.equals(getCurrentInputMethodId()));
        }

        @Override
        public void close() throws Exception {
            executeShellCommand("ime reset");
            PollingCheck.check("Make sure that Baseline IME becomes unavailable", TIMEOUT, () ->
                    mContext.getSystemService(InputMethodManager.class)
                            .getEnabledInputMethodList()
                            .stream()
                            .noneMatch(info -> mImeName.equals(info.getComponent())));
        }

        @Nullable
        private ComponentName getCurrentInputMethodId() {
            return ComponentName.unflattenFromString(
                    Settings.Secure.getString(mContext.getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD));
        }
    }

    /**
     * A minimal baseline IME (that has a single static view) used to measure IMF latency.
     */
    public static class BaselineIme extends InputMethodService {

        public static final int HEIGHT_DP = 100;
        private static int sPid;

        @Override
        public View onCreateInputView() {
            final ViewGroup view = new FrameLayout(this);
            final View inner = new View(this);
            final float density = getResources().getDisplayMetrics().density;
            final int height = (int) (HEIGHT_DP * density);
            view.setPadding(0, 0, 0, 0);
            view.addView(inner, new FrameLayout.LayoutParams(MATCH_PARENT, height));
            inner.setBackgroundColor(0xff01fe10); // green
            sPid = Process.myPid();
            return view;
        }

        static int getPid() {
            return sPid;
        }

        static ComponentName getName(Context context) {
            return new ComponentName(context, BaselineIme.class);
        }
    }

    @Test
    @ManualBenchmarkTest(
            targetTestDurationNs = 10 * TIME_1_S_IN_NS,
            statsReport = @StatsReport(
                    flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                            | StatsReport.FLAG_MIN | StatsReport.FLAG_MAX
                            | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testShowImeWarm() throws Throwable {
        testShowOrHideImeWarm(true /* show */);
    }

    @Test
    @ManualBenchmarkTest(
            targetTestDurationNs = 10 * TIME_1_S_IN_NS,
            statsReport = @StatsReport(
                    flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                            | StatsReport.FLAG_MIN | StatsReport.FLAG_MAX
                            | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testHideIme() throws Throwable {
        testShowOrHideImeWarm(false /* show */);
    }

    @Test
    @ManualBenchmarkTest(
            targetTestDurationNs = 10 * TIME_1_S_IN_NS,
            statsReport = @StatsReport(
                    flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                            | StatsReport.FLAG_MIN | StatsReport.FLAG_MAX
                            | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testShowImeCold() throws Throwable {
        mTraceMethods = new TraceMarkParser(
                buildArray(mCommonMethods, mShowMethods, mShowMethodsCold));

        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
        if (state.isWarmingUp()) {
            // we don't need to warmup for cold start.
            return;
        }

        long measuredTimeNs = 0;
        while (state.keepRunning(measuredTimeNs)) {
            killBaselineIme();
            try (ImeSession imeSession = new ImeSession(BaselineIme.getName(
                    getInstrumentation().getContext()))) {
                final AtomicReference<CountDownLatch> latchStart = new AtomicReference<>();
                final Activity activity = getActivityWithFocus();

                setImeListener(activity, latchStart, null /* latchEnd */);
                latchStart.set(new CountDownLatch(1));

                if (!mIsTraceStarted) {
                    startAsyncAtrace();
                }

                final WindowInsetsController controller =
                        activity.getWindow().getDecorView().getWindowInsetsController();
                AtomicLong startTime = new AtomicLong();
                activity.runOnUiThread(() -> {
                    startTime.set(SystemClock.elapsedRealtimeNanos());
                    controller.show(WindowInsets.Type.ime());
                });

                measuredTimeNs = waitForAnimationStart(latchStart, startTime);
                mActivityRule.finishActivity();
            }
        }
        stopAsyncAtrace();
        addResultToState(state);
    }

    private void killBaselineIme() {
        assertTrue("PID of test and IME can't be same",
                Process.myPid() != BaselineIme.getPid());
        Process.killProcess(BaselineIme.getPid());
    }

    private void testShowOrHideImeWarm(final boolean show) throws Throwable {
        mTraceMethods = new TraceMarkParser(buildArray(
                mCommonMethods, show ? mShowMethods : mHideMethods));
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
        long measuredTimeNs = 0;
        try (ImeSession imeSession = new ImeSession(BaselineIme.getName(
                getInstrumentation().getContext()))) {
            final AtomicReference<CountDownLatch> latchStart = new AtomicReference<>();
            final AtomicReference<CountDownLatch> latchEnd = new AtomicReference<>();
            final Activity activity = getActivityWithFocus();

            // call IME show/hide
            final WindowInsetsController controller =
                    activity.getWindow().getDecorView().getWindowInsetsController();

            while (state.keepRunning(measuredTimeNs)) {
                setImeListener(activity, latchStart, latchEnd);
                latchStart.set(new CountDownLatch(show ? 1 : 2));
                latchEnd.set(new CountDownLatch(2));
                // For measuring hide, lets show IME first.
                if (!show) {
                    AtomicBoolean showCalled = new AtomicBoolean();
                    getInstrumentation().runOnMainSync(() -> {
                        if (!isImeVisible(activity)) {
                            controller.show(WindowInsets.Type.ime());
                            showCalled.set(true);
                        }
                    });
                    if (showCalled.get()) {
                        PollingCheck.check("IME show animation should finish ", TIMEOUT_1_S_IN_MS,
                                () -> latchStart.get().getCount() == 1
                                        && latchEnd.get().getCount() == 1);
                    }
                }
                if (!mIsTraceStarted && !state.isWarmingUp()) {
                    startAsyncAtrace();
                    mIsTraceStarted = true;
                }

                AtomicLong startTime = new AtomicLong();
                AtomicBoolean unexpectedVisibility = new AtomicBoolean();
                getInstrumentation().runOnMainSync(() -> {
                    boolean isVisible = isImeVisible(activity);
                    startTime.set(SystemClock.elapsedRealtimeNanos());

                    if (show && !isVisible) {
                        controller.show(WindowInsets.Type.ime());
                    } else if (!show && isVisible) {
                        controller.hide(WindowInsets.Type.ime());
                    } else {
                        // ignore this iteration as unexpected IME visibility was encountered.
                        unexpectedVisibility.set(true);
                    }
                });

                if (!unexpectedVisibility.get()) {
                    long timeElapsed = waitForAnimationStart(latchStart, startTime);
                    if (timeElapsed != ANIMATION_NOT_STARTED) {
                        measuredTimeNs = timeElapsed;
                    }
                }

                // hide IME before next iteration.
                if (show) {
                    activity.runOnUiThread(() -> controller.hide(WindowInsets.Type.ime()));
                    try {
                        latchEnd.get().await(TIMEOUT_1_S_IN_MS * 5, TimeUnit.MILLISECONDS);
                        if (latchEnd.get().getCount() != 0
                                && getOnMainSync(() -> isImeVisible(activity))) {
                            Assert.fail("IME hide animation should finish.");
                        }
                    } catch (InterruptedException e) {
                    }
                }
            }
        } finally {
            if (mIsTraceStarted) {
                stopAsyncAtrace();
            }
        }
        mActivityRule.finishActivity();

        addResultToState(state);
    }

    @UiThread
    private boolean isImeVisible(@NonNull final Activity activity) {
        return activity.getWindow().getDecorView().getRootWindowInsets().isVisible(
                WindowInsets.Type.ime());
    }

    private long waitForAnimationStart(
            AtomicReference<CountDownLatch> latchStart, AtomicLong startTime) {
        try {
            latchStart.get().await(TIMEOUT_1_S_IN_MS * 5, TimeUnit.MILLISECONDS);
            if (latchStart.get().getCount() != 0) {
                return ANIMATION_NOT_STARTED;
            }
        } catch (InterruptedException e) { }

        return SystemClock.elapsedRealtimeNanos() - startTime.get();
    }

    private void addResultToState(ManualBenchmarkState state) {
        mTraceMethods.forAllSlices((key, slices) -> {
            for (TraceMarkSlice slice : slices) {
                state.addExtraResult(key, (long) (slice.getDurationInSeconds() * NANOS_PER_S));
            }
        });
        Log.i(TAG, String.valueOf(mTraceMethods));
    }

    private Activity getActivityWithFocus() throws Exception {
        final Activity activity = mActivityRule.launchActivity();
        PollingCheck.check("Activity onResume()", TIMEOUT_1_S_IN_MS,
                () -> activity.isResumed());

        View editor = activity.findViewById(ID_EDITOR);
        editor.requestFocus();

        // wait till editor is focused so we don't count activity/view latency.
        PollingCheck.check("Editor is focused", TIMEOUT_1_S_IN_MS,
                () -> editor.isFocused());
        getInstrumentation().waitForIdleSync();

        return activity;
    }

    private void setImeListener(Activity activity,
            @NonNull AtomicReference<CountDownLatch> latchStart,
            @Nullable AtomicReference<CountDownLatch> latchEnd) {
        // set IME animation listener
        activity.getWindow().getDecorView().setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                    @NonNull
                    @Override
                    public WindowInsetsAnimation.Bounds onStart(
                            @NonNull WindowInsetsAnimation animation,
                            @NonNull WindowInsetsAnimation.Bounds bounds) {
                        latchStart.get().countDown();
                        return super.onStart(animation, bounds);
                    }

                    @NonNull
                    @Override
                    public WindowInsets onProgress(@NonNull WindowInsets insets,
                            @NonNull List<WindowInsetsAnimation> runningAnimations) {
                        return insets;
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimation animation) {
                        super.onEnd(animation);
                        if (latchEnd != null) {
                            latchEnd.get().countDown();
                        }
                    }
                });
    }

    private void startAsyncAtrace() {
        mIsTraceStarted = true;
        // IMF uses 'wm' component for trace in InputMethodService, InputMethodManagerService,
        // WindowManagerService and 'view' for client window (InsetsController).
        // TODO(b/167947940): Consider a separate input_method atrace
        startAsyncAtrace("wm view");
    }

    private void stopAsyncAtrace() {
        if (!mIsTraceStarted) {
            return;
        }
        mIsTraceStarted = false;
        final InputStream inputStream = stopAsyncAtraceWithStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                mTraceMethods.visit(line);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read the result of stopped atrace", e);
        }
    }

    @Override
    public void onStart(int iteration) {
        // Do not capture trace when profiling because the result will be much slower.
        stopAsyncAtrace();
    }

    @Override
    public void onFinished(int iteration) {
        // do nothing.
    }
}
