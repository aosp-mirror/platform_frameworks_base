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

package android.widget;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import android.platform.test.annotations.Presubmit;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.EditorTouchState.MultiTapStatus;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
@Presubmit
public class EditorTouchStateTest {

    private EditorTouchState mTouchState;
    private ViewConfiguration mConfig;

    @Before
    public void before() throws Exception {
        mTouchState = new EditorTouchState();
        mConfig = new ViewConfiguration();
    }

    @Test
    public void testIsDistanceWithin() throws Exception {
        assertTrue(EditorTouchState.isDistanceWithin(0, 0, 0, 0, 8));
        assertTrue(EditorTouchState.isDistanceWithin(3, 9, 5, 11, 8));
        assertTrue(EditorTouchState.isDistanceWithin(5, 11, 3, 9, 8));
        assertFalse(EditorTouchState.isDistanceWithin(5, 10, 5, 20, 8));
    }

    @Test
    public void testUpdate_singleTap() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1001;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate an ACTION_DOWN event whose time is after the double-tap timeout.
        long event3Time = event2Time + ViewConfiguration.getDoubleTapTimeout() + 1;
        MotionEvent event3 = downEvent(event3Time, event3Time, 22f, 33f);
        mTouchState.update(event3, mConfig);
        assertSingleTap(mTouchState, 22f, 33f, 20f, 30f);
    }

    @Test
    public void testUpdate_doubleTap_sameArea() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1001;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate an ACTION_DOWN event whose time is within the double-tap timeout.
        long event3Time = 1002;
        MotionEvent event3 = downEvent(event3Time, event3Time, 22f, 33f);
        mTouchState.update(event3, mConfig);
        assertMultiTap(mTouchState, 22f, 33f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);
    }

    @Test
    public void testUpdate_doubleTap_notSameArea() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1001;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate an ACTION_DOWN event whose time is within the double-tap timeout.
        long event3Time = 1002;
        MotionEvent event3 = downEvent(event3Time, event3Time, 200f, 300f);
        mTouchState.update(event3, mConfig);
        assertMultiTap(mTouchState, 200f, 300f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, false);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 200f, 300f);
        mTouchState.update(event4, mConfig);
        assertMultiTap(mTouchState, 200f, 300f, 200f, 300f,
                MultiTapStatus.DOUBLE_TAP, false);
    }

    @Test
    public void testUpdate_doubleTap_delayAfterFirstDownEvent() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event with a delay that's longer than the double-tap timeout.
        long event2Time = 1000 + ViewConfiguration.getDoubleTapTimeout() + 1;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate an ACTION_DOWN event whose time is within the double-tap timeout when
        // calculated from the last ACTION_UP event time. Even though the time between the last up
        // and this down event is within the double-tap timeout, this should not be considered a
        // double-tap (since the first down event had a longer delay).
        long event3Time = event2Time + 1;
        MotionEvent event3 = downEvent(event3Time, event3Time, 22f, 33f);
        mTouchState.update(event3, mConfig);
        assertSingleTap(mTouchState, 22f, 33f, 20f, 30f);
    }

    @Test
    public void testUpdate_quickTapAfterDrag() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_MOVE event.
        long event2Time = 1001;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 200f, 31f);
        mTouchState.update(event2, mConfig);
        assertDrag(mTouchState, 20f, 30f, 0, 0, 180f);

        // Simulate an ACTION_UP event with a delay that's longer than the double-tap timeout.
        long event3Time = 5000;
        MotionEvent event3 = upEvent(event1Time, event3Time, 200f, 31f);
        mTouchState.update(event3, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 200f, 31f);

        // Generate an ACTION_DOWN event whose time is within the double-tap timeout when
        // calculated from the last ACTION_UP event time. Even though the time between the last up
        // and this down event is within the double-tap timeout, this should not be considered a
        // double-tap (since the first down event had a longer delay).
        long event4Time = event3Time + 1;
        MotionEvent event4 = downEvent(event4Time, event4Time, 200f, 31f);
        mTouchState.update(event4, mConfig);
        assertSingleTap(mTouchState, 200f, 31f, 200f, 31f);
    }

    @Test
    public void testUpdate_tripleClick_mouse() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        event1.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1001;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        event2.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate a second ACTION_DOWN event whose time is within the double-tap timeout.
        long event3Time = 1002;
        MotionEvent event3 = downEvent(event3Time, event3Time, 21f, 31f);
        event3.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event3, mConfig);
        assertMultiTap(mTouchState, 21f, 31f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 21f, 31f);
        event4.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event4, mConfig);
        assertMultiTap(mTouchState, 21f, 31f, 21f, 31f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Generate a third ACTION_DOWN event whose time is within the double-tap timeout.
        long event5Time = 1004;
        MotionEvent event5 = downEvent(event5Time, event5Time, 22f, 32f);
        event5.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event5, mConfig);
        assertMultiTap(mTouchState, 22f, 32f, 21f, 31f,
                MultiTapStatus.TRIPLE_CLICK, true);
    }

    @Test
    public void testUpdate_tripleClick_touch() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1001;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate a second ACTION_DOWN event whose time is within the double-tap timeout.
        long event3Time = 1002;
        MotionEvent event3 = downEvent(event3Time, event3Time, 21f, 31f);
        mTouchState.update(event3, mConfig);
        assertMultiTap(mTouchState, 21f, 31f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 21f, 31f);
        mTouchState.update(event4, mConfig);
        assertMultiTap(mTouchState, 21f, 31f, 21f, 31f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Generate a third ACTION_DOWN event whose time is within the double-tap timeout.
        long event5Time = 1004;
        MotionEvent event5 = downEvent(event5Time, event5Time, 22f, 32f);
        mTouchState.update(event5, mConfig);
        assertSingleTap(mTouchState, 22f, 32f, 21f, 31f);
    }

    @Test
    public void testUpdate_drag() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1000;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_MOVE event whose location is not far enough to start a drag.
        long event2Time = 1001;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 21f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate another ACTION_MOVE event whose location is far enough to start a drag.
        int touchSlop = mConfig.getScaledTouchSlop();
        float newX = event1.getX() + touchSlop + 1;
        float newY = event1.getY();
        long event3Time = 1002;
        MotionEvent event3 = moveEvent(event3Time, event3Time, newX, newY);
        mTouchState.update(event3, mConfig);
        assertDrag(mTouchState, 20f, 30f, 0, 0, Float.MAX_VALUE);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 200f, 300f);
        mTouchState.update(event4, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 200f, 300f);
    }

    @Test
    public void testUpdate_drag_startsCloseToVerticalThenHorizontal() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 0f, 0f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 0f, 0f, 0, 0);

        // Simulate an ACTION_MOVE event that is < 30 deg from vertical.
        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 100f, 174f);
        mTouchState.update(event2, mConfig);
        assertDrag(mTouchState, 0f, 0f, 0, 0, 100f / 174f);

        // Simulate another ACTION_MOVE event that is horizontal from the original down event.
        // The drag direction ratio should NOT change since it should only reflect the initial
        // direction of movement.
        long event3Time = 1003;
        MotionEvent event3 = moveEvent(event1Time, event3Time, 200f, 0f);
        mTouchState.update(event3, mConfig);
        assertDrag(mTouchState, 0f, 0f, 0, 0, 100f / 174f);

        // Simulate an ACTION_UP event.
        long event4Time = 1004;
        MotionEvent event4 = upEvent(event1Time, event4Time, 200f, 0f);
        mTouchState.update(event4, mConfig);
        assertSingleTap(mTouchState, 0f, 0f, 200f, 0f);
    }

    @Test
    public void testUpdate_drag_startsHorizontalThenVertical() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 0f, 0f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 0f, 0f, 0, 0);

        // Simulate an ACTION_MOVE event that is > 45 deg from vertical.
        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 100f, 90f);
        mTouchState.update(event2, mConfig);
        assertDrag(mTouchState, 0f, 0f, 0, 0, 100f / 90f);

        // Simulate another ACTION_MOVE event that is vertical from the original down event.
        // The drag direction ratio should NOT change since it should only reflect the initial
        // direction of movement.
        long event3Time = 1003;
        MotionEvent event3 = moveEvent(event1Time, event3Time, 0f, 200f);
        mTouchState.update(event3, mConfig);
        assertDrag(mTouchState, 0f, 0f, 0, 0, 100f / 90f);

        // Simulate an ACTION_UP event.
        long event4Time = 1004;
        MotionEvent event4 = upEvent(event1Time, event4Time, 0f, 200f);
        mTouchState.update(event4, mConfig);
        assertSingleTap(mTouchState, 0f, 0f, 0f, 200f);
    }

    @Test
    public void testUpdate_cancelAfterDown() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_CANCEL event.
        long event2Time = 1002;
        MotionEvent event2 = cancelEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);
    }

    @Test
    public void testUpdate_cancelAfterDrag() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate another ACTION_MOVE event whose location is far enough to start a drag.
        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event2Time, event2Time, 200f, 30f);
        mTouchState.update(event2, mConfig);
        assertDrag(mTouchState, 20f, 30f, 0, 0, Float.MAX_VALUE);

        // Simulate an ACTION_CANCEL event.
        long event3Time = 1003;
        MotionEvent event3 = cancelEvent(event1Time, event3Time, 200f, 30f);
        mTouchState.update(event3, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);
    }

    @Test
    public void testUpdate_cancelAfterMultitap() throws Exception {
        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mTouchState.update(event1, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 0, 0);

        // Simulate an ACTION_UP event.
        long event2Time = 1002;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mTouchState.update(event2, mConfig);
        assertSingleTap(mTouchState, 20f, 30f, 20f, 30f);

        // Generate an ACTION_DOWN event whose time is within the double-tap timeout.
        long event3Time = 1003;
        MotionEvent event3 = downEvent(event3Time, event3Time, 22f, 33f);
        mTouchState.update(event3, mConfig);
        assertMultiTap(mTouchState, 22f, 33f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Simulate an ACTION_CANCEL event.
        long event4Time = 1004;
        MotionEvent event4 = cancelEvent(event3Time, event4Time, 20f, 30f);
        mTouchState.update(event4, mConfig);
        assertSingleTap(mTouchState, 22f, 33f, 20f, 30f);
    }

    @Test
    public void testGetXYRatio() throws Exception {
        doTestGetXYRatio(-1, 0.0f);
        doTestGetXYRatio(0, 0.0f);
        doTestGetXYRatio(30, 0.58f);
        doTestGetXYRatio(45, 1.0f);
        doTestGetXYRatio(60, 1.73f);
        doTestGetXYRatio(90, Float.MAX_VALUE);
        doTestGetXYRatio(91, Float.MAX_VALUE);
    }

    private void doTestGetXYRatio(int angleFromVerticalInDegrees, float expectedXYRatioRounded) {
        float result = EditorTouchState.getXYRatio(angleFromVerticalInDegrees);
        String msg = String.format(
                "%d deg should give an x/y ratio of %f; actual unrounded result is %f",
                angleFromVerticalInDegrees, expectedXYRatioRounded, result);
        float roundedResult = (result == 0.0f || result == Float.MAX_VALUE) ? result :
                Math.round(result * 100) / 100f;
        assertThat(msg, roundedResult, is(expectedXYRatioRounded));
    }

    @Test
    public void testUpdate_dragDirection() throws Exception {
        // Simulate moving straight up.
        doTestDragDirection(100f, 100f, 100f, 50f, 0f);

        // Simulate moving straight down.
        doTestDragDirection(100f, 100f, 100f, 150f, 0f);

        // Simulate moving straight left.
        doTestDragDirection(100f, 100f, 50f, 100f, Float.MAX_VALUE);

        // Simulate moving straight right.
        doTestDragDirection(100f, 100f, 150f, 100f, Float.MAX_VALUE);

        // Simulate moving up and right, < 45 deg from vertical.
        doTestDragDirection(100f, 100f, 110f, 50f, 10f / 50f);

        // Simulate moving up and right, > 45 deg from vertical.
        doTestDragDirection(100f, 100f, 150f, 90f, 50f / 10f);

        // Simulate moving down and right, < 45 deg from vertical.
        doTestDragDirection(100f, 100f, 110f, 150f, 10f / 50f);

        // Simulate moving down and right, > 45 deg from vertical.
        doTestDragDirection(100f, 100f, 150f, 110f, 50f / 10f);

        // Simulate moving down and left, < 45 deg from vertical.
        doTestDragDirection(100f, 100f, 90f, 150f, 10f / 50f);

        // Simulate moving down and left, > 45 deg from vertical.
        doTestDragDirection(100f, 100f, 50f, 110f, 50f / 10f);

        // Simulate moving up and left, < 45 deg from vertical.
        doTestDragDirection(100f, 100f, 90f, 50f, 10f / 50f);

        // Simulate moving up and left, > 45 deg from vertical.
        doTestDragDirection(100f, 100f, 50f, 90f, 50f / 10f);
    }

    private void doTestDragDirection(float downX, float downY, float moveX, float moveY,
            float expectedInitialDragDirectionXYRatio) {
        EditorTouchState touchState = new EditorTouchState();

        // Simulate an ACTION_DOWN event.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, downX, downY);
        touchState.update(event1, mConfig);

        // Simulate an ACTION_MOVE event.
        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event1Time, event2Time, moveX, moveY);
        touchState.update(event2, mConfig);
        String msg = String.format("(%.0f,%.0f)=>(%.0f,%.0f)", downX, downY, moveX, moveY);
        assertThat(msg, touchState.getInitialDragDirectionXYRatio(),
                is(expectedInitialDragDirectionXYRatio));
    }

    private static MotionEvent downEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static MotionEvent upEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
    }

    private static MotionEvent moveEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0);
    }

    private static MotionEvent cancelEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_CANCEL, x, y, 0);
    }

    private static void assertSingleTap(EditorTouchState touchState, float lastDownX,
            float lastDownY, float lastUpX, float lastUpY) {
        assertThat(touchState.getLastDownX(), is(lastDownX));
        assertThat(touchState.getLastDownY(), is(lastDownY));
        assertThat(touchState.getLastUpX(), is(lastUpX));
        assertThat(touchState.getLastUpY(), is(lastUpY));
        assertThat(touchState.isDoubleTap(), is(false));
        assertThat(touchState.isTripleClick(), is(false));
        assertThat(touchState.isMultiTap(), is(false));
        assertThat(touchState.isMultiTapInSameArea(), is(false));
        assertThat(touchState.isMovedEnoughForDrag(), is(false));
    }

    private static void assertDrag(EditorTouchState touchState, float lastDownX,
            float lastDownY, float lastUpX, float lastUpY, float initialDragDirectionXYRatio) {
        assertThat(touchState.getLastDownX(), is(lastDownX));
        assertThat(touchState.getLastDownY(), is(lastDownY));
        assertThat(touchState.getLastUpX(), is(lastUpX));
        assertThat(touchState.getLastUpY(), is(lastUpY));
        assertThat(touchState.isDoubleTap(), is(false));
        assertThat(touchState.isTripleClick(), is(false));
        assertThat(touchState.isMultiTap(), is(false));
        assertThat(touchState.isMultiTapInSameArea(), is(false));
        assertThat(touchState.isMovedEnoughForDrag(), is(true));
        assertThat(touchState.getInitialDragDirectionXYRatio(), is(initialDragDirectionXYRatio));
    }

    private static void assertMultiTap(EditorTouchState touchState,
            float lastDownX, float lastDownY, float lastUpX, float lastUpY,
            @MultiTapStatus int multiTapStatus, boolean isMultiTapInSameArea) {
        assertThat(touchState.getLastDownX(), is(lastDownX));
        assertThat(touchState.getLastDownY(), is(lastDownY));
        assertThat(touchState.getLastUpX(), is(lastUpX));
        assertThat(touchState.getLastUpY(), is(lastUpY));
        assertThat(touchState.isDoubleTap(), is(multiTapStatus == MultiTapStatus.DOUBLE_TAP));
        assertThat(touchState.isTripleClick(), is(multiTapStatus == MultiTapStatus.TRIPLE_CLICK));
        assertThat(touchState.isMultiTap(), is(multiTapStatus == MultiTapStatus.DOUBLE_TAP
                || multiTapStatus == MultiTapStatus.TRIPLE_CLICK));
        assertThat(touchState.isMultiTapInSameArea(), is(isMultiTapInSameArea));
        assertThat(touchState.isMovedEnoughForDrag(), is(false));
    }
}
