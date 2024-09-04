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

import static android.view.InputDevice.SOURCE_TOUCHSCREEN;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.testing.TestableContext;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.input.MotionEventBuilder;
import com.android.cts.input.PointerBuilder;

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
    private static final int TOUCHPAD_DEVICE_ID = 6;

    private TouchpadDebugView mTouchpadDebugView;
    private WindowManager.LayoutParams mWindowLayoutParams;

    @Mock
    WindowManager mWindowManager;

    Rect mWindowBounds;
    WindowMetrics mWindowMetrics;
    TestableContext mTestableContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mTestableContext = new TestableContext(context);

        mTestableContext.addMockSystemService(WindowManager.class, mWindowManager);

        mWindowBounds = new Rect(0, 0, 2560, 1600);
        mWindowMetrics = new WindowMetrics(mWindowBounds, new WindowInsets(mWindowBounds), 1.0f);

        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);

        mTouchpadDebugView = new TouchpadDebugView(mTestableContext, TOUCHPAD_DEVICE_ID);

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
}
