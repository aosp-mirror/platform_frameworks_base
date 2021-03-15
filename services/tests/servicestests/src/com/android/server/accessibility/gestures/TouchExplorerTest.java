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

import static android.view.ViewConfiguration.getDoubleTapTimeout;

import static com.android.server.accessibility.gestures.TouchState.STATE_CLEAR;
import static com.android.server.accessibility.gestures.TouchState.STATE_DELEGATING;
import static com.android.server.accessibility.gestures.TouchState.STATE_DRAGGING;
import static com.android.server.accessibility.gestures.TouchState.STATE_TOUCH_EXPLORING;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.PointF;
import android.os.SystemClock;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.utils.MotionEventMatcher;

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
    private TouchExplorer mTouchExplorer;
    private Context mContext;
    private int mTouchSlop;
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
            // LastEvent may not match if we're clearing the state
            // The last event becomes ACTION_UP event when sending the ACTION_CANCEL event,
            // so ignoring the ACTION_CANCEL event checking.
            if (mLastEvent != null && rawEvent.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                MotionEventMatcher lastEventMatcher = new MotionEventMatcher(mLastEvent);
                assertThat(rawEvent, lastEventMatcher);
            }
        }

        @Override
        public void setNext(EventStreamTransformation next) {
        }

        @Override
        public EventStreamTransformation getNext() {
            return null;
        }
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        AccessibilityManagerService ams = new AccessibilityManagerService(mContext);
        GestureManifold detector = mock(GestureManifold.class);
        mCaptor = new EventCaptor();
        mTouchExplorer = new TouchExplorer(mContext, ams, detector);
        mTouchExplorer.setNext(mCaptor);
    }

    /**
     * Test the case where the event location is correct when clicking after the following
     * situation happened: entering the delegate state through doubleTapAndHold gesture and
     * receiving a cancel event to return the clear state.
     */
    @Test
    public void testClick_afterCanceledDoubleTapAndHold_eventLocationIsCorrect()
            throws InterruptedException {
        // Generates the click position by this click operation, otherwise the offset used
        // while delegating could not be set.
        send(downEvent(DEFAULT_X + 10, DEFAULT_Y + 10));
        // Waits for transition to touch exploring state.
        Thread.sleep(2 * USER_INTENT_TIMEOUT);
        send(upEvent());

        // Simulates detecting the doubleTapAndHold gesture and enters the delegate state.
        final MotionEvent sendEvent =
                fromTouchscreen(downEvent(DEFAULT_X + 100, DEFAULT_Y + 100));
        mTouchExplorer.onDoubleTapAndHold(sendEvent, sendEvent, 0);
        assertState(STATE_DELEGATING);

        send(cancelEvent());

        // Generates the click operation, and checks the event location of the ACTION_HOVER_ENTER
        // event is correct.
        send(downEvent());
        // Waits for transition to touch exploring state.
        Thread.sleep(2 * USER_INTENT_TIMEOUT);
        send(upEvent());

        final List<MotionEvent> events = getCapturedEvents();
        assertTrue(events.stream().anyMatch(
                motionEvent -> motionEvent.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                        && motionEvent.getX() == DEFAULT_X
                        && motionEvent.getY() == DEFAULT_Y));
    }

    @Test
    public void testTwoFingersMove_shouldDelegatingAndInjectActionDownPointerDown() {
        goFromStateClearTo(STATE_MOVING_2FINGERS);

        assertState(STATE_DELEGATING);
        assertCapturedEvents(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN);
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
        assertCapturedEvents(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP);
    }

    @Test
    public void testTwoFingersDrag_shouldDraggingAndActionDown() {
        goFromStateClearTo(STATE_DRAGGING_2FINGERS);

        assertState(STATE_DRAGGING);
        assertCapturedEvents(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testTwoFingersNotDrag_shouldDelegatingAndActionUpDownPointerDown() {
        // only from dragging state, and withMoveHistory no dragging
        goFromStateClearTo(STATE_PINCH_2FINGERS);

        assertState(STATE_DELEGATING);
        assertCapturedEvents(
                /* goto dragging state */ MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                /* leave dragging state */ MotionEvent.ACTION_UP,
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testThreeFingersMove_shouldDelegatingAnd3ActionPointerDown() {
        goFromStateClearTo(STATE_MOVING_3FINGERS);

        assertState(STATE_DELEGATING);
        assertCapturedEvents(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_DOWN);
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
                case STATE_CLEAR: {
                    mTouchExplorer.onDestroy();
                }
                break;
                case STATE_TOUCH_EXPLORING_1FINGER: {
                    send(downEvent());
                }
                break;
                case STATE_TOUCH_EXPLORING_2FINGER: {
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_1FINGER);
                    send(pointerDownEvent());
                }
                break;
                case STATE_TOUCH_EXPLORING_3FINGER: {
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    send(thirdPointerDownEvent());
                }
                break;
                case STATE_MOVING_2FINGERS: {
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(10, 0), p(5, 10));
                    send(mLastEvent);
                }
                break;
                case STATE_DRAGGING_2FINGERS: {
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(mTouchSlop, 0), p(mTouchSlop, 0));
                    send(mLastEvent);
                }
                break;
                case STATE_PINCH_2FINGERS: {
                    goFromStateClearTo(STATE_DRAGGING_2FINGERS);
                    moveEachPointers(mLastEvent, p(mTouchSlop, 0), p(-mTouchSlop, 1));
                    send(mLastEvent);
                }
                break;
                case STATE_MOVING_3FINGERS: {
                    goFromStateClearTo(STATE_TOUCH_EXPLORING_3FINGER);
                    moveEachPointers(mLastEvent, p(1, 0), p(1, 0), p(1, 0));
                    send(mLastEvent);
                }
                break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to go to state "
            + TouchState.getStateSymbolicName(state), t);
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

    private MotionEvent cancelEvent() {
        mLastDownTime = SystemClock.uptimeMillis();
        return fromTouchscreen(
                MotionEvent.obtain(mLastDownTime, mLastDownTime, MotionEvent.ACTION_CANCEL,
                        DEFAULT_X, DEFAULT_Y, 0));
    }

    private MotionEvent downEvent(float x, float y) {
        mLastDownTime = SystemClock.uptimeMillis();
        return fromTouchscreen(
                MotionEvent.obtain(mLastDownTime, mLastDownTime, MotionEvent.ACTION_DOWN, x, y, 0));
    }

    private MotionEvent downEvent() {
        mLastDownTime = SystemClock.uptimeMillis();
        return fromTouchscreen(
                MotionEvent.obtain(mLastDownTime, mLastDownTime, MotionEvent.ACTION_DOWN, DEFAULT_X,
                        DEFAULT_Y, 0));
    }

    private MotionEvent upEvent() {
        MotionEvent event = downEvent();
        event.setAction(MotionEvent.ACTION_UP);
        return event;
    }

    private MotionEvent pointerDownEvent() {
        final int secondPointerId = 0x0100;
        final int action = MotionEvent.ACTION_POINTER_DOWN | secondPointerId;
        final float[] x = new float[]{DEFAULT_X, DEFAULT_X + 29};
        final float[] y = new float[]{DEFAULT_Y, DEFAULT_Y + 28};
        return manyPointerEvent(action, x, y);
    }

    private MotionEvent thirdPointerDownEvent() {
        final int thirdPointerId = 0x0200;
        final int action = MotionEvent.ACTION_POINTER_DOWN | thirdPointerId;
        final float[] x = new float[]{DEFAULT_X, DEFAULT_X + 29, DEFAULT_X + 59};
        final float[] y = new float[]{DEFAULT_Y, DEFAULT_Y + 28, DEFAULT_Y + 58};
        return manyPointerEvent(action, x, y);
    }

    private void moveEachPointers(MotionEvent event, PointF... points) {
        final float[] x = new float[points.length];
        final float[] y = new float[points.length];
        for (int i = 0; i < points.length; i++) {
            x[i] = event.getX(i) + points[i].x;
            y[i] = event.getY(i) + points[i].y;
        }
        MotionEvent newEvent = manyPointerEvent(MotionEvent.ACTION_MOVE, x, y);
        event.setAction(MotionEvent.ACTION_MOVE);
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
}
