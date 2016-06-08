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

package com.android.internal.util;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link com.android.internal.util.WakeupMessage}.
 */
@SmallTest
public class WakeupMessageTest {
    private static final String TEST_CMD_NAME = "TEST cmd Name";
    private static final int TEST_CMD = 18;
    private static final int TEST_ARG1 = 33;
    private static final int TEST_ARG2 = 182;
    private static final Object TEST_OBJ = "hello";

    @Mock AlarmManager mAlarmManager;
    WakeupMessage mMessage;
    // Make a spy so that we can verify calls to it
    @Spy MessageCapturingHandler mHandler = new MessageCapturingHandler();

    ArgumentCaptor<AlarmManager.OnAlarmListener> mListenerCaptor =
            ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);

    /**
     * A Handler that will capture the most recent message sent to it.
     *
     * This handler is setup on the main Looper
     */
    public static class MessageCapturingHandler extends Handler {
        private Message mLastMessage;

        public MessageCapturingHandler() {
            super(Looper.getMainLooper(), /* Nothing is actually dispatched on this Looper */
                    null, false);
        }

        @Override
        public void handleMessage(Message m) {
            // need to copy since it will be recycled after this method returns
            mLastMessage = Message.obtain(m);
        }

        public Message getLastMessage() {
            return mLastMessage;
        }
    }

    /**
     * Sets up the test.
     */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = mock(Context.class);
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(mAlarmManager);
        // capture the listener for each AlarmManager.setExact call
        doNothing().when(mAlarmManager).setExact(anyInt(), anyLong(), any(String.class),
                mListenerCaptor.capture(), any(Handler.class));

        mMessage = new WakeupMessage(context, mHandler, TEST_CMD_NAME, TEST_CMD, TEST_ARG1,
                TEST_ARG2, TEST_OBJ);
    }

    /**
     * Ensure the test is cleaned up and ready for the next test.
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private void scheduleAndVerifyAlarm(long when) {
        mMessage.schedule(when);
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP), eq(when),
                eq(TEST_CMD_NAME), any(AlarmManager.OnAlarmListener.class), eq(mHandler));
    }

    private void verifyMessageDispatchedOnce() {
        verify(mHandler, times(1)).handleMessage(any(Message.class));
        assertEquals("what", TEST_CMD, mHandler.getLastMessage().what);
        assertEquals("arg1", TEST_ARG1, mHandler.getLastMessage().arg1);
        assertEquals("arg2", TEST_ARG2, mHandler.getLastMessage().arg2);
        assertEquals("obj", TEST_OBJ, mHandler.getLastMessage().obj);
    }

    /**
     * Schedule and deliver a single message
     */
    @Test
    public void scheduleAndDeliverMessage() {
        final long when = 1001;
        scheduleAndVerifyAlarm(when);
        verify(mHandler, never()).handleMessage(any(Message.class));
        mListenerCaptor.getValue().onAlarm();
        verifyMessageDispatchedOnce();
    }

    /**
     * Check that the message is not delivered if cancel is called it after its alarm fires but
     * before onAlarm is called.
     *
     * This ensures that if cancel is called on the handler thread, any previously-scheduled message
     * is guaranteed not to be delivered.
     */
    @Test
    public void scheduleAndCancelMessage() {
        final long when = 1010;
        scheduleAndVerifyAlarm(when);
        mMessage.cancel();
        mListenerCaptor.getValue().onAlarm();
        verify(mHandler, never()).handleMessage(any(Message.class));
    }

    /**
     * Verify nothing happens when cancel is called without a schedule
     */
    @Test
    public void cancelWithoutSchedule() {
        mMessage.cancel();
    }

    /**
     * Verify that the message is silently rescheduled if schedule is called twice without the
     * message being dispatched first.
     */
    @Test
    public void scheduleTwiceWithoutMessageDispatched() {
        final long when1 = 1011;
        final long when2 = 1012;
        scheduleAndVerifyAlarm(when1);
        scheduleAndVerifyAlarm(when2);
        mListenerCaptor.getValue().onAlarm();
        verifyMessageDispatchedOnce();
    }

}
