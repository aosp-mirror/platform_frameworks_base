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

import static android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowManagerPolicyConstants.FLAG_INJECTED_FROM_ACCESSIBILITY;
import static android.view.WindowManagerPolicyConstants.FLAG_PASS_TO_USER;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.accessibilityservice.GestureDescription.GestureStep;
import android.accessibilityservice.GestureDescription.TouchPoint;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.graphics.Point;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.accessibility.utils.MotionEventMatcher;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for MotionEventInjector
 */
@RunWith(AndroidJUnit4.class)
public class MotionEventInjectorTest {
    private static final String LOG_TAG = "MotionEventInjectorTest";
    private static final Matcher<MotionEvent> IS_ACTION_DOWN =
            new MotionEventActionMatcher(ACTION_DOWN);
    private static final Matcher<MotionEvent> IS_ACTION_POINTER_DOWN =
            new MotionEventActionMatcher(MotionEvent.ACTION_POINTER_DOWN);
    private static final Matcher<MotionEvent> IS_ACTION_UP =
            new MotionEventActionMatcher(ACTION_UP);
    private static final Matcher<MotionEvent> IS_ACTION_POINTER_UP =
            new MotionEventActionMatcher(MotionEvent.ACTION_POINTER_UP);
    private static final Matcher<MotionEvent> IS_ACTION_CANCEL =
            new MotionEventActionMatcher(MotionEvent.ACTION_CANCEL);
    private static final Matcher<MotionEvent> IS_ACTION_MOVE =
            new MotionEventActionMatcher(MotionEvent.ACTION_MOVE);

    private static final Point LINE_START = new Point(100, 200);
    private static final Point LINE_END = new Point(100, 300);
    private static final int LINE_DURATION = 100;
    private static final int LINE_SEQUENCE = 50;

    private static final Point CLICK_POINT = new Point(1000, 2000);
    private static final int CLICK_DURATION = 10;
    private static final int CLICK_SEQUENCE = 51;

    private static final int MOTION_EVENT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final int OTHER_EVENT_SOURCE = InputDevice.SOURCE_MOUSE;

    private static final Point CONTINUED_LINE_START = new Point(500, 300);
    private static final Point CONTINUED_LINE_MID1 = new Point(500, 400);
    private static final Point CONTINUED_LINE_MID2 = new Point(600, 300);
    private static final Point CONTINUED_LINE_END = new Point(600, 400);
    private static final int CONTINUED_LINE_STROKE_ID_1 = 100;
    private static final int CONTINUED_LINE_STROKE_ID_2 = 101;
    private static final int CONTINUED_LINE_INTERVAL = 100;
    private static final int CONTINUED_LINE_SEQUENCE_1 = 52;
    private static final int CONTINUED_LINE_SEQUENCE_2 = 53;

    private static final float PRESSURE = 1;
    private static final float X_PRECISION = 1;
    private static final float Y_PRECISION = 1;
    private static final int EDGEFLAGS = 0;
    private static final float POINTER_SIZE = 1;
    private static final int METASTATE = 0;

    MotionEventInjector mMotionEventInjector;
    IAccessibilityServiceClient mServiceInterface;
    AccessibilityTraceManager mTrace;
    List<GestureStep> mLineList = new ArrayList<>();
    List<GestureStep> mClickList = new ArrayList<>();
    List<GestureStep> mContinuedLineList1 = new ArrayList<>();
    List<GestureStep> mContinuedLineList2 = new ArrayList<>();

    MotionEvent mClickDownEvent;
    MotionEvent mClickUpEvent;
    MotionEvent mHoverMoveEvent;

    ArgumentCaptor<MotionEvent> mCaptor1 = ArgumentCaptor.forClass(MotionEvent.class);
    ArgumentCaptor<MotionEvent> mCaptor2 = ArgumentCaptor.forClass(MotionEvent.class);
    MessageCapturingHandler mMessageCapturingHandler;
    Matcher<MotionEvent> mIsLineStart;
    Matcher<MotionEvent> mIsLineMiddle;
    Matcher<MotionEvent> mIsLineEnd;
    Matcher<MotionEvent> mIsClickDown;
    Matcher<MotionEvent> mIsClickUp;

    @Before
    public void setUp() {
        mMessageCapturingHandler = new MessageCapturingHandler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                return mMotionEventInjector.handleMessage(msg);
            }
        });
        mTrace = mock(AccessibilityTraceManager.class);
        mMotionEventInjector = new MotionEventInjector(mMessageCapturingHandler, mTrace);
        mServiceInterface = mock(IAccessibilityServiceClient.class);

        mLineList = createSimpleGestureFromPoints(0, 0, false, LINE_DURATION, LINE_START, LINE_END);
        mClickList = createSimpleGestureFromPoints(
                0, 0, false, CLICK_DURATION, CLICK_POINT, CLICK_POINT);
        mContinuedLineList1 = createSimpleGestureFromPoints(CONTINUED_LINE_STROKE_ID_1, 0, true,
                CONTINUED_LINE_INTERVAL, CONTINUED_LINE_START, CONTINUED_LINE_MID1);
        mContinuedLineList2 = createSimpleGestureFromPoints(CONTINUED_LINE_STROKE_ID_2,
                CONTINUED_LINE_STROKE_ID_1, false, CONTINUED_LINE_INTERVAL, CONTINUED_LINE_MID1,
                CONTINUED_LINE_MID2, CONTINUED_LINE_END);

        mClickDownEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, CLICK_POINT.x, CLICK_POINT.y,
                 PRESSURE, POINTER_SIZE, METASTATE, X_PRECISION, Y_PRECISION, VIRTUAL_KEYBOARD,
                 EDGEFLAGS);
        mClickDownEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        mClickUpEvent = MotionEvent.obtain(0, CLICK_DURATION, ACTION_UP, CLICK_POINT.x,
                CLICK_POINT.y, PRESSURE, POINTER_SIZE, METASTATE, X_PRECISION, Y_PRECISION,
                VIRTUAL_KEYBOARD, EDGEFLAGS);
        mClickUpEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        mHoverMoveEvent = MotionEvent.obtain(0, 0, ACTION_HOVER_MOVE, CLICK_POINT.x, CLICK_POINT.y,
                PRESSURE, POINTER_SIZE, METASTATE, X_PRECISION, Y_PRECISION, VIRTUAL_KEYBOARD,
                EDGEFLAGS);
        mHoverMoveEvent.setSource(InputDevice.SOURCE_MOUSE);

        mIsLineStart = allOf(IS_ACTION_DOWN, isAtPoint(LINE_START), hasStandardInitialization(),
                hasTimeFromDown(0));
        mIsLineMiddle = allOf(IS_ACTION_MOVE, isAtPoint(LINE_END), hasStandardInitialization(),
                hasTimeFromDown(LINE_DURATION));
        mIsLineEnd = allOf(IS_ACTION_UP, isAtPoint(LINE_END), hasStandardInitialization(),
                hasTimeFromDown(LINE_DURATION));
        mIsClickDown = allOf(IS_ACTION_DOWN, isAtPoint(CLICK_POINT), hasStandardInitialization(),
                hasTimeFromDown(0));
        mIsClickUp = allOf(IS_ACTION_UP, isAtPoint(CLICK_POINT), hasStandardInitialization(),
                hasTimeFromDown(CLICK_DURATION));
    }

    @After
    public void tearDown() {
        mMessageCapturingHandler.removeAllMessages();
    }


    @Test
    public void testInjectEvents_shouldEmergeInOrderWithCorrectTiming() throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        verifyNoMoreInteractions(next);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        final int expectedFlags = FLAG_PASS_TO_USER | FLAG_INJECTED_FROM_ACCESSIBILITY;
        verify(next).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), eq(expectedFlags));
        verify(next).onMotionEvent(argThat(mIsLineStart), argThat(mIsLineStart), eq(expectedFlags));
        verifyNoMoreInteractions(next);
        reset(next);

        Matcher<MotionEvent> hasRightDownTime = hasDownTime(mCaptor1.getValue().getDownTime());

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(argThat(allOf(mIsLineMiddle, hasRightDownTime)),
                argThat(allOf(mIsLineMiddle, hasRightDownTime)), eq(expectedFlags));
        verifyNoMoreInteractions(next);
        reset(next);

        verifyZeroInteractions(mServiceInterface);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(argThat(allOf(mIsLineEnd, hasRightDownTime)),
                argThat(allOf(mIsLineEnd, hasRightDownTime)), eq(expectedFlags));
        verifyNoMoreInteractions(next);

        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, true);
        verifyNoMoreInteractions(mServiceInterface);
    }

    @Test
    public void testInjectEvents_gestureWithTooManyPoints_shouldNotCrash() throws  Exception {
        int tooManyPointsCount = 20;
        TouchPoint[] startTouchPoints = new TouchPoint[tooManyPointsCount];
        TouchPoint[] endTouchPoints = new TouchPoint[tooManyPointsCount];
        for (int i = 0; i < tooManyPointsCount; i++) {
            startTouchPoints[i] = new TouchPoint();
            startTouchPoints[i].mIsStartOfPath = true;
            startTouchPoints[i].mX = i;
            startTouchPoints[i].mY = i;
            endTouchPoints[i] = new TouchPoint();
            endTouchPoints[i].mIsEndOfPath = true;
            endTouchPoints[i].mX = i;
            endTouchPoints[i].mY = i;
        }
        List<GestureStep> events = Arrays.asList(
                new GestureStep(0, tooManyPointsCount, startTouchPoints),
                new GestureStep(CLICK_DURATION, tooManyPointsCount, endTouchPoints));
        attachMockNext(mMotionEventInjector);
        injectEventsSync(events, mServiceInterface, CLICK_SEQUENCE);
        mMessageCapturingHandler.sendAllMessages();
        verify(mServiceInterface).onPerformGestureResult(eq(CLICK_SEQUENCE), anyBoolean());
    }

    @Test
    public void testRegularEvent_afterGestureComplete_shouldPassToNext() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        reset(next);
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
        verify(next).onMotionEvent(argThat(mIsClickDown), argThat(mIsClickDown),
                eq(FLAG_INJECTED_FROM_ACCESSIBILITY));
    }

    @Test
    public void testInjectEvents_withRealGestureUnderway_shouldCancelRealAndPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);

        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsClickDown);
        assertThat(mCaptor1.getAllValues().get(1), IS_ACTION_CANCEL);
        reset(next);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(
                argThat(mIsLineStart), argThat(mIsLineStart),
                eq(FLAG_PASS_TO_USER | FLAG_INJECTED_FROM_ACCESSIBILITY));
    }

    @Test
    public void testInjectEvents_withRealMouseGestureUnderway_shouldContinueRealAndPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        MotionEvent mouseEvent = MotionEvent.obtain(mClickDownEvent);
        mouseEvent.setSource(InputDevice.SOURCE_MOUSE);
        MotionEventMatcher isMouseEvent = new MotionEventMatcher(mouseEvent);
        mMotionEventInjector.onMotionEvent(mouseEvent, mouseEvent, 0);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), isMouseEvent);
        assertThat(mCaptor1.getAllValues().get(1), mIsLineStart);
    }

    @Test
    public void testInjectEvents_withRealGestureFinished_shouldJustPassInjected() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
        mMotionEventInjector.onMotionEvent(mClickUpEvent, mClickUpEvent, 0);

        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsClickDown);
        assertThat(mCaptor1.getAllValues().get(1), mIsClickUp);
        reset(next);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        verify(next).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(),
                eq(FLAG_PASS_TO_USER | FLAG_INJECTED_FROM_ACCESSIBILITY));
        verify(next).onMotionEvent(
                argThat(mIsLineStart), argThat(mIsLineStart),
                eq(FLAG_PASS_TO_USER | FLAG_INJECTED_FROM_ACCESSIBILITY));
    }

    @Test
    public void testOnMotionEvents_openInjectedGestureInProgress_shouldCancelAndNotifyAndPassReal()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);

        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsLineStart);
        assertThat(mCaptor1.getAllValues().get(1), IS_ACTION_CANCEL);
        assertThat(mCaptor1.getAllValues().get(2), mIsClickDown);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, false);
    }

    @Test
    public void
            testOnMotionEvents_fromMouseWithInjectedGestureInProgress_shouldNotCancelAndPassReal()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        mMotionEventInjector.onMotionEvent(mHoverMoveEvent, mHoverMoveEvent, 0);
        mMessageCapturingHandler.sendAllMessages();

        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsLineStart);
        assertThat(mCaptor1.getAllValues().get(1), mIsLineMiddle);
        assertThat(mCaptor1.getAllValues().get(2), mIsLineEnd);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, true);
    }

    @Test
    public void testOnMotionEvents_closedInjectedGestureInProgress_shouldOnlyNotifyAndPassReal()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        // Tack a click down to the end of the line
        TouchPoint clickTouchPoint = new TouchPoint();
        clickTouchPoint.mIsStartOfPath = true;
        clickTouchPoint.mX = CLICK_POINT.x;
        clickTouchPoint.mY = CLICK_POINT.y;
        mLineList.add(new GestureStep(0, 1, new TouchPoint[] {clickTouchPoint}));

        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);

        // Send 3 motion events, leaving the extra down in the queue
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();

        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);

        verify(next, times(4)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsLineStart);
        assertThat(mCaptor1.getAllValues().get(1), mIsLineMiddle);
        assertThat(mCaptor1.getAllValues().get(2), mIsLineEnd);
        assertThat(mCaptor1.getAllValues().get(3), mIsClickDown);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, false);
        assertFalse(mMessageCapturingHandler.hasMessages());
    }

    @Test
    public void testInjectEvents_openInjectedGestureInProgress_shouldCancelAndNotifyAndPassNew()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        injectEventsSync(mClickList, mServiceInterface, CLICK_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(mServiceInterface, times(1)).onPerformGestureResult(LINE_SEQUENCE, false);
        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsLineStart);
        assertThat(mCaptor1.getAllValues().get(1), IS_ACTION_CANCEL);
        assertThat(mCaptor1.getAllValues().get(2), mIsClickDown);
    }

    @Test
    public void testInjectEvents_closedInjectedGestureInProgress_shouldOnlyNotifyAndPassNew()
            throws RemoteException {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        // Tack a click down to the end of the line
        TouchPoint clickTouchPoint = new TouchPoint();
        clickTouchPoint.mIsStartOfPath = true;
        clickTouchPoint.mX = CLICK_POINT.x;
        clickTouchPoint.mY = CLICK_POINT.y;
        mLineList.add(new GestureStep(0, 1, new TouchPoint[] {clickTouchPoint}));
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);

        // Send 3 motion events, leaving newEvent in the queue
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();
        mMessageCapturingHandler.sendOneMessage();

        injectEventsSync(mClickList, mServiceInterface, CLICK_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, false);
        verify(next, times(4)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsLineStart);
        assertThat(mCaptor1.getAllValues().get(1), mIsLineMiddle);
        assertThat(mCaptor1.getAllValues().get(2), mIsLineEnd);
        assertThat(mCaptor1.getAllValues().get(3), mIsClickDown);
    }

    @Test
    public void testContinuedGesture_continuationArrivesAfterDispatched_gestureCompletes()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        injectEventsSync(mContinuedLineList2, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, true);
        verify(next, times(5)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        long downTime = events.get(0).getDownTime();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN,
                hasEventTime(downTime)));
        assertThat(events, everyItem(hasDownTime(downTime)));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL)));
        // Timing will restart when the gesture continues
        long secondSequenceStart = events.get(2).getEventTime();
        assertTrue(secondSequenceStart >= events.get(1).getEventTime());
        assertThat(events.get(2), allOf(isAtPoint(CONTINUED_LINE_MID2), IS_ACTION_MOVE));
        assertThat(events.get(3), allOf(isAtPoint(CONTINUED_LINE_END), IS_ACTION_MOVE,
                hasEventTime(secondSequenceStart + CONTINUED_LINE_INTERVAL)));
        assertThat(events.get(4), allOf(isAtPoint(CONTINUED_LINE_END), IS_ACTION_UP,
                hasEventTime(secondSequenceStart + CONTINUED_LINE_INTERVAL)));
    }

    @Test
    public void testContinuedGesture_withTwoTouchPoints_gestureCompletes()
            throws Exception {
        // Run one point through the continued line backwards
        int backLineId1 = 30;
        int backLineId2 = 30;
        List<GestureStep> continuedBackLineList1 = createSimpleGestureFromPoints(backLineId1, 0,
                true, CONTINUED_LINE_INTERVAL, CONTINUED_LINE_END, CONTINUED_LINE_MID2);
        List<GestureStep> continuedBackLineList2 = createSimpleGestureFromPoints(backLineId2,
                backLineId1, false, CONTINUED_LINE_INTERVAL, CONTINUED_LINE_MID2,
                CONTINUED_LINE_MID1, CONTINUED_LINE_START);
        List<GestureStep> combinedLines1 = combineGestureSteps(
                mContinuedLineList1, continuedBackLineList1);
        List<GestureStep> combinedLines2 = combineGestureSteps(
                mContinuedLineList2, continuedBackLineList2);

        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(combinedLines1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        injectEventsSync(combinedLines2, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, true);
        verify(next, times(7)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        long downTime = events.get(0).getDownTime();
        assertThat(events.get(0), allOf(
                anyOf(isAtPoint(CONTINUED_LINE_END), isAtPoint(CONTINUED_LINE_START)),
                IS_ACTION_DOWN, hasEventTime(downTime)));
        assertThat(events, everyItem(hasDownTime(downTime)));
        assertThat(events.get(1), allOf(containsPoints(CONTINUED_LINE_START, CONTINUED_LINE_END),
                IS_ACTION_POINTER_DOWN, hasEventTime(downTime)));
        assertThat(events.get(2), allOf(containsPoints(CONTINUED_LINE_MID1, CONTINUED_LINE_MID2),
                IS_ACTION_MOVE, hasEventTime(downTime + CONTINUED_LINE_INTERVAL)));
        assertThat(events.get(3), allOf(containsPoints(CONTINUED_LINE_MID1, CONTINUED_LINE_MID2),
                IS_ACTION_MOVE, hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 2)));
        assertThat(events.get(4), allOf(containsPoints(CONTINUED_LINE_START, CONTINUED_LINE_END),
                IS_ACTION_MOVE, hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
        assertThat(events.get(5), allOf(containsPoints(CONTINUED_LINE_START, CONTINUED_LINE_END),
                IS_ACTION_POINTER_UP, hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
        assertThat(events.get(6), allOf(
                anyOf(isAtPoint(CONTINUED_LINE_END), isAtPoint(CONTINUED_LINE_START)),
                IS_ACTION_UP, hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
    }


    @Test
    public void testContinuedGesture_continuationArrivesWhileDispatching_gestureCompletes()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        injectEventsSync(mContinuedLineList2, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, true);
        verify(next, times(5)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        long downTime = events.get(0).getDownTime();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN,
                hasEventTime(downTime)));
        assertThat(events, everyItem(hasDownTime(downTime)));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL)));
        assertThat(events.get(2), allOf(isAtPoint(CONTINUED_LINE_MID2), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 2)));
        assertThat(events.get(3), allOf(isAtPoint(CONTINUED_LINE_END), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
        assertThat(events.get(4), allOf(isAtPoint(CONTINUED_LINE_END), IS_ACTION_UP,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
    }

    @Test
    public void testContinuedGesture_twoContinuationsArriveWhileDispatching_gestureCompletes()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        // Continue line again
        List<GestureStep> continuedLineList2 = createSimpleGestureFromPoints(
                CONTINUED_LINE_STROKE_ID_2, CONTINUED_LINE_STROKE_ID_1, true,
                CONTINUED_LINE_INTERVAL, CONTINUED_LINE_MID1,
                CONTINUED_LINE_MID2, CONTINUED_LINE_END);
        // Finish line by backtracking
        int strokeId3 = CONTINUED_LINE_STROKE_ID_2 + 1;
        int sequence3 = CONTINUED_LINE_SEQUENCE_2 + 1;
        List<GestureStep> continuedLineList3 = createSimpleGestureFromPoints(strokeId3,
                CONTINUED_LINE_STROKE_ID_2, false, CONTINUED_LINE_INTERVAL, CONTINUED_LINE_END,
                CONTINUED_LINE_MID2);

        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        injectEventsSync(continuedLineList2, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        injectEventsSync(continuedLineList3, mServiceInterface, sequence3);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, true);
        verify(mServiceInterface).onPerformGestureResult(sequence3, true);
        verify(next, times(6)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        long downTime = events.get(0).getDownTime();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN,
                hasEventTime(downTime)));
        assertThat(events, everyItem(hasDownTime(downTime)));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL)));
        assertThat(events.get(2), allOf(isAtPoint(CONTINUED_LINE_MID2), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 2)));
        assertThat(events.get(3), allOf(isAtPoint(CONTINUED_LINE_END), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 3)));
        assertThat(events.get(4), allOf(isAtPoint(CONTINUED_LINE_MID2), IS_ACTION_MOVE,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 4)));
        assertThat(events.get(5), allOf(isAtPoint(CONTINUED_LINE_MID2), IS_ACTION_UP,
                hasEventTime(downTime + CONTINUED_LINE_INTERVAL * 4)));
    }

    @Test
    public void testContinuedGesture_nonContinuingGestureArrivesDuringDispatch_gestureCanceled()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, false);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, true);
        verify(next, times(5)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(1), IS_ACTION_CANCEL);
        assertThat(events.get(2), allOf(isAtPoint(LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(3), allOf(isAtPoint(LINE_END), IS_ACTION_MOVE));
        assertThat(events.get(4), allOf(isAtPoint(LINE_END), IS_ACTION_UP));
    }

    @Test
    public void testContinuedGesture_nonContinuingGestureArrivesAfterDispatch_gestureCanceled()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, true);
        verify(next, times(6)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE));
        assertThat(events.get(2), IS_ACTION_CANCEL);
        assertThat(events.get(3), allOf(isAtPoint(LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(4), allOf(isAtPoint(LINE_END), IS_ACTION_MOVE));
        assertThat(events.get(5), allOf(isAtPoint(LINE_END), IS_ACTION_UP));
    }

    @Test
    public void testContinuedGesture_misMatchedContinuationArrives_bothGesturesCanceled()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);
        List<GestureStep> discontinuousGesture = mContinuedLineList2
                .subList(1, mContinuedLineList2.size());
        injectEventsSync(discontinuousGesture, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, false);
        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE));
        assertThat(events.get(2), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_CANCEL));
    }

    @Test
    public void testContinuedGesture_continuationArrivesFromOtherService_bothGesturesCanceled()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        IAccessibilityServiceClient otherService = mock(IAccessibilityServiceClient.class);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion events
        injectEventsSync(mContinuedLineList2, otherService, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, false);
        verify(otherService).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, false);
        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(1), IS_ACTION_CANCEL);
    }

    @Test
    public void testContinuedGesture_realGestureArrivesInBetween_getsCanceled()
            throws Exception {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        injectEventsSync(mContinuedLineList1, mServiceInterface, CONTINUED_LINE_SEQUENCE_1);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_1, true);

        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);

        injectEventsSync(mContinuedLineList2, mServiceInterface, CONTINUED_LINE_SEQUENCE_2);
        mMessageCapturingHandler.sendAllMessages(); // Send all motion events
        verify(mServiceInterface).onPerformGestureResult(CONTINUED_LINE_SEQUENCE_2, false);
        verify(next, times(4)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        List<MotionEvent> events = mCaptor1.getAllValues();
        assertThat(events.get(0), allOf(isAtPoint(CONTINUED_LINE_START), IS_ACTION_DOWN));
        assertThat(events.get(1), allOf(isAtPoint(CONTINUED_LINE_MID1), IS_ACTION_MOVE));
        assertThat(events.get(2), IS_ACTION_CANCEL);
        assertThat(events.get(3), allOf(isAtPoint(CLICK_POINT), IS_ACTION_DOWN));
    }

    @Test
    public void testClearEvents_realGestureInProgress_shouldForgetAboutGesture() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
        mMotionEventInjector.clearEvents(MOTION_EVENT_SOURCE);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(next, times(2)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsClickDown);
        assertThat(mCaptor1.getAllValues().get(1), mIsLineStart);
    }

    @Test
    public void testClearEventsOnOtherSource_realGestureInProgress_shouldNotForgetAboutGesture() {
        EventStreamTransformation next = attachMockNext(mMotionEventInjector);
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
        mMotionEventInjector.clearEvents(OTHER_EVENT_SOURCE);
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        mMessageCapturingHandler.sendOneMessage(); // Send a motion event

        verify(next, times(3)).onMotionEvent(mCaptor1.capture(), mCaptor2.capture(), anyInt());
        assertThat(mCaptor1.getAllValues().get(0), mIsClickDown);
        assertThat(mCaptor1.getAllValues().get(1), IS_ACTION_CANCEL);
        assertThat(mCaptor1.getAllValues().get(2), mIsLineStart);
    }

    @Test
    public void testOnDestroy_shouldCancelGestures() throws RemoteException {
        mMotionEventInjector.onDestroy();
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, false);
    }

    @Test
    public void testInjectEvents_withNoNext_shouldCancel() throws RemoteException {
        injectEventsSync(mLineList, mServiceInterface, LINE_SEQUENCE);
        verify(mServiceInterface).onPerformGestureResult(LINE_SEQUENCE, false);
    }

    @Test
    public void testOnMotionEvent_withNoNext_shouldNotCrash() {
        mMotionEventInjector.onMotionEvent(mClickDownEvent, mClickDownEvent, 0);
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

    private void injectEventsSync(List<GestureStep> gestureSteps,
            IAccessibilityServiceClient serviceInterface, int sequence) {
        mMotionEventInjector.injectEvents(gestureSteps, serviceInterface, sequence,
                Display.DEFAULT_DISPLAY);
        // Dispatch the message sent by the injector. Our simple handler doesn't guarantee stuff
        // happens in order.
        mMessageCapturingHandler.sendLastMessage();
    }

    private List<GestureStep> createSimpleGestureFromPoints(int strokeId, int continuedStrokeId,
            boolean continued, long interval, Point... points) {
        List<GestureStep> gesture = new ArrayList<>(points.length);
        TouchPoint[] touchPoints = new TouchPoint[1];
        touchPoints[0] = new TouchPoint();
        for (int i = 0; i < points.length; i++) {
            touchPoints[0].mX = points[i].x;
            touchPoints[0].mY = points[i].y;
            touchPoints[0].mIsStartOfPath = ((i == 0) && (continuedStrokeId <= 0));
            touchPoints[0].mContinuedStrokeId = continuedStrokeId;
            touchPoints[0].mStrokeId = strokeId;
            touchPoints[0].mIsEndOfPath = ((i == points.length - 1) && !continued);
            gesture.add(new GestureStep(interval * i, 1, touchPoints));
        }
        return gesture;
    }

    List<GestureStep> combineGestureSteps(List<GestureStep> list1, List<GestureStep> list2) {
        assertEquals(list1.size(), list2.size());
        List<GestureStep> gesture = new ArrayList<>(list1.size());
        for (int i = 0; i < list1.size(); i++) {
            int numPoints1 = list1.get(i).numTouchPoints;
            int numPoints2 = list2.get(i).numTouchPoints;
            TouchPoint[] touchPoints = new TouchPoint[numPoints1 + numPoints2];
            for (int j = 0; j < numPoints1; j++) {
                touchPoints[j] = new TouchPoint();
                touchPoints[j].copyFrom(list1.get(i).touchPoints[j]);
            }
            for (int j = 0; j < numPoints2; j++) {
                touchPoints[numPoints1 + j] = new TouchPoint();
                touchPoints[numPoints1 + j].copyFrom(list2.get(i).touchPoints[j]);
            }
            gesture.add(new GestureStep(list1.get(i).timeSinceGestureStart,
                    numPoints1 + numPoints2, touchPoints));
        }
        return gesture;
    }

    private EventStreamTransformation attachMockNext(MotionEventInjector motionEventInjector) {
        EventStreamTransformation next = mock(EventStreamTransformation.class);
        motionEventInjector.setNext(next);
        return next;
    }

    private static class MotionEventActionMatcher extends TypeSafeMatcher<MotionEvent> {
        int mAction;

        MotionEventActionMatcher(int action) {
            super();
            mAction = action;
        }

        @Override
        protected boolean matchesSafely(MotionEvent motionEvent) {
            return motionEvent.getActionMasked() == mAction;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Matching to action " + mAction);
        }
    }

    private static TypeSafeMatcher<MotionEvent> isAtPoint(final Point point) {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                return ((event.getX() == point.x) && (event.getY() == point.y));
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Is at point " + point);
            }
        };
    }

    private static TypeSafeMatcher<MotionEvent> containsPoints(final Point... points) {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
                for (int i = 0; i < points.length; i++) {
                    boolean havePoint = false;
                    for (int j = 0; j < points.length; j++) {
                        event.getPointerCoords(j, coords);
                        if ((points[i].x == coords.x) && (points[i].y == coords.y)) {
                            havePoint = true;
                        }
                    }
                    if (!havePoint) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Contains points " + Arrays.toString(points));
            }
        };
    }

    private static TypeSafeMatcher<MotionEvent> hasDownTime(final long downTime) {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                return event.getDownTime() == downTime;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Down time = " + downTime);
            }
        };
    }

    private static TypeSafeMatcher<MotionEvent> hasEventTime(final long eventTime) {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                return event.getEventTime() == eventTime;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Event time = " + eventTime);
            }
        };
    }

    private static TypeSafeMatcher<MotionEvent> hasTimeFromDown(final long timeFromDown) {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                return (event.getEventTime() - event.getDownTime()) == timeFromDown;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Time from down to event times = " + timeFromDown);
            }
        };
    }

    private static TypeSafeMatcher<MotionEvent> hasStandardInitialization() {
        return new TypeSafeMatcher<MotionEvent>() {
            @Override
            protected boolean matchesSafely(MotionEvent event) {
                return (0 == event.getActionIndex()) && (VIRTUAL_KEYBOARD == event.getDeviceId())
                        && (EDGEFLAGS == event.getEdgeFlags()) && (0 == event.getFlags())
                        && (METASTATE == event.getMetaState()) && (0F == event.getOrientation())
                        && (0F == event.getTouchMajor()) && (0F == event.getTouchMinor())
                        && (X_PRECISION == event.getXPrecision())
                        && (Y_PRECISION == event.getYPrecision())
                        && (POINTER_SIZE == event.getSize())
                        && (1 == event.getPointerCount()) && (PRESSURE == event.getPressure())
                        && (InputDevice.SOURCE_TOUCHSCREEN == event.getSource());
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Has standard values for all parameters");
            }
        };
    }
}
