/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.accessibilityservice.IAccessibilityServiceClient;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import android.util.Pair;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy;
import java.util.ArrayList;
import java.util.List;

import android.view.accessibility.AccessibilityEvent;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

/**
 * Tests for MotionEventInjector
 */
@RunWith(AndroidJUnit4.class)
public class MotionEventInjectorTest {
    private static final String LOG_TAG = "MotionEventInjectorTest";
    private static final int CLICK_X = 100;
    private static final int CLICK_Y_START = 200;
    private static final int CLICK_Y_END = 201;
    private static final int CLICK_DURATION = 10;
    private static final int SEQUENCE = 50;

    private static final int SECOND_CLICK_X = 1000;
    private static final int SECOND_CLICK_Y = 2000;
    private static final int SECOND_SEQUENCE = 51;

    private static final int MOTION_EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final int OTHER_EVENT_SOURCE = InputDevice.SOURCE_MOUSE;

    MotionEventInjector mMotionEventInjector;
    IAccessibilityServiceClient mServiceInterface;
    List<MotionEvent> mClickList = new ArrayList<>();
    List<MotionEvent> mSecondClickList = new ArrayList<>();
    ArgumentCaptor<MotionEvent> mCaptor1 = ArgumentCaptor.forClass(MotionEvent.class);
    ArgumentCaptor<MotionEvent> mCaptor2 = ArgumentCaptor.forClass(MotionEvent.class);
    MessageCapturingHandler mMessageCapturingHandler;
    MotionEventMatcher mClickEvent0Matcher;
    MotionEventMatcher mClickEvent1Matcher;
    MotionEventMatcher mClickEvent2Matcher;
    MotionEventMatcher mSecondClickEvent0Matcher;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setUp() {
        mMessageCapturingHandler = new MessageCapturingHandler();
        mMotionEventInjector = new MotionEventInjector(mMessageCapturingHandler);
        mClickList.add(
                MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, CLICK_X, CLICK_Y_START, 0));
        mClickList.add(MotionEvent.obtain(
                0, CLICK_DURATION, MotionEvent.ACTION_MOVE, CLICK_X, CLICK_Y_END, 0));
        mClickList.add(MotionEvent.obtain(
                0, CLICK_DURATION, MotionEvent.ACTION_UP, CLICK_X, CLICK_Y_END, 0));
        for (int i = 0; i < mClickList.size(); i++) {
            mClickList.get(i).setSource(MOTION_EVENT_SOURCE);
        }

        mClickEvent0Matcher = new MotionEventMatcher(mClickList.get(0));
        mClickEvent1Matcher = new MotionEventMatcher(mClickList.get(1));
        mClickEvent2Matcher = new MotionEventMatcher(mClickList.get(2));

        mSecondClickList.add(MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_DOWN, SECOND_CLICK_X, SECOND_CLICK_Y, 0));
        mSecondClickList.add(MotionEvent.obtain(
                0, CLICK_DURATION, MotionEvent.ACTION_MOVE, SECOND_CLICK_X, CLICK_Y_END, 0));
        mSecondClickList.add(MotionEvent.obtain(
                0, CLICK_DURATION, MotionEvent.ACTION_UP, SECOND_CLICK_X, CLICK_Y_END, 0));
        for (int i = 0; i < mSecondClickList.size(); i++) {
            mSecondClickList.get(i).setSource(MOTION_EVENT_SOURCE);
        }

        mSecondClickEvent0Matcher = new MotionEventMatcher(mSecondClickList.get(0));

        mServiceInterface = mock(IAccessibilityServiceClient.class);
    }

    @Test
    public void testInjectEvents_shouldEmergeInOrderWithCorrectTiming() throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        verifyNoMoreInteractions(next);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(next).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        long gestureStart = mCaptor1.getValue().getDownTime();
        mClickEvent0Matcher.offsetTimesBy(gestureStart);
        mClickEvent1Matcher.offsetTimesBy(gestureStart);
        mClickEvent2Matcher.offsetTimesBy(gestureStart);

        verify(next).onMotionEvent(argThat(mClickEvent0Matcher), argThat(mClickEvent0Matcher),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        verifyNoMoreInteractions(next);
        reset(next);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(argThat(mClickEvent1Matcher), argThat(mClickEvent1Matcher),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        verifyNoMoreInteractions(next);
        reset(next);

        verifyZeroInteractions(mServiceInterface);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(argThat(mClickEvent2Matcher), argThat(mClickEvent2Matcher),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        verifyNoMoreInteractions(next);
        reset(next);

        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, true);
        verifyNoMoreInteractions(mServiceInterface);
    }

    @Test
    public void testInjectEvents_eventWithManyPointers_shouldNotCrash() {
        int manyPointersCount = 20;
        MotionEvent.PointerCoords[] pointerCoords =
                new MotionEvent.PointerCoords[manyPointersCount];
        MotionEvent.PointerProperties[] pointerProperties =
                new MotionEvent.PointerProperties[manyPointersCount];
        for (int i = 0; i < manyPointersCount; i++) {
            pointerProperties[i] = new MotionEvent.PointerProperties();
            pointerProperties[i].id = i;
            pointerProperties[i].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            pointerCoords[i] = new MotionEvent.PointerCoords();
            pointerCoords[i].clear();
            pointerCoords[i].pressure = 1.0f;
            pointerCoords[i].size = 1.0f;
            pointerCoords[i].x = i;
            pointerCoords[i].y = i;
        }
        List<MotionEvent> events = new ArrayList<>();
        events.add(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, manyPointersCount,
                pointerProperties, pointerCoords, 0, 0,
                1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
        events.add(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, manyPointersCount,
                pointerProperties, pointerCoords, 0, 0,
                1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.injectEvents(events, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendAllMessages();
        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertEquals(MotionEvent.ACTION_DOWN, mCaptor1.getAllValues().get(0).getActionMasked());
        assertEquals(MotionEvent.ACTION_UP, mCaptor1.getAllValues().get(1).getActionMasked());
    }

    @Test
    public void testRegularEvent_afterGestureComplete_shouldPassToNext() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        reset(next);
        mMotionEventInjector.onMotionEvent(mSecondClickList.get(0), mClickList.get(0), 0);
        verify(next).onMotionEvent(argThat(mSecondClickEvent0Matcher),
                argThat(mClickEvent0Matcher), eq(0));
    }

    @Test
    public void testInjectEvents_withRealGestureUnderway_shouldCancelRealAndPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickList.get(0), mClickList.get(0), 0);
        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection

        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertEquals(MotionEvent.ACTION_CANCEL, mCaptor1.getAllValues().get(1).getActionMasked());
        reset(next);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        long gestureStart = mCaptor1.getValue().getDownTime();
        mSecondClickEvent0Matcher.offsetTimesBy(gestureStart);

        verify(next).onMotionEvent(argThat(mSecondClickEvent0Matcher),
                argThat(mSecondClickEvent0Matcher), eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
    }

    @Test
    public void testInjectEvents_withRealMouseGestureUnderway_shouldContinueRealAndPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        MotionEvent mouseEvent = MotionEvent.obtain(mClickList.get(0));
        mouseEvent.setSource(InputDevice.SOURCE_MOUSE);
        MotionEventMatcher mouseEventMatcher = new MotionEventMatcher(mouseEvent);
        mMotionEventInjector.onMotionEvent(mouseEvent, mouseEvent, 0);
        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertTrue(mouseEventMatcher.matches(mCaptor1.getAllValues().get(0)));
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(1).getDownTime());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(1)));
    }

    @Test
    public void testInjectEvents_withRealGestureFinished_shouldJustPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickList.get(0), mClickList.get(0), 0);
        mMotionEventInjector.onMotionEvent(mClickList.get(1), mClickList.get(1), 0);
        mMotionEventInjector.onMotionEvent(mClickList.get(2), mClickList.get(2), 0);

        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());

        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertTrue(mClickEvent1Matcher.matches(mCaptor1.getAllValues().get(1)));
        assertTrue(mClickEvent2Matcher.matches(mCaptor1.getAllValues().get(2)));
        reset(next);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(),
                eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getValue().getDownTime());
        verify(next).onMotionEvent(argThat(mSecondClickEvent0Matcher),
                argThat(mSecondClickEvent0Matcher), eq(WindowManagerPolicy.FLAG_PASS_TO_USER));
    }

    @Test
    public void testOnMotionEvents_openInjectedGestureInProgress_shouldCancelAndNotifyAndPassReal()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        mMotionEventInjector.onMotionEvent(mSecondClickList.get(0), mSecondClickList.get(0), 0);

        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        mClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(0).getDownTime());
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertEquals(MotionEvent.ACTION_CANCEL, mCaptor1.getAllValues().get(1).getActionMasked());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(2)));
        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, false);
    }

    @Test
    public void testOnMotionEvents_closedInjectedGestureInProgress_shouldOnlyNotifyAndPassReal()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mClickList.add(MotionEvent.obtain(2 * CLICK_DURATION, 2 * CLICK_DURATION,
                MotionEvent.ACTION_DOWN, CLICK_X, CLICK_Y_START, 0));
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection

        // Send 3 motion events, leaving the extra down in the queue
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();

        mMotionEventInjector.onMotionEvent(mSecondClickList.get(0), mClickList.get(0), 0);

        verify(next, times(4)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        long gestureStart = mCaptor1.getAllValues().get(0).getDownTime();
        mClickEvent0Matcher.offsetTimesBy(gestureStart);
        mClickEvent1Matcher.offsetTimesBy(gestureStart);
        mClickEvent2Matcher.offsetTimesBy(gestureStart);
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertTrue(mClickEvent1Matcher.matches(mCaptor1.getAllValues().get(1)));
        assertTrue(mClickEvent2Matcher.matches(mCaptor1.getAllValues().get(2)));
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(3)));

        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, false);
        assertFalse(mMessageCapturingHandler.hasMessages());
    }

    @Test
    public void testInjectEvents_openInjectedGestureInProgress_shouldCancelAndNotifyAndPassNew()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SECOND_SEQUENCE);
        mMessageCapturingHandler.sendLastMessage(); // Process the second event injection
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(mServiceInterface, times(1)).onPerformGestureResult(SEQUENCE, false);
        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        mClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(0).getDownTime());
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertEquals(MotionEvent.ACTION_CANCEL, mCaptor1.getAllValues().get(1).getActionMasked());
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(2).getDownTime());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(2)));
    }

    @Test
    public void testInjectEvents_closedInjectedGestureInProgress_shouldOnlyNotifyAndPassNew()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        MotionEvent newEvent = MotionEvent.obtain(2 * CLICK_DURATION, 2 * CLICK_DURATION,
                MotionEvent.ACTION_DOWN, CLICK_X, CLICK_Y_START, 0);
        newEvent.setSource(mClickList.get(0).getSource());
        mClickList.add(newEvent);
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection

        // Send 3 motion events, leaving newEvent in the queue
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();

        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SECOND_SEQUENCE);
        mMessageCapturingHandler.sendLastMessage(); // Process the event injection
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, false);
        verify(next, times(4)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        long gestureStart = mCaptor1.getAllValues().get(0).getDownTime();
        mClickEvent0Matcher.offsetTimesBy(gestureStart);
        mClickEvent1Matcher.offsetTimesBy(gestureStart);
        mClickEvent2Matcher.offsetTimesBy(gestureStart);
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertTrue(mClickEvent1Matcher.matches(mCaptor1.getAllValues().get(1)));
        assertTrue(mClickEvent2Matcher.matches(mCaptor1.getAllValues().get(2)));
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(3).getDownTime());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(3)));
    }

    @Test
    public void testClearEvents_realGestureInProgress_shouldForgetAboutGesture() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickList.get(0), mClickList.get(0), 0);
        mMotionEventInjector.clearEvents(MOTION_EVENT_SOURCE);
        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(1).getDownTime());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(1)));
    }

    @Test
    public void testClearEventsOnOtherSource_realGestureInProgress_shouldNotForgetAboutGesture() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickList.get(0), mClickList.get(0), 0);
        mMotionEventInjector.clearEvents(OTHER_EVENT_SOURCE);
        mMotionEventInjector.injectEvents(mSecondClickList, mServiceInterface, SECOND_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertTrue(mClickEvent0Matcher.matches(mCaptor1.getAllValues().get(0)));
        assertEquals(MotionEvent.ACTION_CANCEL, mCaptor1.getAllValues().get(1).getActionMasked());
        mSecondClickEvent0Matcher.offsetTimesBy(mCaptor1.getAllValues().get(2).getDownTime());
        assertTrue(mSecondClickEvent0Matcher.matches(mCaptor1.getAllValues().get(2)));
    }

    @Test
    public void testOnDestroy_shouldCancelGestures() throws RemoteException {
        mMotionEventInjector.onDestroy();
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, false);
    }

    @Test
    public void testInjectEvents_withNoNext_shouldCancel() throws RemoteException {
        mMotionEventInjector.injectEvents(mClickList, mServiceInterface, SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Process the event injection
        verify(mServiceInterface).onPerformGestureResult(SEQUENCE, false);
    }

    @Test
    public void testOnMotionEvent_withNoNext_shouldNotCrash() {
        mMotionEventInjector.onMotionEvent(mClickList.get(0), mClickList.get(0), 0);
    }

    @Test
    public void testOnKeyEvent_shouldPassToNext() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        KeyEvent event = new KeyEvent(0, 0);
        mMotionEventInjector.onKeyEvent(event, 0);
        verify(next).onKeyEvent(event, 0);
    }

    @Test
    public void testOnKeyEvent_withNoNext_shouldNotCrash() {
        KeyEvent event = new KeyEvent(0, 0);
        mMotionEventInjector.onKeyEvent(event, 0);
    }

    @Test
    public void testOnAccessibilityEvent_shouldPassToNext() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        AccessibilityEvent event = AccessibilityEvent.obtain();
        mMotionEventInjector.onAccessibilityEvent(event);
        verify(next).onAccessibilityEvent(event);
    }

    @Test
    public void testOnAccessibilityEvent_withNoNext_shouldNotCrash() {
        AccessibilityEvent event = AccessibilityEvent.obtain();
        mMotionEventInjector.onAccessibilityEvent(event);
    }

    private EventStreamTransformation attachMockNext(MotionEventInjector motionEventInjector) {
        EventStreamTransformation next = mock(EventStreamTransformation.class);
        motionEventInjector.setNext(next);
        return next;
    }

    static class MotionEventMatcher extends ArgumentMatcher<MotionEvent> {
        long mDownTime;
        long mEventTime;
        long mActionMasked;
        int mX;
        int mY;

        MotionEventMatcher(long downTime, long eventTime, int actionMasked, int x, int y) {
            mDownTime = downTime;
            mEventTime = eventTime;
            mActionMasked = actionMasked;
            mX = x;
            mY = y;
        }

        MotionEventMatcher(MotionEvent event) {
            this(event.getDownTime(), event.getEventTime(), event.getActionMasked(),
                    (int) event.getX(), (int) event.getY());
        }

        void offsetTimesBy(long timeOffset) {
            mDownTime += timeOffset;
            mEventTime += timeOffset;
        }

        @Override
        public boolean matches(Object o) {
            MotionEvent event = (MotionEvent) o;
            if ((event.getDownTime() == mDownTime) && (event.getEventTime() == mEventTime)
                    && (event.getActionMasked() == mActionMasked) && ((int) event.getX() == mX)
                    && ((int) event.getY() == mY)) {
                return true;
            }
            Log.e(LOG_TAG, "MotionEvent match failed");
            Log.e(LOG_TAG, "event.getDownTime() = " + event.getDownTime()
                    + ", expected " + mDownTime);
            Log.e(LOG_TAG, "event.getEventTime() = " + event.getEventTime()
                    + ", expected " + mEventTime);
            Log.e(LOG_TAG, "event.getActionMasked() = " + event.getActionMasked()
                    + ", expected " + mActionMasked);
            Log.e(LOG_TAG, "event.getX() = " + event.getX() + ", expected " + mX);
            Log.e(LOG_TAG, "event.getY() = " + event.getY() + ", expected " + mY);
            return false;
        }
    }

    private class MessageCapturingHandler extends Handler {
        List<Pair<Message, Long>> timedMessages = new ArrayList<>();

        @Override
        public boolean sendMessageAtTime(Message message, long uptimeMillis) {
            timedMessages.add(new Pair<>(Message.obtain(message), uptimeMillis));
            return super.sendMessageAtTime(message, uptimeMillis);
        }

        void sendOneMessage() {
            Message message = timedMessages.remove(0).first;
            removeMessages(message.what, message.obj);
            mMotionEventInjector.handleMessage(message);
            removeStaleMessages();
        }

        void sendAllMessages() {
            while (!timedMessages.isEmpty()) {
                sendOneMessage();
            }
        }

        void sendLastMessage() {
            Message message = timedMessages.remove(timedMessages.size() - 1).first;
            removeMessages(message.what, message.obj);
            mMotionEventInjector.handleMessage(message);
            removeStaleMessages();
        }

        boolean hasMessages() {
            removeStaleMessages();
            return !timedMessages.isEmpty();
        }

        private void removeStaleMessages() {
            for (int i = 0; i < timedMessages.size(); i++) {
                Message message = timedMessages.get(i).first;
                if (!hasMessages(message.what, message.obj)) {
                    timedMessages.remove(i--);
                }
            }
        }
    }
}
