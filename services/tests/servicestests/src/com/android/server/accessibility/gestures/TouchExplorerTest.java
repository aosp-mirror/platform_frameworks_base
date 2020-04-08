/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.accessibility.gestures;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_HOVER_ENTER;
import static android.view.MotionEvent.ACTION_HOVER_EXIT;
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.ViewConfiguration.getDoubleTapTimeout;

import static com.android.server.accessibility.gestures.TouchState.STATE_CLEAR;
import static com.android.server.accessibility.gestures.TouchState.STATE_DELEGATING;
import static com.android.server.accessibility.gestures.TouchState.STATE_DRAGGING;
import static com.android.server.accessibility.gestures.TouchState.STATE_GESTURE_DETECTING;
import static com.android.server.accessibility.gestures.TouchState.STATE_TOUCH_EXPLORING;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.PointF;
import android.os.Looper;
import android.os.SystemClock;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.testutils.OffsettableClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TouchExplorerTest {

    private static final String LOG_TAG = "TouchExplorerTest";
    // The constant of mDetermineUserIntentTimeout.
    private static final int USER_INTENT_TIMEOUT = getDoubleTapTimeout();
    private static final int FLAG_1FINGER = 0x8000;
    private static final int FLAG_2FINGERS = 0x0100;
    private static final int FLAG_3FINGERS = 0x0200;
    private static final int FLAG_MOVING = 0x00010000;
    private static final int FLAG_MOVING_DIFF_DIRECTION = 0x00020000;

    private static final int STATE_TOUCH_EXPLORING_1FINGER = STATE_TOUCH_EXPLORING | FLAG_1FINGER;
    private static final int STATE_TOUCH_EXPLORING_2FINGER = STATE_TOUCH_EXPLORING | FLAG_2FINGERS;
    private static final int STATE_TOUCH_EXPLORING_3FINGER = STATE_TOUCH_EXPLORING | FLAG_3FINGERS;
    private static final int STATE_MOVING_2FINGERS = STATE_TOUCH_EXPLORING_2FINGER | FLAG_MOVING;
    private static final int STATE_MOVING_3FINGERS = STATE_TOUCH_EXPLORING_3FINGER | FLAG_MOVING;
    private static final int STATE_DRAGGING_2FINGERS = STATE_DRAGGING | FLAG_2FINGERS;
    private static final int STATE_PINCH_2FINGERS =
            STATE_TOUCH_EXPLORING_2FINGER | FLAG_MOVING_DIFF_DIRECTION;
    private static final float DEFAULT_X = 301f;
    private static final float DEFAULT_Y = 299f;

    private EventStreamTransformation mCaptor;
    private MotionEvent mLastEvent;
    private TestHandler mHandler;
    private TouchExplorer mTouchExplorer;
    private long mLastDownTime = Integer.MIN_VALUE;

    // mock package-private GestureManifold class
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    /**
     * {@link TouchExplorer#sendDownForAllNotInjectedPointers} injecting events with the same object
     * is resulting {@link ArgumentCaptor} to capture events with last state. Before implementation
     * change, this helper class will save copies to verify the result.
     */
    private class EventCaptor implements EventStreamTransformation {
        List<MotionEvent> mEvents = new ArrayList<>();

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mEvents.add(0, event.copy());
        }

        @Override
        public void setNext(EventStreamTransformation next) {}

        @Override
        public EventStreamTransformation getNext() {
            return null;
        }
    }

    @Before
    public void setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Context context = InstrumentationRegistry.getContext();
        AccessibilityManagerService ams = new AccessibilityManagerService(context);
        GestureManifold detector = mock(GestureManifold.class);
        mCaptor = new EventCaptor();
        mHandler = new TestHandler();
        mTouchExplorer = new TouchExplorer(context, ams, detector, mHandler);
        mTouchExplorer.setNext(mCaptor);
    }

    @Test
    public void testOneFingerMove_shouldInjectHoverEvents() {
        goFromStateClearTo(STATE_TOUCH_EXPLORING_1FINGER);
        // Wait for transiting to touch exploring state.
        mHandler.fastForward(2 * USER_INTENT_TIMEOUT);
        moveEachPointers(mLastEvent, p(10, 10));
        send(mLastEvent);
        goToStateClearFrom(STATE_TOUCH_EXPLORING_1FINGER);
        assertCapturedEvents(ACTION_HOVER_ENTER, ACTION_HOVER_MOVE, ACTION_HOVER_EXIT);
        assertState(STATE_TOUCH_EXPLORING);
    }

    /**
     * Test the case where ACTION_DOWN is followed by a number of ACTION_MOVE events that do not
     * change the coordinates.
     */
    @Test
    public void testOneFingerMoveWithExtraMoveEvents() {
        goFromStateClearTo(STATE_TOUCH_EXPLORING_1FINGER);
        // Inject a set of move events that have the same coordinates as the down event.
        moveEachPointers(mLastEvent, p(0, 0));
        send(mLastEvent);
        // Wait for transition to touch exploring state.
        mHandler.fastForward(2 * USER_INTENT_TIMEOUT);
        // Now move for real.
        moveEachPointers(mLastEvent, p(10, 10));
        send(mLastEvent);
        // One more move event with no change.
        moveEachPointers(mLastEvent, p(0, 0));
        send(mLastEvent);
        goToStateClearFrom(STATE_TOUCH_EXPLORING_1FINGER);
        assertCapturedEvents(
                ACTION_HOVER_ENTER,
                ACTION_HOVER_MOVE,
                ACTION_HOVER_MOVE,
                ACTION_HOVER_MOVE,
                ACTION_HOVER_EXIT);
    }

    /**
     * Test the case where ACTION_POINTER_DOWN is followed by a number of ACTION_MOVE events that do
     * not change the coordinates.
     */
    @Test
    public void testTwoFingerDragWithExtraMoveEvents() {
        goFromStateClearTo(STATE_DRAGGING_2FINGERS);
        // Inject a set of move events that have the same coordinates as the down event.
        moveEachPointers(mLastEvent, p(0, 0), p(0, 0));
        send(mLastEvent);
        // Now move for real.
        moveEachPointers(mLastEvent, p(10, 10), p(10, 10));
        send(mLastEvent);
        goToStateClearFrom(STATE_DRAGGING_2FINGERS);
        assertCapturedEvents(ACTION_DOWN, ACTION_MOVE, ACTION_MOVE, ACTION_UP);
    }

    @Test
    public void testUpEvent_OneFingerMove_clearStateAndInjectHoverEvents() {
        goFromStateClearTo(STATE_TOUCH_EXPLORING_1FINGER);
        moveEachPointers(mLastEvent, p(10, 10));
        send(mLastEvent);

        send(upEvent());
        // Wait for sending hover exit event to transit to clear state.
        mHandler.fastForward(USER_INTENT_TIMEOUT);

        assertCapturedEvents(ACTION_HOVER_ENTER, ACTION_HOVER_MOVE, ACTION_HOVER_EXIT);
        assertState(STATE_CLEAR);
    }

    /*
     * The gesture should be completed in USER_INTENT_TIMEOUT duration otherwise the A11y
     * touch-exploration end event runnable will be scheduled after receiving the up event.
     * The distance between start and end point is shorter than the minimum swipe distance.
     * Note that the delayed time  of each runnable is USER_INTENT_TIMEOUT.
     */
    @Test
    public void testFlickCrossViews_clearStateAndExpectedEvents() {
        final int oneThirdUserIntentTimeout = USER_INTENT_TIMEOUT / 3;
        // Touch the first view.
        send(downEvent());

        // Wait for the finger moving to the second view.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        moveEachPointers(mLastEvent, p(10, 10));
        send(mLastEvent);

        // Wait for the finger lifting from the second view.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        // Now there are three delayed Runnables, hover enter/move runnable, hover exit motion event
        // runnable and a11y interaction end event runnable. The last two runnables are scheduled
        // after sending the up event.
        send(upEvent());

        // Wait for running hover enter/move runnable. The runnable is scheduled when sending
        // the down event.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        // Wait for the views responding to hover enter/move events.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        // Simulate receiving the a11y exit event sent by the first view.
        AccessibilityEvent a11yExitEvent = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        mTouchExplorer.onAccessibilityEvent(a11yExitEvent);

        // Wait for running the hover exit event runnable. After it, touch-exploration end event
        // runnable will be scheduled.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        // Wait for the second views responding to hover exit events.
        mHandler.fastForward(oneThirdUserIntentTimeout);
        // Simulate receiving the a11y exit event sent by the second view.
        mTouchExplorer.onAccessibilityEvent(a11yExitEvent);

        assertCapturedEvents(ACTION_HOVER_ENTER, ACTION_HOVER_MOVE, ACTION_HOVER_EXIT);
        assertState(STATE_CLEAR);
    }

    @Test
    public void testTwoFingersMove_shouldDelegatingAndInjectActionDownPointerDown() {
        goFromStateClearTo(STATE_MOVING_2FINGERS);
        assertState(STATE_DELEGATING);
        goToStateClearFrom(STATE_MOVING_2FINGERS);
        assertState(STATE_CLEAR);
        assertCapturedEvents(ACTION_DOWN, ACTION_POINTER_DOWN, ACTION_POINTER_UP, ACTION_UP);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void upEventWhenInTwoFingerMove_clearsState() {
        goFromStateClearTo(STATE_MOVING_2FINGERS);

        send(upEvent());
        assertState(STATE_CLEAR);
    }

    @Test
    public void clearEventsWhenInTwoFingerMove_clearsStateAndSendsUp() {
        goFromStateClearTo(STATE_MOVING_2FINGERS);

        // Clear last event so we don't try to match against anything when cleanup events are sent
        // for the clear
        mLastEvent = null;
        mTouchExplorer.clearEvents(InputDevice.SOURCE_TOUCHSCREEN);
        assertState(STATE_CLEAR);
        List<MotionEvent> events = getCapturedEvents();
        assertCapturedEvents(ACTION_DOWN, ACTION_POINTER_DOWN, ACTION_POINTER_UP, ACTION_UP);
    }

    @Test
    public void testTwoFingersDrag_shouldDraggingAndActionDown() {
        goFromStateClearTo(STATE_DRAGGING_2FINGERS);

        assertState(STATE_DRAGGING);
        goToStateClearFrom(STATE_DRAGGING_2FINGERS);
        assertState(STATE_CLEAR);
        assertCapturedEvents(ACTION_DOWN, ACTION_UP);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testTwoFingersNotDrag_shouldDelegatingAndActionUpDownPointerDown() {
        // only from dragging state, and withMoveHistory no dragging
        goFromStateClearTo(STATE_PINCH_2FINGERS);
        assertState(STATE_DELEGATING);
        goToStateClearFrom(STATE_PINCH_2FINGERS);
        assertState(STATE_CLEAR);
        assertCapturedEvents(
                /* goto dragging state */ ACTION_DOWN,
                /* leave dragging state */ ACTION_UP,
                ACTION_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_UP,
                ACTION_UP);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testThreeFingersMove_shouldDelegatingAnd3ActionPointerDown() {
        goFromStateClearTo(STATE_MOVING_3FINGERS);
        assertState(STATE_DELEGATING);
        goToStateClearFrom(STATE_MOVING_3FINGERS);
        assertState(STATE_CLEAR);
        assertCapturedEvents(
                ACTION_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_UP,
                ACTION_POINTER_UP,
                ACTION_UP);
        assertCapturedEventsNoHistory();
    }

    private static MotionEvent fromTouchscreen(MotionEvent ev) {
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    private static PointF p(int x, int y) {
        return new PointF(x, y);
    }

    private void goFromStateClearTo(int state) {
        try {
            switch (state) {
                case STATE_CLEAR:
                    mTouchExplorer.onDestroy();
                    break;
                case STATE_TOUCH_EXPLORING_1FINGER:
                    send(downEvent());
                    break;
                case STATE_TOUCH_EXPLORING_2FINGER:
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_1FINGER);
                    send(pointerDownEvent());
                    break;
                case STATE_TOUCH_EXPLORING_3FINGER:
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    send(thirdPointerDownEvent());
                    break;
                case STATE_MOVING_2FINGERS:
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(10, 0), p(5, 10));
                    send(mLastEvent);
                    break;
                case STATE_DRAGGING_2FINGERS:
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(10, 0), p(10, 0));
                    send(mLastEvent);
                    break;
                case STATE_PINCH_2FINGERS:
                    goFromStateClearTo(STATE_DRAGGING_2FINGERS);
                    moveEachPointers(mLastEvent, p(10, 0), p(-10, 1));
                    send(mLastEvent);
                    break;
                case STATE_MOVING_3FINGERS:
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_3FINGER);
                    moveEachPointers(mLastEvent, p(1, 0), p(1, 0), p(1, 0));
                    send(mLastEvent);
                    break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Failed to go to state " + stateToString(state), t);
        }
    }

    private void goToStateClearFrom(int state) {
        try {
            switch (state) {
                case STATE_CLEAR:
                    mTouchExplorer.onDestroy();
                    break;
                case STATE_TOUCH_EXPLORING_1FINGER:
                    send(upEvent());
                    break;
                case STATE_TOUCH_EXPLORING_2FINGER:
                case STATE_MOVING_2FINGERS:
                case STATE_DRAGGING_2FINGERS:
                case STATE_PINCH_2FINGERS:
                    send(pointerUpEvent());
                    goToStateClearFrom(STATE_TOUCH_EXPLORING_1FINGER);
                    break;
                case STATE_TOUCH_EXPLORING_3FINGER:
                case STATE_MOVING_3FINGERS:
                    send(thirdPointerUpEvent());
                    goToStateClearFrom(STATE_TOUCH_EXPLORING_2FINGER);
                    break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Failed to return to clear from state " + stateToString(state), t);
        }
    }

    private void send(MotionEvent event) {
        final MotionEvent sendEvent = fromTouchscreen(event);
        mLastEvent = sendEvent;
        try {
            mTouchExplorer.onMotionEvent(sendEvent, sendEvent, /* policyFlags */ 0);
        } catch (Throwable t) {
            throw new RuntimeException("Exception while handling " + sendEvent, t);
        }
    }

    private void assertState(int expect) {
        assertEquals(
                TouchState.getStateSymbolicName(expect),
                TouchState.getStateSymbolicName(mTouchExplorer.getState().getState()));
    }

    private void assertCapturedEvents(int... actionsInOrder) {
        final int eventCount = actionsInOrder.length;
        assertEquals(eventCount, getCapturedEvents().size());
        for (int i = 0; i < eventCount; i++) {
            assertEquals(actionsInOrder[eventCount - i - 1], getCapturedEvent(i).getActionMasked());
        }
    }

    private void assertCapturedEventsNoHistory() {
        for (MotionEvent e : getCapturedEvents()) {
            assertEquals(0, e.getHistorySize());
        }
    }

    private MotionEvent getCapturedEvent(int index) {
        return getCapturedEvents().get(index);
    }

    private List<MotionEvent> getCapturedEvents() {
        return ((EventCaptor) mCaptor).mEvents;
    }

    private MotionEvent downEvent() {
        mLastDownTime = SystemClock.uptimeMillis();
        return fromTouchscreen(
                MotionEvent.obtain(
                        mLastDownTime, mLastDownTime, ACTION_DOWN, DEFAULT_X, DEFAULT_Y, 0));
    }

    private MotionEvent upEvent() {
        MotionEvent event = MotionEvent.obtainNoHistory(mLastEvent);
        event.setAction(ACTION_UP);
        return event;
    }

    private MotionEvent pointerDownEvent() {
        final int secondPointerId = 0x0100;
        final int action = ACTION_POINTER_DOWN | secondPointerId;
        final float[] x = new float[] {DEFAULT_X, DEFAULT_X + 29};
        final float[] y = new float[] {DEFAULT_Y, DEFAULT_Y + 28};
        return manyPointerEvent(action, x, y);
    }

    private MotionEvent pointerUpEvent() {
        final int secondPointerId = 0x0100;
        final int action = ACTION_POINTER_UP | secondPointerId;
        final MotionEvent event = MotionEvent.obtainNoHistory(mLastEvent);
        event.setAction(action);
        return event;
    }

    private MotionEvent thirdPointerDownEvent() {
        final int thirdPointerId = 0x0200;
        final int action = ACTION_POINTER_DOWN | thirdPointerId;
        final float[] x = new float[] {DEFAULT_X, DEFAULT_X + 29, DEFAULT_X + 59};
        final float[] y = new float[] {DEFAULT_Y, DEFAULT_Y + 28, DEFAULT_Y + 58};
        return manyPointerEvent(action, x, y);
    }

    private MotionEvent thirdPointerUpEvent() {
        final int thirdPointerId = 0x0200;
        final int action = ACTION_POINTER_UP | thirdPointerId;
        final MotionEvent event = MotionEvent.obtainNoHistory(mLastEvent);
        event.setAction(action);
        return event;
    }

    private void moveEachPointers(MotionEvent event, PointF... points) {
        final float[] x = new float[points.length];
        final float[] y = new float[points.length];
        for (int i = 0; i < points.length; i++) {
            x[i] = event.getX(i) + points[i].x;
            y[i] = event.getY(i) + points[i].y;
        }
        MotionEvent newEvent = manyPointerEvent(ACTION_MOVE, x, y);
        event.setAction(ACTION_MOVE);
        // add history count
        event.addBatch(newEvent);
    }

    private MotionEvent manyPointerEvent(int action, float[] x, float[] y) {
        return manyPointerEvent(action, x, y, mLastDownTime);
    }

    private MotionEvent manyPointerEvent(int action, float[] x, float[] y, long downTime) {
        final int len = x.length;

        final MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[len];
        for (int i = 0; i < len; i++) {
            MotionEvent.PointerProperties pointerProperty = new MotionEvent.PointerProperties();
            pointerProperty.id = i;
            pointerProperty.toolType = MotionEvent.TOOL_TYPE_FINGER;
            pp[i] = pointerProperty;
        }

        final MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[len];
        for (int i = 0; i < len; i++) {
            MotionEvent.PointerCoords pointerCoord = new MotionEvent.PointerCoords();
            pointerCoord.x = x[i];
            pointerCoord.y = y[i];
            pc[i] = pointerCoord;
        }

        return MotionEvent.obtain(
                /* downTime */ SystemClock.uptimeMillis(),
                /* eventTime */ downTime,
                /* action */ action,
                /* pointerCount */ pc.length,
                /* pointerProperties */ pp,
                /* pointerCoords */ pc,
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 1.0f,
                /* yPrecision */ 1.0f,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ InputDevice.SOURCE_TOUCHSCREEN,
                /* flags */ 0);
    }

    private static String stateToString(int state) {
        if (state <= STATE_GESTURE_DETECTING /* maximum value of touch state */) {
            return TouchState.getStateSymbolicName(state);
        }
        switch (state) {
            case STATE_TOUCH_EXPLORING_1FINGER:
                return "STATE_TOUCH_EXPLORING_1FINGER";
            case STATE_TOUCH_EXPLORING_2FINGER:
                return "STATE_TOUCH_EXPLORING_2FINGER";
            case STATE_TOUCH_EXPLORING_3FINGER:
                return "STATE_TOUCH_EXPLORING_3FINGER";
            case STATE_MOVING_2FINGERS:
                return "STATE_MOVING_2FINGERS";
            case STATE_MOVING_3FINGERS:
                return "STATE_MOVING_3FINGERS";
            case STATE_DRAGGING_2FINGERS:
                return "STATE_DRAGGING_2FINGERS";
            case STATE_PINCH_2FINGERS:
                return "STATE_PINCH_2FINGERS";
            default:
                return "stateToString -- Unknown state: " + Integer.toHexString(state);
        }
    }

    /**
     * A {@link android.os.Handler} that doesn't process messages until {@link
     * #fastForward(int)} is invoked.
     *
     * @see com.android.server.testutils.TestHandler
     */
    private class TestHandler extends com.android.server.testutils.TestHandler {
        private final OffsettableClock mClock;

        TestHandler() {
            this(null, new OffsettableClock.Stopped());
        }

        TestHandler(Callback callback, OffsettableClock clock) {
            super(Looper.myLooper(), callback, clock);
            mClock = clock;
        }

        void fastForward(int ms) {
            mClock.fastForward(ms);
            timeAdvance();
        }
    }
}
