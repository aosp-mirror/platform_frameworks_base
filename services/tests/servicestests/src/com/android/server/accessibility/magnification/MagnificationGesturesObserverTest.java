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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.InstrumentationRegistry;

import com.android.server.accessibility.utils.TouchEventGenerator;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Tests for MagnificationGesturesObserver.
 */
public class MagnificationGesturesObserverTest {

    private static final float DEFAULT_X = 100f;
    private static final float DEFAULT_Y = 100f;

    @Mock
    private MagnificationGesturesObserver.Callback mCallback;
    @Captor
    private ArgumentCaptor<List<MotionEventInfo>> mEventInfoArgumentCaptor;

    private Context mContext;
    private Instrumentation mInstrumentation;
    private MagnificationGesturesObserver mObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mObserver = new MagnificationGesturesObserver(mCallback, new SimpleSwipe(mContext),
                new TwoFingersDownOrSwipe(mContext));
    }

    @Test
    public void onActionMove_onGestureCanceled() {
        final MotionEvent moveEvent = TouchEventGenerator.moveEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X , DEFAULT_Y);

        mObserver.onMotionEvent(moveEvent, moveEvent, 0);

        verify(mCallback).onGestureCancelled(eq(0L),
                mEventInfoArgumentCaptor.capture(), argThat(new MotionEventMatcher(moveEvent)));
        verifyCacheMotionEvents(mEventInfoArgumentCaptor.getValue(), moveEvent);
    }

    @Test
    public void onActionDown_shouldNotDetection_onGestureCanceled() {
        when(mCallback.shouldStopDetection(any(MotionEvent.class))).thenReturn(true);
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X , DEFAULT_Y);

        mObserver.onMotionEvent(downEvent, downEvent, 0);

        verify(mCallback).onGestureCancelled(eq(0L),
                mEventInfoArgumentCaptor.capture(), argThat(new MotionEventMatcher(downEvent)));
        verifyCacheMotionEvents(mEventInfoArgumentCaptor.getValue(), downEvent);
    }

    @Test
    public void onMotionEvent_unrecognizedEvents_onDetectionCanceledAfterTimeout() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final int timeoutMillis = MagnificationGestureMatcher.getMagnificationMultiTapTimeout(
                mContext) + 100;

        mObserver.onMotionEvent(downEvent, downEvent, 0);

        verify(mCallback, timeout(timeoutMillis)).onGestureCancelled(eq(downEvent.getDownTime()),
                mEventInfoArgumentCaptor.capture(), argThat(new MotionEventMatcher(downEvent)));
        verifyCacheMotionEvents(mEventInfoArgumentCaptor.getValue(), downEvent);
    }

    @Test
    public void sendEventsOfSwiping_onGestureCompleted() {
        final MotionEvent downEvent = TouchEventGenerator.downEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X, DEFAULT_Y);
        final float swipeDistance = ViewConfiguration.get(mContext).getScaledTouchSlop() + 1;
        final MotionEvent moveEvent = TouchEventGenerator.moveEvent(Display.DEFAULT_DISPLAY,
                DEFAULT_X + swipeDistance, DEFAULT_Y + swipeDistance);

        mObserver.onMotionEvent(downEvent, downEvent, 0);
        mObserver.onMotionEvent(moveEvent, moveEvent, 0);

        verify(mCallback).onGestureCompleted(eq(MagnificationGestureMatcher.GESTURE_SWIPE),
                eq(downEvent.getDownTime()), mEventInfoArgumentCaptor.capture(),
                argThat(new MotionEventMatcher(moveEvent)));
        verifyCacheMotionEvents(mEventInfoArgumentCaptor.getValue(), downEvent, moveEvent);
    }

    private static class MotionEventMatcher implements ArgumentMatcher<MotionEvent> {

        private final MotionEvent mExpectedEvent;
        MotionEventMatcher(MotionEvent motionEvent) {
            mExpectedEvent = motionEvent;
        }

        @Override
        public boolean matches(MotionEvent actualEvent) {
            return compareMotionEvent(mExpectedEvent, actualEvent);
        }
    }

    private static boolean compareMotionEvent(MotionEvent expectedEvent, MotionEvent actualEvent) {
        if (expectedEvent == null || actualEvent == null) {
            return false;
        }
        return expectedEvent.toString().contentEquals(actualEvent.toString());
    }

    private static void verifyCacheMotionEvents(List<MotionEventInfo> actualEvents,
            MotionEvent... expectedEvents) {
        Assert.assertEquals("events size doesn't match", expectedEvents.length,
                actualEvents.size());
        for (int i = 0; i < actualEvents.size(); i++) {
            Assert.assertTrue(compareMotionEvent(expectedEvents[i], actualEvents.get(i).mEvent));
        }
    }
}
