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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;

import com.android.server.accessibility.utils.TouchEventGenerator;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SimpleSwipe}.
 */
public class SimpleSwipeTest {

    private static final float DEFAULT_X = 100f;
    private static final float DEFAULT_Y = 100f;

    private Context mContext;

    private GesturesObserver mGesturesObserver;
    @Mock
    private GesturesObserver.Listener mListener;

    @BeforeClass
    public static void setupOnce() {
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();
        mGesturesObserver = new GesturesObserver(mListener, new SimpleSwipe(mContext));
    }

    @Test
    public void sendSingleDownEvent_onGestureCanceledAfterTimeout() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final int timeoutMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                mContext);

        runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
        });

        verify(mListener, timeout(timeoutMillis + 100)).onGestureCancelled(any(MotionEvent.class),
                any(MotionEvent.class), eq(0));
    }

    @Test
    public void sendSwipeEvent_onGestureCompleted() {
        final float swipeDistance = ViewConfiguration.get(mContext).getScaledTouchSlop() + 1;
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent moveEvent = TouchEventGenerator.moveEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X + swipeDistance, DEFAULT_Y);

        runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
            mGesturesObserver.onMotionEvent(moveEvent, moveEvent, 0);
        });

        verify(mListener).onGestureCompleted(
                MagnificationGestureMatcher.GESTURE_SWIPE, moveEvent, moveEvent, 0);
    }

    @Test
    public void sendSingleTapEvent_onGestureCanceled() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent upEvent = TouchEventGenerator.upEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);

        runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
            mGesturesObserver.onMotionEvent(upEvent, upEvent, 0);
        });

        verify(mListener).onGestureCancelled(any(MotionEvent.class),
                any(MotionEvent.class), eq(0));
    }

    private static void runOnMainSync(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }
}
