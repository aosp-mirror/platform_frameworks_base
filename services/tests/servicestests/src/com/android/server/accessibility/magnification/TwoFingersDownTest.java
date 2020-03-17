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

import androidx.test.InstrumentationRegistry;

import com.android.server.accessibility.utils.TouchEventGenerator;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link TwoFingersDown}.
 */
public class TwoFingersDownTest {

    private static final float DEFAULT_X = 100f;
    private static final float DEFAULT_Y = 100f;

    private static Context sContext;
    private static int sTimeoutMillis;

    private Context mContext;
    private GesturesObserver mGesturesObserver;
    @Mock
    private GesturesObserver.Listener mListener;

    @BeforeClass
    public static void setupOnce() {
        sContext = InstrumentationRegistry.getContext();
        sTimeoutMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                sContext) + 100;
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        MockitoAnnotations.initMocks(this);
        mGesturesObserver = new GesturesObserver(mListener, new TwoFingersDown(mContext));
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
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent.PointerCoords defPointerCoords = new MotionEvent.PointerCoords();
        defPointerCoords.x = DEFAULT_X;
        defPointerCoords.y = DEFAULT_Y;
        final MotionEvent.PointerCoords secondPointerCoords = new MotionEvent.PointerCoords();
        secondPointerCoords.x = DEFAULT_X + 10;
        secondPointerCoords.y = DEFAULT_Y + 10;

        final MotionEvent pointerDownEvent = TouchEventGenerator.pointerDownEvent(
                Display.DEFAULT_DISPLAY, defPointerCoords, secondPointerCoords);

        mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
        mGesturesObserver.onMotionEvent(pointerDownEvent, pointerDownEvent, 0);

        verify(mListener, timeout(sTimeoutMillis)).onGestureCompleted(
                MagnificationGestureMatcher.GESTURE_TWO_FINGER_DOWN, pointerDownEvent,
                pointerDownEvent, 0);
    }

    @Test
    public void sendSingleTapEvent_onGestureCancelled() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent upEvent = TouchEventGenerator.upEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);

        mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
        mGesturesObserver.onMotionEvent(upEvent, upEvent, 0);

        verify(mListener, timeout(sTimeoutMillis)).onGestureCancelled(any(MotionEvent.class),
                any(MotionEvent.class), eq(0));
    }
}
