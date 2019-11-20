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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
public class EditorTouchStateTest {

    private EditorTouchState mTouchState;
    private ViewConfiguration mConfig;

    @Before
    public void before() throws Exception {
        mTouchState = new EditorTouchState();
        mConfig = new ViewConfiguration();
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
        assertTap(mTouchState, 22f, 33f, 20f, 30f,
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
        assertTap(mTouchState, 200f, 300f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, false);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 200f, 300f);
        mTouchState.update(event4, mConfig);
        assertTap(mTouchState, 200f, 300f, 200f, 300f,
                MultiTapStatus.DOUBLE_TAP, false);
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
        assertTap(mTouchState, 21f, 31f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 21f, 31f);
        event4.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event4, mConfig);
        assertTap(mTouchState, 21f, 31f, 21f, 31f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Generate a third ACTION_DOWN event whose time is within the double-tap timeout.
        long event5Time = 1004;
        MotionEvent event5 = downEvent(event5Time, event5Time, 22f, 32f);
        event5.setSource(InputDevice.SOURCE_MOUSE);
        mTouchState.update(event5, mConfig);
        assertTap(mTouchState, 22f, 32f, 21f, 31f,
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
        assertTap(mTouchState, 21f, 31f, 20f, 30f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Simulate an ACTION_UP event.
        long event4Time = 1003;
        MotionEvent event4 = upEvent(event3Time, event4Time, 21f, 31f);
        mTouchState.update(event4, mConfig);
        assertTap(mTouchState, 21f, 31f, 21f, 31f,
                MultiTapStatus.DOUBLE_TAP, true);

        // Generate a third ACTION_DOWN event whose time is within the double-tap timeout.
        long event5Time = 1004;
        MotionEvent event5 = downEvent(event5Time, event5Time, 22f, 32f);
        mTouchState.update(event5, mConfig);
        assertTap(mTouchState, 22f, 32f, 21f, 31f,
                MultiTapStatus.FIRST_TAP, false);
    }

    private static MotionEvent downEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static MotionEvent upEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
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
    }

    private static void assertTap(EditorTouchState touchState,
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
    }
}
