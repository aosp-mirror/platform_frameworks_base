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

package com.android.systemui.util.concurrency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MessageRouterImplTest extends SysuiTestCase {
    private static final int MESSAGE_A = 0;
    private static final int MESSAGE_B = 1;
    private static final int MESSAGE_C = 2;

    private static final String METADATA_A = "A";
    private static final String METADATA_B = "B";
    private static final String METADATA_C = "C";
    private static final Foobar METADATA_FOO = new Foobar();

    FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    @Mock
    MessageRouter.SimpleMessageListener mNoMdListener;
    @Mock
    MessageRouter.DataMessageListener<String> mStringListener;
    @Mock
    MessageRouter.DataMessageListener<Foobar> mFoobarListener;
    private MessageRouterImpl mMR;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMR = new MessageRouterImpl(mFakeExecutor);
    }

    @Test
    public void testSingleMessage_NoMetaData() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);

        mMR.sendMessage(MESSAGE_A);
        verify(mNoMdListener, never()).onMessage(anyInt());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener).onMessage(MESSAGE_A);
    }

    @Test
    public void testSingleMessage_WithMetaData() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessage(METADATA_A);
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mStringListener).onMessage(METADATA_A);
    }

    @Test
    public void testMessages_WithMixedMetaData() {
        mMR.subscribeTo(String.class, mStringListener);
        mMR.subscribeTo(Foobar.class, mFoobarListener);

        mMR.sendMessage(METADATA_A);
        verify(mStringListener, never()).onMessage(anyString());
        verify(mFoobarListener, never()).onMessage(any(Foobar.class));

        mFakeExecutor.runAllReady();
        verify(mStringListener).onMessage(METADATA_A);
        verify(mFoobarListener, never()).onMessage(any(Foobar.class));

        reset(mStringListener);
        reset(mFoobarListener);

        mMR.sendMessage(METADATA_FOO);
        verify(mStringListener, never()).onMessage(anyString());
        verify(mFoobarListener, never()).onMessage(any(Foobar.class));

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(anyString());
        verify(mFoobarListener).onMessage(METADATA_FOO);
    }

    @Test
    public void testMessages_WithAndWithoutMetaData() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessage(MESSAGE_A);
        verify(mNoMdListener, never()).onMessage(anyInt());
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener).onMessage(MESSAGE_A);
        verify(mStringListener, never()).onMessage(anyString());

        reset(mNoMdListener);
        reset(mStringListener);

        mMR.sendMessage(METADATA_A);
        verify(mNoMdListener, never()).onMessage(anyInt());
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(anyInt());
        verify(mStringListener).onMessage(METADATA_A);
    }

    @Test
    public void testRepeatedMessage() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_B);
        verify(mStringListener, never()).onMessage(anyString());

        InOrder ordered = inOrder(mStringListener);
        mFakeExecutor.runNextReady();
        ordered.verify(mStringListener).onMessage(METADATA_A);
        mFakeExecutor.runNextReady();
        ordered.verify(mStringListener).onMessage(METADATA_A);
        mFakeExecutor.runNextReady();
        ordered.verify(mStringListener).onMessage(METADATA_B);
    }

    @Test
    public void testCancelMessage() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);
        mMR.subscribeTo(MESSAGE_B, mNoMdListener);
        mMR.subscribeTo(MESSAGE_C, mNoMdListener);

        mMR.sendMessage(MESSAGE_A);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_C);
        verify(mNoMdListener, never()).onMessage(anyInt());

        mMR.cancelMessages(MESSAGE_B);

        mFakeExecutor.runAllReady();

        InOrder ordered = inOrder(mNoMdListener);
        ordered.verify(mNoMdListener).onMessage(MESSAGE_A);
        ordered.verify(mNoMdListener).onMessage(MESSAGE_C);
    }

    @Test
    public void testSendMessage_NoSubscriber() {
        mMR.sendMessage(MESSAGE_A);
        verify(mNoMdListener, never()).onMessage(anyInt());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(anyInt());

        mMR.subscribeTo(MESSAGE_A, mNoMdListener);

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(anyInt());

        mMR.sendMessage(MESSAGE_A);
        verify(mNoMdListener, never()).onMessage(anyInt());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener).onMessage(MESSAGE_A);
    }

    @Test
    public void testUnsubscribe_SpecificMessage_NoMetadata() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);
        mMR.subscribeTo(MESSAGE_B, mNoMdListener);
        mMR.sendMessage(MESSAGE_A);
        mMR.sendMessage(MESSAGE_B);

        mFakeExecutor.runAllReady();
        InOrder ordered = inOrder(mNoMdListener);
        ordered.verify(mNoMdListener).onMessage(MESSAGE_A);
        ordered.verify(mNoMdListener).onMessage(MESSAGE_B);

        reset(mNoMdListener);
        mMR.unsubscribeFrom(MESSAGE_A, mNoMdListener);
        mMR.sendMessage(MESSAGE_A);
        mMR.sendMessage(MESSAGE_B);

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(MESSAGE_A);
        verify(mNoMdListener).onMessage(MESSAGE_B);
    }

    @Test
    public void testUnsubscribe_SpecificMessage_WithMetadata() {
        mMR.subscribeTo(String.class, mStringListener);
        mMR.subscribeTo(Foobar.class, mFoobarListener);
        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_FOO);

        mFakeExecutor.runNextReady();
        verify(mStringListener).onMessage(METADATA_A);
        mFakeExecutor.runNextReady();
        verify(mFoobarListener).onMessage(METADATA_FOO);

        reset(mStringListener);
        reset(mFoobarListener);
        mMR.unsubscribeFrom(String.class, mStringListener);
        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_FOO);

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(METADATA_A);
        verify(mFoobarListener).onMessage(METADATA_FOO);
    }

    @Test
    public void testUnsubscribe_AllMessages_NoMetadata() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);
        mMR.subscribeTo(MESSAGE_B, mNoMdListener);
        mMR.subscribeTo(MESSAGE_C, mNoMdListener);

        mMR.sendMessage(MESSAGE_A);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_C);

        mFakeExecutor.runAllReady();
        verify(mNoMdListener).onMessage(MESSAGE_A);
        verify(mNoMdListener).onMessage(MESSAGE_B);
        verify(mNoMdListener).onMessage(MESSAGE_C);

        reset(mNoMdListener);

        mMR.unsubscribeFrom(mNoMdListener);
        mMR.sendMessage(MESSAGE_A);
        mMR.sendMessage(MESSAGE_B);
        mMR.sendMessage(MESSAGE_C);

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(MESSAGE_A);
        verify(mNoMdListener, never()).onMessage(MESSAGE_B);
        verify(mNoMdListener, never()).onMessage(MESSAGE_C);
    }

    @Test
    public void testUnsubscribe_AllMessages_WithMetadata() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_B);
        mMR.sendMessage(METADATA_C);

        mFakeExecutor.runAllReady();
        verify(mStringListener).onMessage(METADATA_A);
        verify(mStringListener).onMessage(METADATA_B);
        verify(mStringListener).onMessage(METADATA_C);

        reset(mStringListener);

        mMR.unsubscribeFrom(mStringListener);
        mMR.sendMessage(METADATA_A);
        mMR.sendMessage(METADATA_B);
        mMR.sendMessage(METADATA_C);

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(METADATA_A);
        verify(mStringListener, never()).onMessage(METADATA_B);
        verify(mStringListener, never()).onMessage(METADATA_C);
    }

    @Test
    public void testSingleDelayedMessage_NoMetaData() {
        mMR.subscribeTo(MESSAGE_A, mNoMdListener);

        mMR.sendMessageDelayed(MESSAGE_A, 100);
        verify(mNoMdListener, never()).onMessage(anyInt());

        mFakeExecutor.runAllReady();
        verify(mNoMdListener, never()).onMessage(anyInt());

        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runAllReady();
        verify(mNoMdListener).onMessage(MESSAGE_A);
    }

    @Test
    public void testSingleDelayedMessage_WithMetaData() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessageDelayed(METADATA_C, 1000);
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runAllReady();
        verify(mStringListener).onMessage(METADATA_C);
    }

    @Test
    public void testMultipleDelayedMessages() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessageDelayed(METADATA_A, 100);
        mMR.sendMessageDelayed(METADATA_B, 1000);
        mMR.sendMessageDelayed(METADATA_C, 500);
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.advanceClockToLast();
        mFakeExecutor.runAllReady();

        InOrder ordered = inOrder(mStringListener);
        ordered.verify(mStringListener).onMessage(METADATA_A);
        ordered.verify(mStringListener).onMessage(METADATA_C);
        ordered.verify(mStringListener).onMessage(METADATA_B);
    }

    @Test
    public void testCancelDelayedMessages() {
        mMR.subscribeTo(String.class, mStringListener);

        mMR.sendMessageDelayed(METADATA_A, 100);
        mMR.sendMessageDelayed(METADATA_B, 1000);
        mMR.sendMessageDelayed(METADATA_C, 500);
        verify(mStringListener, never()).onMessage(anyString());

        mFakeExecutor.runAllReady();
        verify(mStringListener, never()).onMessage(anyString());

        mMR.cancelMessages(String.class);
        mFakeExecutor.advanceClockToLast();
        mFakeExecutor.runAllReady();

        verify(mStringListener, never()).onMessage(anyString());
    }

    private static class Foobar {}
}
