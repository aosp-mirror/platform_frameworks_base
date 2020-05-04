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

package com.android.server.accessibility.magnification;

import static android.view.MotionEvent.ACTION_POINTER_DOWN;

import static com.android.server.testutils.TestUtils.strictMock;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.DebugUtils;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.magnification.MagnificationGestureHandler.ScaleChangedListener;
import com.android.server.accessibility.utils.TouchEventGenerator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.IntConsumer;

/**
 * Tests of {@link WindowMagnificationGestureHandler}.
 */
@RunWith(AndroidJUnit4.class)
public class WindowMagnificationGestureHandlerTest {

    public static final int STATE_IDLE = 1;
    public static final int STATE_SHOW_MAGNIFIER = 2;
    public static final int STATE_TWO_FINGERS_DOWN = 3;
    //TODO: Test it after can injecting Handler to GestureMatcher is available.

    public static final int FIRST_STATE = STATE_IDLE;
    public static final int LAST_STATE = STATE_TWO_FINGERS_DOWN;

    // Co-prime x and y, to potentially catch x-y-swapped errors
    public static final float DEFAULT_X = 301;
    public static final float DEFAULT_Y = 299;
    //Assume first pointer position (DEFAULT_X,DEFAULT_Y) is in the window.
    public static Rect DEFAULT_WINDOW_FRAME = new Rect(0, 0, 500,  500);
    private static final int DISPLAY_0 = 0;

    private Context mContext;
    private WindowMagnificationManager mWindowMagnificationManager;
    private MockWindowMagnificationConnection mMockConnection;
    private WindowMagnificationGestureHandler mWindowMagnificationGestureHandler;

    @Before
    public void setUp() throws RemoteException {
        mContext = InstrumentationRegistry.getContext();
        mWindowMagnificationManager = new WindowMagnificationManager(mContext, 0);
        mMockConnection = new MockWindowMagnificationConnection();
        mWindowMagnificationGestureHandler = new WindowMagnificationGestureHandler(
                mContext, mWindowMagnificationManager, mock(ScaleChangedListener.class), DISPLAY_0);
        mWindowMagnificationManager.setConnection(mMockConnection.getConnection());
        mMockConnection.getConnectionCallback().onWindowMagnifierBoundsChanged(DISPLAY_0,
                DEFAULT_WINDOW_FRAME);
        doAnswer((invocation) -> {
            mMockConnection.getConnectionCallback().onWindowMagnifierBoundsChanged(DISPLAY_0,
                    DEFAULT_WINDOW_FRAME);
            return null;
        }).when(mMockConnection.getConnection()).enableWindowMagnification(eq(DISPLAY_0),
                anyFloat(), anyFloat(), anyFloat());
        mWindowMagnificationGestureHandler.setNext(strictMock(EventStreamTransformation.class));
    }

    @After
    public void tearDown() {
        mWindowMagnificationManager.disableWindowMagnifier(DISPLAY_0, true);
    }

    @Test
    public void testInitialState_isIdle() {
        assertIn(STATE_IDLE);
    }

    /**
     * Covers following paths to get to and back between each state and {@link #STATE_IDLE}.
     * <p>
     *     <br> IDLE -> SHOW_MAGNIFIER [label="a11y\nbtn"]
     *     <br> SHOW_MAGNIFIER -> TWO_FINGER_DOWN [label="2hold"]
     *     <br> TWO_FINGER_DOWN -> SHOW_MAGNIFIER [label="release"]
     *     <br> SHOW_MAGNIFIER -> IDLE [label="a11y\nbtn"]*
     * </p>
     * This navigates between states using "canonical" paths, specified in
     * {@link #goFromStateIdleTo} (for traversing away from {@link #STATE_IDLE}) and
     * {@link #returnToNormalFrom} (for navigating back to {@link #STATE_IDLE})
     */
    @Test
    public void testEachState_isReachableAndRecoverable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            forEachState(state -> {
                goFromStateIdleTo(state);
                assertIn(state);
                returnToNormalFrom(state);
                try {
                    assertIn(STATE_IDLE);
                } catch (AssertionError e) {
                    throw new AssertionError("Failed while testing state " + stateToString(state),
                            e);
                }
            });
        });
    }

    @Test
    public void testStates_areMutuallyExclusive() {
        forEachState(state1 -> {
            forEachState(state2 -> {
                if (state1 < state2) {
                    goFromStateIdleTo(state1);
                    try {
                        assertIn(state2);
                        fail("State " + stateToString(state1) + " also implies state "
                                + stateToString(state2) + stateDump());
                    } catch (AssertionError e) {
                        // expected
                        returnToNormalFrom(state1);
                    }
                }
            });
        });
    }

    private void forEachState(IntConsumer action) {
        for (int state = FIRST_STATE; state <= LAST_STATE; state++) {
            action.accept(state);
        }
    }

    /**
     * Asserts that {@link #mWindowMagnificationGestureHandler} is in the given {@code state}
     */
    private void assertIn(int state) {
        switch (state) {

            // Asserts on separate lines for accurate stack traces
            case STATE_IDLE: {
                check(!isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                        == mWindowMagnificationGestureHandler.mDetectingState, state);
            } break;
            case STATE_SHOW_MAGNIFIER: {
                check(isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                        == mWindowMagnificationGestureHandler.mDetectingState, state);
            } break;
            case STATE_TWO_FINGERS_DOWN: {
                check(isWindowMagnifierEnabled(DISPLAY_0), state);
                check(mWindowMagnificationGestureHandler.mCurrentState
                                == mWindowMagnificationGestureHandler.mObservePanningScalingState,
                        state);
            } break;

            default: throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    /**
     * Defines a "canonical" path from {@link #STATE_IDLE} to {@code state}
     */
    private void goFromStateIdleTo(int state) {
        try {
            switch (state) {
                case STATE_IDLE: {
                    // no op
                } break;
                case STATE_SHOW_MAGNIFIER: {
                    triggerShortcut();
                } break;
                case STATE_TWO_FINGERS_DOWN: {
                    goFromStateIdleTo(STATE_SHOW_MAGNIFIER);
                    send(downEvent());
                    //Second finger is outside the window.
                    send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_WINDOW_FRAME.right + 10,
                            DEFAULT_WINDOW_FRAME.bottom + 10));
                } break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to go to state " + stateToString(state), t);
        }
    }

    /**
     * Defines a "canonical" path from {@code state} to {@link #STATE_IDLE}
     */
    private void returnToNormalFrom(int state) {
        switch (state) {
            case STATE_IDLE: {
                // no op
            } break;
            case STATE_SHOW_MAGNIFIER: {
                mWindowMagnificationManager.disableWindowMagnifier(DISPLAY_0, false);
            } break;
            case STATE_TWO_FINGERS_DOWN: {
                send(upEvent());
                returnToNormalFrom(STATE_SHOW_MAGNIFIER);
            } break;
            default: throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    private void check(boolean condition, int expectedState) {
        if (!condition) {
            fail("Expected to be in state " + stateToString(expectedState) + stateDump());
        }
    }

    private boolean isWindowMagnifierEnabled(int displayId) {
        return mWindowMagnificationManager.isWindowMagnifierEnabled(displayId);
    }

    private static String stateToString(int state) {
        return DebugUtils.valueToString(WindowMagnificationGestureHandlerTest.class, "STATE_",
                state);
    }

    private void triggerShortcut() {
        mWindowMagnificationGestureHandler.notifyShortcutTriggered();
    }

    private void send(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            mWindowMagnificationGestureHandler.onMotionEvent(event, event, /* policyFlags */ 0);
        } catch (Throwable t) {
            throw new RuntimeException("Exception while handling " + event, t);
        }
    }

    private MotionEvent downEvent() {
        return TouchEventGenerator.downEvent(DISPLAY_0, DEFAULT_X, DEFAULT_Y);
    }

    private MotionEvent upEvent() {
        return upEvent(DEFAULT_X, DEFAULT_Y);
    }

    private MotionEvent upEvent(float x, float y) {
        return TouchEventGenerator.upEvent(DISPLAY_0, x, y);
    }

    private MotionEvent pointerEvent(int action, float x, float y) {
        final MotionEvent.PointerCoords defPointerCoords = new MotionEvent.PointerCoords();
        defPointerCoords.x = DEFAULT_X;
        defPointerCoords.y = DEFAULT_Y;
        final MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
        pointerCoords.x = x;
        pointerCoords.y = y;
        return TouchEventGenerator.pointerDownEvent(DISPLAY_0, defPointerCoords, pointerCoords);
    }

    private String stateDump() {
        return "\nCurrent state dump:\n" + mWindowMagnificationGestureHandler.mCurrentState;
    }
}
