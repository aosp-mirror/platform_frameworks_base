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

package com.android.systemui.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MagnificationGestureDetectorTest extends SysuiTestCase {

    private static final float ACTION_DOWN_X = 100;
    private static final float ACTION_DOWN_Y = 200;
    private int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private MagnificationGestureDetector mGestureDetector;
    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();
    private View mSpyView;
    @Mock
    private MagnificationGestureDetector.OnGestureListener mListener;
    @Mock
    private Handler mHandler;
    private Runnable mCancelSingleTapRunnable;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doAnswer((invocation) -> {
            mCancelSingleTapRunnable = invocation.getArgument(0);
            return null;
        }).when(mHandler).postAtTime(any(Runnable.class), anyLong());
        mGestureDetector = new MagnificationGestureDetector(mContext, mHandler, mListener);
        mSpyView = Mockito.spy(new View(mContext));
    }

    @After
    public void tearDown() {
        mMotionEventHelper.recycleEvents();
    }

    @Test
    public void onActionDown_invokeDownCallback() {
        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);

        mListener.onStart(ACTION_DOWN_X, ACTION_DOWN_Y);
    }

    @Test
    public void performSingleTap_invokeCallbacksInOrder() {
        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);
        final MotionEvent upEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_UP, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);
        mGestureDetector.onTouch(mSpyView, upEvent);

        InOrder inOrder = Mockito.inOrder(mListener);
        inOrder.verify(mListener).onStart(ACTION_DOWN_X, ACTION_DOWN_Y);
        inOrder.verify(mListener).onSingleTap(mSpyView);
        inOrder.verify(mListener).onFinish(ACTION_DOWN_X, ACTION_DOWN_Y);
        verify(mListener, never()).onDrag(eq(mSpyView), anyFloat(), anyFloat());
    }

    @Test
    public void performSingleTapWithActionCancel_notInvokeOnSingleTapCallback() {
        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);
        final MotionEvent cancelEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_CANCEL, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);
        mGestureDetector.onTouch(mSpyView, cancelEvent);

        verify(mListener, never()).onSingleTap(mSpyView);
    }

    @Test
    public void performSingleTapWithTwoPointers_notInvokeSingleTapCallback() {
        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);
        final MotionEvent upEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_POINTER_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);
        mGestureDetector.onTouch(mSpyView, upEvent);

        verify(mListener, never()).onSingleTap(mSpyView);
    }

    @Test
    public void performLongPress_invokeCallbacksInOrder() {
        final long downTime = SystemClock.uptimeMillis();
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);
        final MotionEvent upEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_UP, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);
        // Execute the pending message for stopping single-tap detection.
        mCancelSingleTapRunnable.run();
        mGestureDetector.onTouch(mSpyView, upEvent);

        InOrder inOrder = Mockito.inOrder(mListener);
        inOrder.verify(mListener).onStart(ACTION_DOWN_X, ACTION_DOWN_Y);
        inOrder.verify(mListener).onFinish(ACTION_DOWN_X, ACTION_DOWN_Y);
        verify(mListener, never()).onSingleTap(mSpyView);
    }

    @Test
    public void performDrag_invokeCallbacksInOrder() {
        final long downTime = SystemClock.uptimeMillis();
        final float dragOffset = mTouchSlop + 10;
        final MotionEvent downEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_DOWN, ACTION_DOWN_X, ACTION_DOWN_Y);
        final MotionEvent moveEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_MOVE, ACTION_DOWN_X + dragOffset, ACTION_DOWN_Y);
        final MotionEvent upEvent = mMotionEventHelper.obtainMotionEvent(downTime, downTime,
                MotionEvent.ACTION_UP, ACTION_DOWN_X, ACTION_DOWN_Y);

        mGestureDetector.onTouch(mSpyView, downEvent);
        mGestureDetector.onTouch(mSpyView, moveEvent);
        mGestureDetector.onTouch(mSpyView, upEvent);

        InOrder inOrder = Mockito.inOrder(mListener);
        inOrder.verify(mListener).onStart(ACTION_DOWN_X, ACTION_DOWN_Y);
        inOrder.verify(mListener).onDrag(mSpyView, dragOffset, 0);
        inOrder.verify(mListener).onFinish(ACTION_DOWN_X, ACTION_DOWN_Y);
        verify(mListener, never()).onSingleTap(mSpyView);
    }
}
