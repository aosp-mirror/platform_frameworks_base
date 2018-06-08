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
 * limitations under the License.
 */

package com.android.server.accessibility;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;

import static com.android.server.testutils.TestUtils.strictMock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Message;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.DebugUtils;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.IntConsumer;


/**
 * Tests the state transitions of {@link MagnificationGestureHandler}
 *
 * Here's a dot graph describing the transitions being tested:
 * {@code
 *      digraph {
 *          IDLE -> SHORTCUT_TRIGGERED [label="a11y\nbtn"]
 *          SHORTCUT_TRIGGERED -> IDLE [label="a11y\nbtn"]
 *          IDLE -> DOUBLE_TAP [label="2tap"]
 *          DOUBLE_TAP -> IDLE [label="timeout"]
 *          DOUBLE_TAP -> TRIPLE_TAP_AND_HOLD [label="down"]
 *          SHORTCUT_TRIGGERED -> TRIPLE_TAP_AND_HOLD [label="down"]
 *          TRIPLE_TAP_AND_HOLD -> ZOOMED [label="up"]
 *          TRIPLE_TAP_AND_HOLD -> DRAGGING_TMP [label="hold/\nswipe"]
 *          DRAGGING_TMP -> IDLE [label="release"]
 *          ZOOMED -> ZOOMED_DOUBLE_TAP [label="2tap"]
 *          ZOOMED_DOUBLE_TAP -> ZOOMED [label="timeout"]
 *          ZOOMED_DOUBLE_TAP -> DRAGGING [label="hold"]
 *          ZOOMED_DOUBLE_TAP -> IDLE [label="tap"]
 *          DRAGGING -> ZOOMED [label="release"]
 *          ZOOMED -> IDLE [label="a11y\nbtn"]
 *          ZOOMED -> PANNING [label="2hold"]
 *          PANNING -> PANNING_SCALING [label="pinch"]
 *          PANNING_SCALING -> ZOOMED [label="release"]
 *          PANNING -> ZOOMED [label="release"]
 *      }
 * }
 */
@RunWith(AndroidJUnit4.class)
public class MagnificationGestureHandlerTest {

    public static final int STATE_IDLE = 1;
    public static final int STATE_ZOOMED = 2;
    public static final int STATE_2TAPS = 3;
    public static final int STATE_ZOOMED_2TAPS = 4;
    public static final int STATE_SHORTCUT_TRIGGERED = 5;
    public static final int STATE_DRAGGING_TMP = 6;
    public static final int STATE_DRAGGING = 7;
    public static final int STATE_PANNING = 8;
    public static final int STATE_SCALING_AND_PANNING = 9;


    public static final int FIRST_STATE = STATE_IDLE;
    public static final int LAST_STATE = STATE_SCALING_AND_PANNING;

    // Co-prime x and y, to potentially catch x-y-swapped errors
    public static final float DEFAULT_X = 301;
    public static final float DEFAULT_Y = 299;

    private Context mContext;
    private AccessibilityManagerService mAms;
    private MagnificationController mMagnificationController;
    private OffsettableClock mClock;
    private MagnificationGestureHandler mMgh;
    private TestHandler mHandler;

    private long mLastDownTime = Integer.MIN_VALUE;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mAms = new AccessibilityManagerService(mContext);
        mMagnificationController = new MagnificationController(
                mContext, mAms, /* lock */ new Object()) {
            @Override
            public boolean magnificationRegionContains(float x, float y) {
                return true;
            }

            @Override
            void setForceShowMagnifiableBounds(boolean show) {}
        };
        mMagnificationController.mRegistered = true;
        mClock = new OffsettableClock.Stopped();

        boolean detectTripleTap = true;
        boolean detectShortcutTrigger = true;
        mMgh = newInstance(detectTripleTap, detectShortcutTrigger);
    }

    @NonNull
    private MagnificationGestureHandler newInstance(boolean detectTripleTap,
            boolean detectShortcutTrigger) {
        MagnificationGestureHandler h = new MagnificationGestureHandler(
                mContext, mMagnificationController,
                detectTripleTap, detectShortcutTrigger);
        mHandler = new TestHandler(h.mDetectingState, mClock) {
            @Override
            protected String messageToString(Message m) {
                return DebugUtils.valueToString(
                        MagnificationGestureHandler.DetectingState.class, "MESSAGE_", m.what);
            }
        };
        h.mDetectingState.mHandler = mHandler;
        h.setNext(strictMock(EventStreamTransformation.class));
        return h;
    }

    @Test
    public void testInitialState_isIdle() {
        assertIn(STATE_IDLE);
    }

    /**
     * Covers paths to get to and back between each state and {@link #STATE_IDLE}
     * This navigates between states using "canonical" paths, specified in
     * {@link #goFromStateIdleTo} (for traversing away from {@link #STATE_IDLE}) and
     * {@link #returnToNormalFrom} (for navigating back to {@link #STATE_IDLE})
     */
    @Test
    public void testEachState_isReachableAndRecoverable() {
        forEachState(state -> {
            goFromStateIdleTo(state);
            assertIn(state);

            returnToNormalFrom(state);
            try {
                assertIn(STATE_IDLE);
            } catch (AssertionError e) {
                throw new AssertionError("Failed while testing state " + stateToString(state), e);
            }
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

    @Test
    public void testTransitionToDelegatingStateAndClear_preservesShortcutTriggeredState() {
        mMgh.mDetectingState.transitionToDelegatingStateAndClear();
        assertFalse(mMgh.mDetectingState.mShortcutTriggered);

        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
        mMgh.mDetectingState.transitionToDelegatingStateAndClear();
        assertTrue(mMgh.mDetectingState.mShortcutTriggered);
    }

    /**
     * Covers edges of the graph not covered by "canonical" transitions specified in
     * {@link #goFromStateIdleTo} and {@link #returnToNormalFrom}
     */
    @SuppressWarnings("Convert2MethodRef")
    @Test
    public void testAlternativeTransitions_areWorking() {
        // A11y button followed by a tap&hold turns temporary "viewport dragging" zoom on
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> {
            send(downEvent());
            fastForward1sec();
        }, STATE_DRAGGING_TMP);

        // A11y button followed by a tap turns zoom on
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> tap(), STATE_ZOOMED);

        // A11y button pressed second time negates the 1st press
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> triggerShortcut(), STATE_IDLE);

        // A11y button turns zoom off
        assertTransition(STATE_ZOOMED, () -> triggerShortcut(), STATE_IDLE);


        // Double tap times out while zoomed
        assertTransition(STATE_ZOOMED_2TAPS, () -> {
            allowEventDelegation();
            fastForward1sec();
        }, STATE_ZOOMED);

        // tap+tap+swipe doesn't get delegated
        assertTransition(STATE_2TAPS, () -> swipe(), STATE_IDLE);

        // tap+tap+swipe initiates viewport dragging immediately
        assertTransition(STATE_2TAPS, () -> swipeAndHold(), STATE_DRAGGING_TMP);
    }

    @Test
    public void testNonTransitions_dontChangeState() {
        // ACTION_POINTER_DOWN triggers event delegation if not magnifying
        assertStaysIn(STATE_IDLE, () -> {
            allowEventDelegation();
            send(downEvent());
            send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_X * 2, DEFAULT_Y));
        });

        // Long tap breaks the triple-tap detection sequence
        Runnable tapAndLongTap = () -> {
            allowEventDelegation();
            tap();
            longTap();
        };
        assertStaysIn(STATE_IDLE, tapAndLongTap);
        assertStaysIn(STATE_ZOOMED, tapAndLongTap);

        // Triple tap with delays in between doesn't count
        Runnable slow3tap = () -> {
            tap();
            fastForward1sec();
            tap();
            fastForward1sec();
            tap();
        };
        assertStaysIn(STATE_IDLE, slow3tap);
        assertStaysIn(STATE_ZOOMED, slow3tap);
    }

    @Test
    public void testDisablingTripleTap_removesInputLag() {
        mMgh = newInstance(/* detect3tap */ false, /* detectShortcut */ true);
        goFromStateIdleTo(STATE_IDLE);
        allowEventDelegation();
        tap();
        // no fast forward
        verify(mMgh.getNext(), times(2)).onMotionEvent(any(), any(), anyInt());
    }

    @Test
    public void testTripleTapAndHold_zoomsImmediately() {
        assertZoomsImmediatelyOnSwipeFrom(STATE_2TAPS);
        assertZoomsImmediatelyOnSwipeFrom(STATE_SHORTCUT_TRIGGERED);
    }

    private void assertZoomsImmediatelyOnSwipeFrom(int state) {
        goFromStateIdleTo(state);
        swipeAndHold();
        assertIn(STATE_DRAGGING_TMP);
        returnToNormalFrom(STATE_DRAGGING_TMP);
    }

    private void assertTransition(int fromState, Runnable transitionAction, int toState) {
        goFromStateIdleTo(fromState);
        transitionAction.run();
        assertIn(toState);
        returnToNormalFrom(toState);
    }

    private void assertStaysIn(int state, Runnable action) {
        assertTransition(state, action, state);
    }

    private void forEachState(IntConsumer action) {
        for (int state = FIRST_STATE; state <= LAST_STATE; state++) {
            action.accept(state);
        }
    }

    private void allowEventDelegation() {
        doNothing().when(mMgh.getNext()).onMotionEvent(any(), any(), anyInt());
    }

    private void fastForward1sec() {
        fastForward(1000);
    }

    private void fastForward(int ms) {
        mClock.fastForward(ms);
        mHandler.timeAdvance();
    }

    /**
     * Asserts that {@link #mMgh the handler} is in the given {@code state}
     */
    private void assertIn(int state) {
        switch (state) {

            // Asserts on separate lines for accurate stack traces

            case STATE_IDLE: {
                check(tapCount() < 2, state);
                check(!mMgh.mDetectingState.mShortcutTriggered, state);
                check(!isZoomed(), state);
            } break;
            case STATE_ZOOMED: {
                check(isZoomed(), state);
                check(tapCount() < 2, state);
            } break;
            case STATE_2TAPS: {
                check(!isZoomed(), state);
                check(tapCount() == 2, state);
            } break;
            case STATE_ZOOMED_2TAPS: {
                check(isZoomed(), state);
                check(tapCount() == 2, state);
            } break;
            case STATE_DRAGGING: {
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mViewportDraggingState,
                        state);
                check(mMgh.mViewportDraggingState.mZoomedInBeforeDrag, state);
            } break;
            case STATE_DRAGGING_TMP: {
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mViewportDraggingState,
                        state);
                check(!mMgh.mViewportDraggingState.mZoomedInBeforeDrag, state);
            } break;
            case STATE_SHORTCUT_TRIGGERED: {
                check(mMgh.mDetectingState.mShortcutTriggered, state);
                check(!isZoomed(), state);
            } break;
            case STATE_PANNING: {
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mPanningScalingState,
                        state);
                check(!mMgh.mPanningScalingState.mScaling, state);
            } break;
            case STATE_SCALING_AND_PANNING: {
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mPanningScalingState,
                        state);
                check(mMgh.mPanningScalingState.mScaling, state);
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
                    mMgh.clearAndTransitionToStateDetecting();
                } break;
                case STATE_2TAPS: {
                    goFromStateIdleTo(STATE_IDLE);
                    tap();
                    tap();
                } break;
                case STATE_ZOOMED: {
                    if (mMgh.mDetectTripleTap) {
                        goFromStateIdleTo(STATE_2TAPS);
                        tap();
                    } else {
                        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
                        tap();
                    }
                } break;
                case STATE_ZOOMED_2TAPS: {
                    goFromStateIdleTo(STATE_ZOOMED);
                    tap();
                    tap();
                } break;
                case STATE_DRAGGING: {
                    goFromStateIdleTo(STATE_ZOOMED_2TAPS);
                    send(downEvent());
                    fastForward1sec();
                } break;
                case STATE_DRAGGING_TMP: {
                    goFromStateIdleTo(STATE_2TAPS);
                    send(downEvent());
                    fastForward1sec();
                } break;
                case STATE_SHORTCUT_TRIGGERED: {
                    goFromStateIdleTo(STATE_IDLE);
                    triggerShortcut();
                } break;
                case STATE_PANNING: {
                    goFromStateIdleTo(STATE_ZOOMED);
                    send(downEvent());
                    send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_X * 2, DEFAULT_Y));
                } break;
                case STATE_SCALING_AND_PANNING: {
                    goFromStateIdleTo(STATE_PANNING);
                    send(pointerEvent(ACTION_MOVE, DEFAULT_X * 2, DEFAULT_Y * 3));
                    send(pointerEvent(ACTION_MOVE, DEFAULT_X * 2, DEFAULT_Y * 4));
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
            case STATE_2TAPS: {
                allowEventDelegation();
                fastForward1sec();
            } break;
            case STATE_ZOOMED: {
                if (mMgh.mDetectTripleTap) {
                    tap();
                    tap();
                    returnToNormalFrom(STATE_ZOOMED_2TAPS);
                } else {
                    triggerShortcut();
                }
            } break;
            case STATE_ZOOMED_2TAPS: {
                tap();
            } break;
            case STATE_DRAGGING: {
                send(upEvent());
                returnToNormalFrom(STATE_ZOOMED);
            } break;
            case STATE_DRAGGING_TMP: {
                send(upEvent());
            } break;
            case STATE_SHORTCUT_TRIGGERED: {
                triggerShortcut();
            } break;
            case STATE_PANNING: {
                send(pointerEvent(ACTION_POINTER_UP, DEFAULT_X * 2, DEFAULT_Y));
                send(upEvent());
                returnToNormalFrom(STATE_ZOOMED);
            } break;
            case STATE_SCALING_AND_PANNING: {
                returnToNormalFrom(STATE_PANNING);
            } break;
            default: throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    private void check(boolean condition, int expectedState) {
        if (!condition) {
            fail("Expected to be in state " + stateToString(expectedState) + stateDump());
        }
    }

    private boolean isZoomed() {
        return mMgh.mMagnificationController.isMagnifying();
    }

    private int tapCount() {
        return mMgh.mDetectingState.tapCount();
    }

    private static String stateToString(int state) {
        return DebugUtils.valueToString(MagnificationGestureHandlerTest.class, "STATE_", state);
    }

    private void tap() {
        send(downEvent());
        send(upEvent());
    }

    private void swipe() {
        swipeAndHold();
        send(upEvent());
    }

    private void swipeAndHold() {
        send(downEvent());
        send(moveEvent(DEFAULT_X * 2, DEFAULT_Y * 2));
    }

    private void longTap() {
        send(downEvent());
        fastForward(2000);
        send(upEvent());
    }

    private void triggerShortcut() {
        mMgh.notifyShortcutTriggered();
    }

    private void send(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            mMgh.onMotionEvent(event, event, /* policyFlags */ 0);
        } catch (Throwable t) {
            throw new RuntimeException("Exception while handling " + event, t);
        }
        fastForward(1);
    }

    private static MotionEvent fromTouchscreen(MotionEvent ev) {
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    private MotionEvent moveEvent(float x, float y) {
        return fromTouchscreen(
        	    MotionEvent.obtain(mLastDownTime, mClock.now(), ACTION_MOVE, x, y, 0));
    }

    private MotionEvent downEvent() {
        mLastDownTime = mClock.now();
        return fromTouchscreen(MotionEvent.obtain(mLastDownTime, mLastDownTime,
                ACTION_DOWN, DEFAULT_X, DEFAULT_Y, 0));
    }

    private MotionEvent upEvent() {
        return upEvent(mLastDownTime);
    }

    private MotionEvent upEvent(long downTime) {
        return fromTouchscreen(MotionEvent.obtain(downTime, mClock.now(),
                MotionEvent.ACTION_UP, DEFAULT_X, DEFAULT_Y, 0));
    }

    private MotionEvent pointerEvent(int action, float x, float y) {
        MotionEvent.PointerProperties defPointerProperties = new MotionEvent.PointerProperties();
        defPointerProperties.id = 0;
        defPointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
        pointerProperties.id = 1;
        pointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords defPointerCoords = new MotionEvent.PointerCoords();
        defPointerCoords.x = DEFAULT_X;
        defPointerCoords.y = DEFAULT_Y;
        MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
        pointerCoords.x = x;
        pointerCoords.y = y;

        return MotionEvent.obtain(
            /* downTime */ mClock.now(),
            /* eventTime */ mClock.now(),
            /* action */ action,
            /* pointerCount */ 2,
            /* pointerProperties */ new MotionEvent.PointerProperties[] {
                        defPointerProperties, pointerProperties },
            /* pointerCoords */ new MotionEvent.PointerCoords[] { defPointerCoords, pointerCoords },
            /* metaState */ 0,
            /* buttonState */ 0,
            /* xPrecision */ 1.0f,
            /* yPrecision */ 1.0f,
            /* deviceId */ 0,
            /* edgeFlags */ 0,
            /* source */ InputDevice.SOURCE_TOUCHSCREEN,
            /* flags */ 0);
    }

    private String stateDump() {
        return "\nCurrent state dump:\n" + mMgh + "\n" + mHandler.getPendingMessages();
    }
}
