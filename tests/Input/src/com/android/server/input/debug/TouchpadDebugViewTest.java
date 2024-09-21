/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.input.InputManager;
import android.testing.TestableContext;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.input.MotionEventBuilder;
import com.android.cts.input.PointerBuilder;
import com.android.server.input.TouchpadFingerState;
import com.android.server.input.TouchpadHardwareProperties;
import com.android.server.input.TouchpadHardwareState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 * atest TouchpadDebugViewTest
 */
@RunWith(AndroidJUnit4.class)
public class TouchpadDebugViewTest {
    private static final int TOUCHPAD_DEVICE_ID = 60;

    private TouchpadDebugView mTouchpadDebugView;
    private WindowManager.LayoutParams mWindowLayoutParams;

    @Mock
    WindowManager mWindowManager;
    @Mock
    InputManager mInputManager;

    Rect mWindowBounds;
    WindowMetrics mWindowMetrics;
    TestableContext mTestableContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mTestableContext = new TestableContext(context);

        mTestableContext.addMockSystemService(WindowManager.class, mWindowManager);
        mTestableContext.addMockSystemService(InputManager.class, mInputManager);

        mWindowBounds = new Rect(0, 0, 2560, 1600);
        mWindowMetrics = new WindowMetrics(mWindowBounds, new WindowInsets(mWindowBounds), 1.0f);

        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);

        InputDevice inputDevice = new InputDevice.Builder()
                .setId(TOUCHPAD_DEVICE_ID)
                .setSources(InputDevice.SOURCE_TOUCHPAD | SOURCE_MOUSE)
                .setName("Test Device " + TOUCHPAD_DEVICE_ID)
                .build();

        when(mInputManager.getInputDevice(TOUCHPAD_DEVICE_ID)).thenReturn(inputDevice);

        mTouchpadDebugView = new TouchpadDebugView(mTestableContext, TOUCHPAD_DEVICE_ID,
                new TouchpadHardwareProperties.Builder(0f, 0f, 500f,
                        500f, 45f, 47f, -4f, 5f, (short) 10, true,
                        true).build());

        mTouchpadDebugView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        doAnswer(invocation -> {
            mTouchpadDebugView.layout(0, 0, mTouchpadDebugView.getMeasuredWidth(),
                    mTouchpadDebugView.getMeasuredHeight());
            return null;
        }).when(mWindowManager).addView(any(), any());

        doAnswer(invocation -> {
            mTouchpadDebugView.layout(0, 0, mTouchpadDebugView.getMeasuredWidth(),
                    mTouchpadDebugView.getMeasuredHeight());
            return null;
        }).when(mWindowManager).updateViewLayout(any(), any());

        mWindowLayoutParams = mTouchpadDebugView.getWindowLayoutParams();
        mWindowLayoutParams.x = 20;
        mWindowLayoutParams.y = 20;

        mTouchpadDebugView.layout(0, 0, mTouchpadDebugView.getMeasuredWidth(),
                mTouchpadDebugView.getMeasuredHeight());
    }

    @Test
    public void testDragView() {
        // Initial view position relative to screen.
        int initialX = mWindowLayoutParams.x;
        int initialY = mWindowLayoutParams.y;

        float offsetX = ViewConfiguration.get(mTestableContext).getScaledTouchSlop() + 10;
        float offsetY = ViewConfiguration.get(mTestableContext).getScaledTouchSlop() + 10;

        // Simulate ACTION_DOWN event (initial touch).
        MotionEvent actionDown = new MotionEventBuilder(MotionEvent.ACTION_DOWN, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f)
                        .y(40f)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionDown);

        verify(mWindowManager, times(0)).updateViewLayout(any(), any());

        // Simulate ACTION_MOVE event (dragging to the right).
        MotionEvent actionMove = new MotionEventBuilder(MotionEvent.ACTION_MOVE, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f + offsetX)
                        .y(40f + offsetY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionMove);

        ArgumentCaptor<WindowManager.LayoutParams> mWindowLayoutParamsCaptor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(any(), mWindowLayoutParamsCaptor.capture());

        // Verify position after ACTION_MOVE
        assertEquals(initialX + (long) offsetX, mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(initialY + (long) offsetY, mWindowLayoutParamsCaptor.getValue().y);

        // Simulate ACTION_UP event (release touch).
        MotionEvent actionUp = new MotionEventBuilder(MotionEvent.ACTION_UP, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f + offsetX)
                        .y(40f + offsetY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionUp);

        assertEquals(initialX + (long) offsetX, mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(initialY + (long) offsetY, mWindowLayoutParamsCaptor.getValue().y);
    }

    @Test
    public void testDragViewOutOfBounds() {
        int initialX = mWindowLayoutParams.x;
        int initialY = mWindowLayoutParams.y;

        MotionEvent actionDown = new MotionEventBuilder(MotionEvent.ACTION_DOWN, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX + 10f)
                        .y(initialY + 10f)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionDown);

        verify(mWindowManager, times(0)).updateViewLayout(any(), any());

        // Simulate ACTION_MOVE event (dragging far to the right and bottom, beyond screen bounds)
        MotionEvent actionMove = new MotionEventBuilder(MotionEvent.ACTION_MOVE, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(mWindowBounds.width() + mTouchpadDebugView.getWidth())
                        .y(mWindowBounds.height() + mTouchpadDebugView.getHeight())
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionMove);

        ArgumentCaptor<WindowManager.LayoutParams> mWindowLayoutParamsCaptor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(any(), mWindowLayoutParamsCaptor.capture());

        // Verify the view has been clamped to the right and bottom edges of the screen
        assertEquals(mWindowBounds.width() - mTouchpadDebugView.getWidth(),
                mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(mWindowBounds.height() - mTouchpadDebugView.getHeight(),
                mWindowLayoutParamsCaptor.getValue().y);

        MotionEvent actionUp = new MotionEventBuilder(MotionEvent.ACTION_UP, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(mWindowBounds.width() + mTouchpadDebugView.getWidth())
                        .y(mWindowBounds.height() + mTouchpadDebugView.getHeight())
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionUp);

        // Verify the view has been clamped to the right and bottom edges of the screen
        assertEquals(mWindowBounds.width() - mTouchpadDebugView.getWidth(),
                mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(mWindowBounds.height() - mTouchpadDebugView.getHeight(),
                mWindowLayoutParamsCaptor.getValue().y);
    }

    @Test
    public void testSlopOffset() {
        int initialX = mWindowLayoutParams.x;
        int initialY = mWindowLayoutParams.y;

        float offsetX = ViewConfiguration.get(mTestableContext).getScaledTouchSlop() / 2.0f;
        float offsetY = -(ViewConfiguration.get(mTestableContext).getScaledTouchSlop() / 2.0f);

        MotionEvent actionDown = new MotionEventBuilder(MotionEvent.ACTION_DOWN, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX)
                        .y(initialY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionDown);

        MotionEvent actionMove = new MotionEventBuilder(MotionEvent.ACTION_MOVE, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX + offsetX)
                        .y(initialY + offsetY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionMove);

        MotionEvent actionUp = new MotionEventBuilder(MotionEvent.ACTION_UP, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX)
                        .y(initialY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionUp);

        // In this case the updateViewLayout() method wouldn't be called because the drag
        // distance hasn't exceeded the slop
        verify(mWindowManager, times(0)).updateViewLayout(any(), any());
    }

    @Test
    public void testViewReturnsToInitialPositionOnCancel() {
        int initialX = mWindowLayoutParams.x;
        int initialY = mWindowLayoutParams.y;

        float offsetX = 50;
        float offsetY = 50;

        MotionEvent actionDown = new MotionEventBuilder(MotionEvent.ACTION_DOWN, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX)
                        .y(initialY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionDown);

        MotionEvent actionMove = new MotionEventBuilder(MotionEvent.ACTION_MOVE, SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX + offsetX)
                        .y(initialY + offsetY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionMove);

        ArgumentCaptor<WindowManager.LayoutParams> mWindowLayoutParamsCaptor =
                ArgumentCaptor.forClass(WindowManager.LayoutParams.class);
        verify(mWindowManager).updateViewLayout(any(), mWindowLayoutParamsCaptor.capture());

        assertEquals(initialX + (long) offsetX, mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(initialY + (long) offsetY, mWindowLayoutParamsCaptor.getValue().y);

        // Simulate ACTION_CANCEL event (canceling the touch event stream)
        MotionEvent actionCancel = new MotionEventBuilder(MotionEvent.ACTION_CANCEL,
                SOURCE_TOUCHSCREEN)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(initialX + offsetX)
                        .y(initialY + offsetY)
                )
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionCancel);

        // Verify the view returns to its initial position
        verify(mWindowManager, times(2)).updateViewLayout(any(),
                mWindowLayoutParamsCaptor.capture());
        assertEquals(initialX, mWindowLayoutParamsCaptor.getValue().x);
        assertEquals(initialY, mWindowLayoutParamsCaptor.getValue().y);
    }

    @Test
    public void testTouchpadClick() {
        View child = mTouchpadDebugView.getChildAt(0);

        mTouchpadDebugView.updateHardwareState(
                new TouchpadHardwareState(0, 1 /* buttonsDown */, 0, 0,
                        new TouchpadFingerState[0]), TOUCHPAD_DEVICE_ID);

        assertEquals(((ColorDrawable) child.getBackground()).getColor(), Color.rgb(118, 151, 99));

        mTouchpadDebugView.updateHardwareState(
                new TouchpadHardwareState(0, 0 /* buttonsDown */, 0, 0,
                        new TouchpadFingerState[0]), TOUCHPAD_DEVICE_ID);

        assertEquals(((ColorDrawable) child.getBackground()).getColor(), Color.rgb(84, 85, 169));

        mTouchpadDebugView.updateHardwareState(
                new TouchpadHardwareState(0, 1 /* buttonsDown */, 0, 0,
                        new TouchpadFingerState[0]), TOUCHPAD_DEVICE_ID);

        assertEquals(((ColorDrawable) child.getBackground()).getColor(), Color.rgb(118, 151, 99));

        // Color should not change because hardware state of a different touchpad
        mTouchpadDebugView.updateHardwareState(
                new TouchpadHardwareState(0, 0 /* buttonsDown */, 0, 0,
                        new TouchpadFingerState[0]), TOUCHPAD_DEVICE_ID + 1);

        assertEquals(((ColorDrawable) child.getBackground()).getColor(), Color.rgb(118, 151, 99));
    }

    @Test
    public void testTouchpadGesture() {
        int gestureType = 3;
        TextView child = mTouchpadDebugView.getGestureInfoView();

        mTouchpadDebugView.updateGestureInfo(gestureType, TOUCHPAD_DEVICE_ID);
        assertEquals(child.getText().toString(), TouchpadDebugView.getGestureText(gestureType));

        gestureType = 6;
        mTouchpadDebugView.updateGestureInfo(gestureType, TOUCHPAD_DEVICE_ID);
        assertEquals(child.getText().toString(), TouchpadDebugView.getGestureText(gestureType));
    }

    @Test
    public void testTwoFingerDrag() {
        float offsetX = ViewConfiguration.get(mTestableContext).getScaledTouchSlop() + 10;
        float offsetY = ViewConfiguration.get(mTestableContext).getScaledTouchSlop() + 10;

        // Simulate ACTION_DOWN event (gesture starts).
        MotionEvent actionDown = new MotionEventBuilder(MotionEvent.ACTION_DOWN, SOURCE_MOUSE)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f)
                        .y(40f)
                )
                .classification(MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionDown);

        // Simulate ACTION_MOVE event (dragging with two fingers, processed as one pointer).
        MotionEvent actionMove = new MotionEventBuilder(MotionEvent.ACTION_MOVE, SOURCE_MOUSE)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f + offsetX)
                        .y(40f + offsetY)
                )
                .classification(MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionMove);

        // Simulate ACTION_UP event (gesture ends).
        MotionEvent actionUp = new MotionEventBuilder(MotionEvent.ACTION_UP, SOURCE_MOUSE)
                .pointer(new PointerBuilder(0, MotionEvent.TOOL_TYPE_FINGER)
                        .x(40f + offsetX)
                        .y(40f + offsetY)
                )
                .classification(MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)
                .build();
        mTouchpadDebugView.dispatchTouchEvent(actionUp);

        // Verify that no updateViewLayout is called (as expected for a two-finger drag gesture).
        verify(mWindowManager, times(0)).updateViewLayout(any(), any());
    }
}