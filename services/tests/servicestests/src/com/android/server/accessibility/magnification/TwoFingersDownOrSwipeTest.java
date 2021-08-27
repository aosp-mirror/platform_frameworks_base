/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.accessibility.utils.TouchEventGenerator.movePointer;
import static com.android.server.accessibility.utils.TouchEventGenerator.twoPointersDownEvents;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.PointF;
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

import java.util.List;

/**
 * Tests for {@link TwoFingersDownOrSwipe}.
 */
public class TwoFingersDownOrSwipeTest {

    private static final float DEFAULT_X = 100f;
    private static final float DEFAULT_Y = 100f;

    private static float sSwipeMinDistance;
    private static int sTimeoutMillis;
    private static Context sContext;

    private GesturesObserver mGesturesObserver;
    @Mock
    private GesturesObserver.Listener mListener;

    @BeforeClass
    public static void setupOnce() {
        sContext = InstrumentationRegistry.getContext();
        sTimeoutMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                sContext) + 100;
        sSwipeMinDistance = ViewConfiguration.get(sContext).getScaledTouchSlop() + 1;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mGesturesObserver = new GesturesObserver(mListener, new TwoFingersDownOrSwipe(sContext));
    }

    @Test
    public void sendSingleDownEvent_GestureCanceledAfterTimeout() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);

        mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);

        verify(mListener, timeout(sTimeoutMillis)).onGestureCancelled(any(MotionEvent.class),
                any(MotionEvent.class), eq(0));
    }

    @Test
    public void sendTwoFingerDownEvent_onGestureCompleted() {
        final List<MotionEvent> downEvents = twoPointersDownEvents(Display.DEFAULT_DISPLAY,
                new PointF(DEFAULT_X, DEFAULT_Y), new PointF(DEFAULT_X + 10, DEFAULT_Y + 10));

        for (MotionEvent event : downEvents) {
            mGesturesObserver.onMotionEvent(event, event, 0);
        }

        verify(mListener, timeout(sTimeoutMillis)).onGestureCompleted(
                MagnificationGestureMatcher.GESTURE_TWO_FINGERS_DOWN_OR_SWIPE, downEvents.get(1),
                downEvents.get(1), 0);
    }

    @Test
    public void sendSingleTapEvent_onGestureCancelled() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent upEvent = TouchEventGenerator.upEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);

        mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
        mGesturesObserver.onMotionEvent(upEvent, upEvent, 0);

        verify(mListener, after(ViewConfiguration.getDoubleTapTimeout())).onGestureCancelled(
                any(MotionEvent.class), any(MotionEvent.class), eq(0));
    }

    @Test
    public void firstPointerMove_twoPointersDown_onGestureCompleted() {
        final List<MotionEvent> downEvents = twoPointersDownEvents(Display.DEFAULT_DISPLAY,
                new PointF(DEFAULT_X, DEFAULT_Y), new PointF(DEFAULT_X + 10, DEFAULT_Y + 10));
        for (MotionEvent event : downEvents) {
            mGesturesObserver.onMotionEvent(event, event, 0);
        }
        final MotionEvent moveEvent = movePointer(downEvents.get(1), 0, sSwipeMinDistance, 0);

        mGesturesObserver.onMotionEvent(moveEvent, moveEvent, 0);

        verify(mListener).onGestureCompleted(
                MagnificationGestureMatcher.GESTURE_TWO_FINGERS_DOWN_OR_SWIPE, moveEvent,
                moveEvent, 0);
    }

    @Test
    public void secondPointerMove_twoPointersDown_onGestureCompleted() {
        final List<MotionEvent> downEvents = twoPointersDownEvents(Display.DEFAULT_DISPLAY,
                new PointF(DEFAULT_X, DEFAULT_Y), new PointF(DEFAULT_X + 10, DEFAULT_Y + 10));
        for (MotionEvent event : downEvents) {
            mGesturesObserver.onMotionEvent(event, event, 0);
        }
        final MotionEvent moveEvent = movePointer(downEvents.get(1), 1, sSwipeMinDistance, 0);

        mGesturesObserver.onMotionEvent(moveEvent, moveEvent, 0);

        verify(mListener).onGestureCompleted(
                MagnificationGestureMatcher.GESTURE_TWO_FINGERS_DOWN_OR_SWIPE, moveEvent,
                moveEvent, 0);
    }
}
