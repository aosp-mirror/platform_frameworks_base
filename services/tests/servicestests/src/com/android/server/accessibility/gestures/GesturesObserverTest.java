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

package com.android.server.accessibility.gestures;

import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.view.Display;
import android.view.MotionEvent;

import androidx.test.InstrumentationRegistry;

import com.android.server.accessibility.magnification.GesturesObserver;
import com.android.server.accessibility.utils.TouchEventGenerator;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for GesturesObserver.
 */
public class GesturesObserverTest {

    private static final int GESTURE_STUB = 1;
    private static final int GESTURE_STUB2 = 2;
    private static final float DEFAULT_X = 100f;
    private static final float DEFAULT_Y = 100f;

    private GesturesObserver mGesturesObserver;
    @Mock
    private GesturesObserver.Listener mListener;

    private Context mContext;
    private Instrumentation mInstrumentation;
    private GestureMatcherStub mGestureMatcher;
    private GestureMatcherStub mGestureMatcher2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mGestureMatcher = new GestureMatcherStub(mContext, GESTURE_STUB);
        mGestureMatcher2 = new GestureMatcherStub(mContext, GESTURE_STUB2);
        mGesturesObserver = new GesturesObserver(mListener, mGestureMatcher, mGestureMatcher2);
    }

    @Test
    public void onActionMove_onGestureCancelled() {
        final MotionEvent moveEvent = TouchEventGenerator.moveEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X , DEFAULT_Y);

        mInstrumentation.runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(moveEvent, moveEvent, 0);
        });
        verify(mListener).onGestureCancelled(any(MotionEvent.class), any(MotionEvent.class), eq(0));
        assertTrue(mGestureMatcher.mInvocationMove == 0);
    }

    @Test
    public void onMotionEvent_unrecognizedEvents_onGestureCancelledAfterTimeout() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);

        mInstrumentation.runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
        });

        verify(mListener, timeout(GestureMatcherStub.TIMEOUT_MILLIS + 100)).onGestureCancelled(
                any(MotionEvent.class), any(MotionEvent.class), eq(0));
        assertTrue(mGestureMatcher.mInvocationDown == 1);
    }

    @Test
    public void onMotionEvent_recognizedEvents_onGestureCompleted() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final MotionEvent moveEvent = TouchEventGenerator.moveEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X , DEFAULT_Y);

        mInstrumentation.runOnMainSync(() -> {
            mGesturesObserver.onMotionEvent(downEvent, downEvent, 0);
            mGesturesObserver.onMotionEvent(moveEvent, moveEvent, 0);
        });

        verify(mListener).onGestureCompleted(eq(GESTURE_STUB), any(MotionEvent.class),
                any(MotionEvent.class), eq(0));
        assertTrue(mGestureMatcher.mInvocationMove == 1);
    }

    private static class GestureMatcherStub extends GestureMatcher {

        private static final int TIMEOUT_MILLIS = 100;
        private int mInvocationMove = 0;
        private int mInvocationDown = 0;
        protected GestureMatcherStub(Context context, int gestureId) {
            super(gestureId, context.getMainThreadHandler(), null);
        }

        @Override
        protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mInvocationDown++;
            cancelAfter(TIMEOUT_MILLIS, event, rawEvent, policyFlags);
        }

        @Override
        protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mInvocationMove++;
            cancelPendingTransitions();
            completeGesture(event, rawEvent, policyFlags);
        }

        @Override
        protected String getGestureName() {
            return getClass().getSimpleName();
        }
    }
}
