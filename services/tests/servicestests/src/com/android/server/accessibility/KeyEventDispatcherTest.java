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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.content.Context;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.view.Display;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.accessibility.KeyEventDispatcher.KeyEventFilter;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.policy.WindowManagerPolicy;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for KeyEventDispatcher
 */
@RunWith(AndroidJUnit4.class)
public class KeyEventDispatcherTest {
    private static final int SEND_FRAMEWORK_KEY_EVENT = 4;

    private final KeyEvent mKeyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, 0x40);
    private final KeyEvent mOtherKeyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, 0x50);
    private final Object mLock = new Object();
    private MessageCapturingHandler mInputEventsHandler;
    private KeyEventDispatcher mKeyEventDispatcher;
    private KeyEventFilter mKeyEventFilter1;
    private KeyEventFilter mKeyEventFilter2;
    private IPowerManager mMockPowerManagerService;
    private IThermalService mMockThermalService;
    private MessageCapturingHandler mMessageCapturingHandler;
    private ArgumentCaptor<Integer> mFilter1SequenceCaptor = ArgumentCaptor.forClass(Integer.class);
    private ArgumentCaptor<Integer> mFilter2SequenceCaptor = ArgumentCaptor.forClass(Integer.class);

    @Before
    public void setUp() {
        Looper looper = InstrumentationRegistry.getContext().getMainLooper();
        mInputEventsHandler = new MessageCapturingHandler(looper, null);
        mMockPowerManagerService = mock(IPowerManager.class);
        mMockThermalService = mock(IThermalService.class);
        // TODO: It would be better to mock PowerManager rather than its binder, but the class is
        // final.
        PowerManager powerManager =
                new PowerManager(mock(Context.class), mMockPowerManagerService, mMockThermalService,
                        new Handler(looper));
        mMessageCapturingHandler = new MessageCapturingHandler(looper, null);
        mKeyEventDispatcher = new KeyEventDispatcher(mInputEventsHandler, SEND_FRAMEWORK_KEY_EVENT,
                mLock, powerManager, mMessageCapturingHandler);

        mKeyEventFilter1 = mock(KeyEventFilter.class);
        when(mKeyEventFilter1.onKeyEvent((KeyEvent) anyObject(),
                mFilter1SequenceCaptor.capture().intValue()))
                .thenReturn(true);

        mKeyEventFilter2 = mock(KeyEventFilter.class);
        when(mKeyEventFilter2.onKeyEvent((KeyEvent) anyObject(),
                mFilter2SequenceCaptor.capture().intValue()))
                .thenReturn(true);
    }

    @After
    public void tearDown() {
        mInputEventsHandler.removeAllMessages();
        mMessageCapturingHandler.removeAllMessages();
    }


    @Test
    public void testNotifyKeyEvent_withNoBoundServices_shouldReturnFalse() {
        assertFalse(mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Collections.EMPTY_LIST));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testNotifyKeyEvent_boundServiceDoesntProcessEvents_shouldReturnFalse() {
        KeyEventFilter keyEventFilter = mock(KeyEventFilter.class);
        when(keyEventFilter.onKeyEvent((KeyEvent) anyObject(), anyInt())).thenReturn(false);
        assertFalse(mKeyEventDispatcher
                .notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(keyEventFilter)));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testNotifyKeyEvent_withOneValidService_shouldNotifyService()
            throws RemoteException {
        assertTrue(mKeyEventDispatcher
                .notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1)));
        verify(mKeyEventFilter1, times(1)).onKeyEvent(argThat(new KeyEventMatcher(mKeyEvent)),
                anyInt());
    }

    @Test
    public void testNotifyKeyEvent_withTwoValidService_shouldNotifyBoth() throws RemoteException {
        assertTrue(mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2)));
        verify(mKeyEventFilter1, times(1)).onKeyEvent(argThat(new KeyEventMatcher(mKeyEvent)),
                anyInt());
        verify(mKeyEventFilter2, times(1)).onKeyEvent(argThat(new KeyEventMatcher(mKeyEvent)),
                anyInt());
    }

    /*
     * Results from services
     */
    @Test
    public void testSetOnKeyResult_eventNotHandled_shouldPassEventToFramework() {
        mKeyEventDispatcher
                .notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testSetOnKeyResult_eventHandled_shouldNotPassEventToFramework()
            throws RemoteException {
        mKeyEventDispatcher
                .notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testSetOnKeyResult_twoServicesReturnsFalse_shouldPassEventToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getValue());

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testSetOnKeyResult_twoServicesReturnsTrue_shouldNotPassEventToFramework()
            throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, true,
                mFilter2SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testSetOnKeyResult_firstOfTwoServicesReturnsTrue_shouldNotPassEventToFramework()
            throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    @Test
    public void testSetOnKeyResult_secondOfTwoServicesReturnsTrue_shouldNotPassEventToFramework()
            throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, true,
                mFilter2SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }

    // Each event should have its result set only once, but if it's set twice, we should ignore
    // the second time.
    @Test
    public void testSetOnKeyResult_firstServiceReturnsTwice_secondShouldBeIgnored() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        // Verify event is sent properly when other service responds
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getValue());
        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
        assertFalse(isTimeoutPending(mMessageCapturingHandler));
    }


    /*
     * Timeouts
     */
    @Test
    public void testEventTimesOut_shouldPassToFramework() throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
    }

    @Test
    public void testEventTimesOut_afterOneServiceReturnsFalse_shouldPassToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());

        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
    }

    @Test
    public void testEventTimesOut_afterOneServiceReturnsTrue_shouldNotPassToFramework()
            throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getValue());

        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
    }

    @Test
    public void testEventTimesOut_thenServiceReturnsFalse_shouldPassToFrameworkOnce() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));
        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mInputEventsHandler.removeMessages(SEND_FRAMEWORK_KEY_EVENT);
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
    }

    @Test
    public void testEventTimesOut_afterServiceReturnsFalse_shouldPassToFrameworkOnce() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getValue());
        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mInputEventsHandler.removeMessages(SEND_FRAMEWORK_KEY_EVENT);
        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verifyZeroInteractions(mMockPowerManagerService);
    }

    @Test
    public void testEventTimesOut_afterServiceReturnsTrue_shouldNotPassToFramework()
            throws RemoteException {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getValue());
        assertEquals(1, mMessageCapturingHandler.timedMessages.size());
        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
        verify(mMockPowerManagerService, times(1)).userActivity(eq(Display.DEFAULT_DISPLAY),
                anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY), eq(0));
    }

    /*
     * Flush services
     */
    @Test
    public void testFlushService_withPendingEvent_shouldPassToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.flush(mKeyEventFilter1);

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
    }

    @Test
    public void testFlushTwpServices_withPendingEvent_shouldPassToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.flush(mKeyEventFilter1);
        mKeyEventDispatcher.flush(mKeyEventFilter2);

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
    }

    @Test
    public void testFlushOneService_thenOtherReturnsTrue_shouldNotPassToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.flush(mKeyEventFilter1);
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, true,
                mFilter2SequenceCaptor.getValue());

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
    }

    @Test
    public void testFlushOneService_thenOtherReturnsFalse_shouldPassToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));

        mKeyEventDispatcher.flush(mKeyEventFilter1);
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getValue());

        assertTrue(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
    }

    @Test
    public void testFlushServiceWithNoEvents_shouldNotCrash() {
        mKeyEventDispatcher.flush(mKeyEventFilter1);
    }

    /*
     * Multiple Events
     */
    @Test
    public void twoEvents_serviceReturnsFalse_sentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mInputEventsHandler.removeMessages(SEND_FRAMEWORK_KEY_EVENT);
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertTwoKeyEventsSentToFrameworkInOrder(mKeyEvent, mOtherKeyEvent);
    }

    @Test
    public void twoEvents_serviceReturnsTrue_notSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));
    }

    @Test
    public void twoEvents_serviceHandlesFirst_otherSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertOneKeyEventSentToFramework(mOtherKeyEvent);
    }

    @Test
    public void twoEvents_serviceHandlesSecond_otherSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertOneKeyEventSentToFramework(mKeyEvent);
    }

    @Test
    public void twoEvents_firstTimesOutThenServiceHandlesBoth_firstSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.handleMessage(getTimedMessage(mMessageCapturingHandler, 0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, true,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertOneKeyEventSentToFramework(mKeyEvent);
    }

    @Test
    public void addServiceBetweenTwoEvents_neitherEventHandled_bothSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher.notifyKeyEventLocked(
                mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getValue());
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(1));

        assertTwoKeyEventsSentToFrameworkInOrder(mKeyEvent, mOtherKeyEvent);
    }

    @Test
    public void removeServiceBetweenTwoEvents_neitherEventHandled_bothSentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(
                mKeyEvent, 0, Arrays.asList(mKeyEventFilter1, mKeyEventFilter2));
        mKeyEventDispatcher.flush(mKeyEventFilter1);
        mKeyEventDispatcher.notifyKeyEventLocked(
                mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter2));
        assertFalse(mInputEventsHandler.hasMessages(SEND_FRAMEWORK_KEY_EVENT));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getAllValues().get(0));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter2, false,
                mFilter2SequenceCaptor.getAllValues().get(1));

        assertTwoKeyEventsSentToFrameworkInOrder(mKeyEvent, mOtherKeyEvent);
    }

    /*
     * Misc
     */
    @Test
    public void twoEvents_serviceReturnsFalseOutOfOrder_sentToFramework() {
        mKeyEventDispatcher.notifyKeyEventLocked(mKeyEvent, 0, Arrays.asList(mKeyEventFilter1));
        mKeyEventDispatcher
                .notifyKeyEventLocked(mOtherKeyEvent, 0, Arrays.asList(mKeyEventFilter1));

        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(1));
        mKeyEventDispatcher.setOnKeyEventResult(mKeyEventFilter1, false,
                mFilter1SequenceCaptor.getAllValues().get(0));

        // Order doesn't really matter since this is really checking that we don't do something
        // really bad, but we'll send them in the order they are reported
        assertTwoKeyEventsSentToFrameworkInOrder(mOtherKeyEvent, mKeyEvent);
    }

    private void assertOneKeyEventSentToFramework(KeyEvent event) {
        assertEquals(1, mInputEventsHandler.timedMessages.size());

        Message m = getTimedMessage(mInputEventsHandler, 0);
        assertEquals(SEND_FRAMEWORK_KEY_EVENT, m.what);
        assertEquals(WindowManagerPolicy.FLAG_PASS_TO_USER, m.arg1);
        assertTrue(new KeyEventMatcher(event).matches(m.obj));
    }

    private void assertTwoKeyEventsSentToFrameworkInOrder(KeyEvent first, KeyEvent second) {
        assertEquals(2, mInputEventsHandler.timedMessages.size());

        Message m0 = getTimedMessage(mInputEventsHandler, 0);
        assertEquals(SEND_FRAMEWORK_KEY_EVENT, m0.what);
        assertEquals(WindowManagerPolicy.FLAG_PASS_TO_USER, m0.arg1);
        assertTrue(new KeyEventMatcher(first).matches(m0.obj));

        Message m1 = getTimedMessage(mInputEventsHandler, 1);
        assertEquals(SEND_FRAMEWORK_KEY_EVENT, m1.what);
        assertEquals(WindowManagerPolicy.FLAG_PASS_TO_USER, m1.arg1);
        assertTrue(new KeyEventMatcher(second).matches(m1.obj));
    }

    private static Message getTimedMessage(MessageCapturingHandler handler, int i) {
        return handler.timedMessages.get(i).first;
    }

    private static boolean isTimeoutPending(MessageCapturingHandler handler) {
        return handler.hasMessages(KeyEventDispatcher.MSG_ON_KEY_EVENT_TIMEOUT);
    }

    private class KeyEventMatcher extends TypeSafeMatcher<KeyEvent> {
        private KeyEvent mEventToMatch;

        KeyEventMatcher(KeyEvent eventToMatch) {
            mEventToMatch = eventToMatch;
        }

        @Override
        public boolean matchesSafely(KeyEvent keyEvent) {
            return (mEventToMatch.getAction() == keyEvent.getAction())
                    && (mEventToMatch.getKeyCode() == keyEvent.getKeyCode());
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Key event matcher");
        }
    }
}
