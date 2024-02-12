/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;
import static android.view.MotionEvent.TOOL_TYPE_STYLUS;
import static android.view.inputmethod.Flags.initiationWithoutInputConnection;


import static org.junit.Assume.assumeFalse;

import android.app.Instrumentation;
import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Benchmark tests for {@link HandwritingInitiator}
 *
 * Build/Install/Run:
 * atest CorePerfTests:android.view.HandwritingInitiatorPerfTest
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class HandwritingInitiatorPerfTest {
    private Context mContext;
    private HandwritingInitiator mHandwritingInitiator;
    private int mTouchSlop;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Before
    public void setup() {
        final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
        final ViewConfiguration viewConfiguration = ViewConfiguration.get(mContext);
        mTouchSlop = viewConfiguration.getScaledTouchSlop();
        final InputMethodManager inputMethodManager =
                mContext.getSystemService(InputMethodManager.class);
        mHandwritingInitiator = new HandwritingInitiator(viewConfiguration, inputMethodManager);
    }

    @Test
    public void onTouchEvent_actionDown_toolTypeStylus() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_STYLUS, 10, 10, 0);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_STYLUS, 11, 11, 1);

        while (state.keepRunning()) {
            mHandwritingInitiator.onTouchEvent(downEvent);
            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(upEvent);
            state.resumeTiming();
        }
    }

    @Test
    public void onTouchEvent_actionUp_toolTypeStylus() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_STYLUS, 10, 10, 0);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_STYLUS, 11, 11, 1);

        while (state.keepRunning()) {
            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(downEvent);
            state.resumeTiming();
            mHandwritingInitiator.onTouchEvent(upEvent);
        }
    }

    @Test
    public void onTouchEvent_actionMove_toolTypeStylus() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final int initX = 10;
        final int initY = 10;
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_STYLUS, initX, initY, 0);

        final int x = initX + mTouchSlop;
        final int y = initY + mTouchSlop;
        final MotionEvent moveEvent =
                createMotionEvent(ACTION_MOVE, TOOL_TYPE_STYLUS, x, y, 1);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_STYLUS, x, y, 1);

        while (state.keepRunning()) {
            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(downEvent);
            state.resumeTiming();

            mHandwritingInitiator.onTouchEvent(moveEvent);

            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(upEvent);
            state.resumeTiming();
        }
    }

    @Test
    public void onTouchEvent_actionDown_toolTypeFinger() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_FINGER, 10, 10, 0);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_FINGER, 11, 11, 1);

        while (state.keepRunning()) {
            mHandwritingInitiator.onTouchEvent(downEvent);
            mHandwritingInitiator.onTouchEvent(upEvent);
        }
    }

    @Test
    public void onTouchEvent_actionUp_toolTypeFinger() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_FINGER, 10, 10, 0);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_FINGER, 11, 11, 1);

        while (state.keepRunning()) {
            mHandwritingInitiator.onTouchEvent(downEvent);
            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(upEvent);
            state.resumeTiming();
        }
    }

    @Test
    public void onTouchEvent_actionMove_toolTypeFinger() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final int initX = 10;
        final int initY = 10;
        final MotionEvent downEvent =
                createMotionEvent(ACTION_DOWN, TOOL_TYPE_FINGER, initX, initY, 0);

        final int x = initX + mTouchSlop;
        final int y = initY + mTouchSlop;
        final MotionEvent moveEvent =
                createMotionEvent(ACTION_MOVE, TOOL_TYPE_FINGER, x, y, 1);
        final MotionEvent upEvent =
                createMotionEvent(ACTION_UP, TOOL_TYPE_FINGER, x, y, 1);

        while (state.keepRunning()) {
            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(downEvent);
            state.resumeTiming();

            mHandwritingInitiator.onTouchEvent(moveEvent);

            state.pauseTiming();
            mHandwritingInitiator.onTouchEvent(upEvent);
            state.resumeTiming();
        }
    }

    @Test
    public void onInputConnectionCreated() {
        assumeFalse(initiationWithoutInputConnection());
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final View view = new View(mContext);
        final EditorInfo editorInfo = new EditorInfo();
        while (state.keepRunning()) {
            mHandwritingInitiator.onInputConnectionCreated(view);
            state.pauseTiming();
            mHandwritingInitiator.onInputConnectionClosed(view);
            state.resumeTiming();
        }
    }

    @Test
    public void onInputConnectionClosed() {
        assumeFalse(initiationWithoutInputConnection());
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final View view = new View(mContext);
        while (state.keepRunning()) {
            state.pauseTiming();
            mHandwritingInitiator.onInputConnectionCreated(view);
            state.resumeTiming();
            mHandwritingInitiator.onInputConnectionClosed(view);
        }
    }

    private MotionEvent createMotionEvent(int action, int toolType, int x, int y, long eventTime) {
        MotionEvent.PointerProperties[] properties = MotionEvent.PointerProperties.createArray(1);
        properties[0].toolType = toolType;

        MotionEvent.PointerCoords[] coords = MotionEvent.PointerCoords.createArray(1);
        coords[0].x = x;
        coords[0].y = y;

        return MotionEvent.obtain(0 /* downTime */, eventTime /* eventTime */, action, 1,
                properties, coords, 0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */,
                1 /* yPrecision */, 0 /* deviceId */, 0 /* edgeFlags */,
                InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
    }
}
