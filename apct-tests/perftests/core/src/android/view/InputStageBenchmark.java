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

package android.view;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.PerfTestActivity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
@RunWith(Parameterized.class)
public class InputStageBenchmark {
    @Parameterized.Parameters(name = "mShowIme({0}), mHandlePreIme({1})")
    public static Collection cases() {
        return Arrays.asList(new Object[][] {
                { false /* no ime */, false /* skip preime */},
                { true /* show ime */, false /* skip preime */},
                { true /* show ime */, true /* handle preime */}
        });
    }

    @Rule
    public final ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameterized.Parameter(0)
    public boolean mShowIme;
    @Parameterized.Parameter(1)
    public boolean mHandlePreIme;

    private Instrumentation mInstrumentation;
    private Window mWindow;
    private CountDownLatch mWaitForReceiveInput;
    private static final long TIMEOUT_MS = 5000;

    class InstrumentedView extends View {
        InstrumentedView(Context context) {
            super(context);
            setFocusable(true);
        }

        @Override
        public boolean dispatchKeyEventPreIme(KeyEvent event) {
            if (mHandlePreIme) {
                mWaitForReceiveInput.countDown();
            }
            return mHandlePreIme;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            mWaitForReceiveInput.countDown();
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            mWaitForReceiveInput.countDown();
            return true;
        }
    }

    class InstrumentedEditText extends EditText {
        InstrumentedEditText(Context context) {
            super(context);
            setFocusable(true);
        }

        @Override
        public boolean dispatchKeyEventPreIme(KeyEvent event) {
            if (mHandlePreIme) {
                mWaitForReceiveInput.countDown();
            }
            return mHandlePreIme;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            mWaitForReceiveInput.countDown();
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            mWaitForReceiveInput.countDown();
            return true;
        }
    }

    private CountDownLatch showSoftKeyboard(View view) {
        final CountDownLatch waitForIme = new CountDownLatch(1);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (insets.isVisible(WindowInsets.Type.ime())) {
                waitForIme.countDown();
            }
            return insets;
        });

        assertTrue("Failed to request focus.", view.requestFocus());
        final InputMethodManager imm =
                mActivityRule.getActivity().getSystemService(InputMethodManager.class);
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);

        return waitForIme;
    }

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        final Activity activity = mActivityRule.getActivity();

        final CountDownLatch[] waitForIme = new CountDownLatch[1];
        mInstrumentation.runOnMainSync(() -> {
            mWindow = mActivityRule.getActivity().getWindow();

            if (mShowIme) {
                final EditText edit = new InstrumentedEditText(activity);
                mWindow.setContentView(edit);
                waitForIme[0] = showSoftKeyboard(edit);
            } else {
                final View v = new InstrumentedView(activity);
                // set FLAG_LOCAL_FOCUS_MODE to prevent delivering input events to the ime
                // in ImeInputStage.
                mWindow.addFlags(WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE);
                mWindow.setContentView(v);
                assertTrue("Failed to request focus.", v.requestFocus());
            }
        });
        if (waitForIme[0] != null) {
            try {
                assertTrue("Failed to show InputMethod.",
                        waitForIme[0].await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        mInstrumentation.waitForIdleSync();
    }

    private void injectInputEvent(InputEvent event) {
        mWaitForReceiveInput = new CountDownLatch(1);
        mInstrumentation.runOnMainSync(() -> mWindow.injectInputEvent(event));
        try {
            mWaitForReceiveInput.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testKeyEvent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            final KeyEvent eventDown =
                    new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACKSLASH);
            injectInputEvent(eventDown);

            state.pauseTiming();
            final KeyEvent eventUp =
                    new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACKSLASH);
            injectInputEvent(eventUp);
            state.resumeTiming();
        }
    }

    @Test
    public void testMotionEvent() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Rect contentFrame = new Rect();
        mInstrumentation.runOnMainSync(() ->
                mWindow.getDecorView().getBoundsOnScreen(contentFrame));
        final int x = contentFrame.centerX();
        final int y = contentFrame.centerY();
        final long eventTime = SystemClock.uptimeMillis();

        while (state.keepRunning()) {
            final MotionEvent eventDown = MotionEvent.obtain(eventTime, eventTime,
                    MotionEvent.ACTION_DOWN, x, y,
                    1.0f /* pressure */, 1.0f /* size */, 0 /* metaState */,
                    1.0f /* xPrecision */, 1.0f /* yPrecision */,
                    0 /* deviceId */, 0 /* edgeFlags */,
                    InputDevice.SOURCE_TOUCHSCREEN, DEFAULT_DISPLAY);
            injectInputEvent(eventDown);

            state.pauseTiming();
            final MotionEvent eventUp = MotionEvent.obtain(eventTime, eventTime,
                    MotionEvent.ACTION_UP, x, y,
                    1.0f /* pressure */, 1.0f /* size */, 0 /* metaState */,
                    1.0f /* xPrecision */, 1.0f /* yPrecision */,
                    0 /* deviceId */, 0 /* edgeFlags */,
                    InputDevice.SOURCE_TOUCHSCREEN, DEFAULT_DISPLAY);
            injectInputEvent(eventUp);
            state.resumeTiming();
        }
    }
}
