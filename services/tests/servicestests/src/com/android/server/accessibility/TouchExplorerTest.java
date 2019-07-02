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

package com.android.server.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.PointF;
import android.os.SystemClock;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.DebugUtils;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TouchExplorerTest {

    public static final int STATE_TOUCH_EXPLORING = 0x00000001;
    public static final int STATE_DRAGGING = 0x00000002;
    public static final int STATE_DELEGATING = 0x00000004;

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
    private long mLastDownTime = Integer.MIN_VALUE;

    // mock package-private AccessibilityGestureDetector class
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
        public void setNext(EventStreamTransformation next) {
        }

        @Override
        public EventStreamTransformation getNext() {
            return null;
        }
    }

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getContext();
        AccessibilityManagerService ams = new AccessibilityManagerService(context);
        AccessibilityGestureDetector detector = mock(AccessibilityGestureDetector.class);
        mCaptor = new EventCaptor();
        mTouchExplorer = new TouchExplorer(context, ams, detector);
        mTouchExplorer.setNext(mCaptor);
    }

    @Test
    public void testTwoFingersMove_shouldDelegatingAndInjectActionDownPointerDown() {
        goFromStateIdleTo(STATE_MOVING_2FINGERS);

        assertState(STATE_DELEGATING);
        assertCapturedEvents(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testTwoFingersDrag_shouldDraggingAndActionDown() {
        goFromStateIdleTo(STATE_DRAGGING_2FINGERS);

        assertState(STATE_DRAGGING);
        assertCapturedEvents(MotionEvent.ACTION_DOWN);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testTwoFingersNotDrag_shouldDelegatingAndActionUpDownPointerDown() {
        // only from dragging state, and withMoveHistory no dragging
        goFromStateIdleTo(STATE_PINCH_2FINGERS);

        assertState(STATE_DELEGATING);
        assertCapturedEvents(
                /* goto dragging state */ MotionEvent.ACTION_DOWN,
                /* leave dragging state */ MotionEvent.ACTION_UP,
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN);
        assertCapturedEventsNoHistory();
    }

    @Test
    public void testThreeFingersMove_shouldDelegatingAnd3ActionPointerDown() {
        goFromStateIdleTo(STATE_MOVING_3FINGERS);

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

    private static String stateToString(int state) {
        return DebugUtils.valueToString(TouchExplorerTest.class, "STATE_", state);
    }

    private void goFromStateIdleTo(int state) {
        try {
            switch (state) {
                case STATE_TOUCH_EXPLORING: {
                    mTouchExplorer.onDestroy();
                }
                break;
                case STATE_TOUCH_EXPLORING_1FINGER: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING);
                    send(downEvent());
                }
                break;
                case STATE_TOUCH_EXPLORING_2FINGER: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING_1FINGER);
                    send(pointerDownEvent());
                }
                break;
                case STATE_TOUCH_EXPLORING_3FINGER: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING_2FINGER);
                    send(thirdPointerDownEvent());
                }
                break;
                case STATE_MOVING_2FINGERS: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(10, 0), p(5, 10));
                    send(mLastEvent);
                }
                break;
                case STATE_DRAGGING_2FINGERS: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING_2FINGER);
                    moveEachPointers(mLastEvent, p(10, 0), p(10, 0));
                    send(mLastEvent);
                }
                break;
                case STATE_PINCH_2FINGERS: {
                    goFromStateIdleTo(STATE_DRAGGING_2FINGERS);
                    moveEachPointers(mLastEvent, p(10, 0), p(-10, 1));
                    send(mLastEvent);
                }
                break;
                case STATE_MOVING_3FINGERS: {
                    goFromStateIdleTo(STATE_TOUCH_EXPLORING_3FINGER);
                    moveEachPointers(mLastEvent, p(1, 0), p(1, 0), p(1, 0));
                    send(mLastEvent);
                }
                break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to go to state " + stateToString(state), t);
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
        final String expectState = "STATE_" + stateToString(expect);
        assertTrue(String.format("Expect state: %s, but: %s", expectState, mTouchExplorer),
                mTouchExplorer.toString().contains(expectState));
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
                MotionEvent.obtain(mLastDownTime, mLastDownTime, MotionEvent.ACTION_DOWN, DEFAULT_X,
                        DEFAULT_Y, 0));
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
