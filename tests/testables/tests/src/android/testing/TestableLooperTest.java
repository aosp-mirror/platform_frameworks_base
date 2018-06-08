/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestableLooper.MessageHandler;
import android.testing.TestableLooper.RunWithLooper;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class TestableLooperTest {

    private TestableLooper mTestableLooper;

    @Before
    public void setup() throws Exception {
        mTestableLooper = TestableLooper.get(this);
    }

    @Test
    public void testMessageExecuted() throws Exception {
        Handler h = new Handler();
        Runnable r = mock(Runnable.class);
        h.post(r);
        verify(r, never()).run();
        mTestableLooper.processAllMessages();
        verify(r).run();
    }

    @Test
    public void testMessageCallback() throws Exception {
        Handler h = new Handler();
        Message m = h.obtainMessage(3);
        Runnable r = mock(Runnable.class);
        MessageHandler messageHandler = mock(MessageHandler.class);
        when(messageHandler.onMessageHandled(any())).thenReturn(false);
        mTestableLooper.setMessageHandler(messageHandler);

        m.sendToTarget();
        h.post(r);

        mTestableLooper.processAllMessages();

        verify(messageHandler).onMessageHandled(eq(m));
        // This should never be run becaus the mock returns false on the first message, and
        // the second will get skipped.
        verify(r, never()).run();
    }

    @Test
    public void testProcessNumberOfMessages() throws Exception {
        Handler h = new Handler();
        Runnable r = mock(Runnable.class);
        h.post(r);
        h.post(r);
        h.post(r);

        mTestableLooper.processMessages(2);

        verify(r, times(2)).run();
    }

    @Test
    public void testProcessAllMessages() throws Exception {
        Handler h = new Handler();
        Runnable r = mock(Runnable.class);
        Runnable poster = () -> h.post(r);
        h.post(poster);

        mTestableLooper.processAllMessages();
        verify(r).run();
    }

    @Test
    public void test3Chain() throws Exception {
        Handler h = new Handler();
        Runnable r = mock(Runnable.class);
        Runnable poster = () -> h.post(r);
        Runnable poster2 = () -> h.post(poster);
        h.post(poster2);

        mTestableLooper.processAllMessages();
        verify(r).run();
    }

    @Test
    public void testProcessAllMessages_2Messages() throws Exception {
        Handler h = new Handler();
        Runnable r = mock(Runnable.class);
        Runnable r2 = mock(Runnable.class);
        h.post(r);
        h.post(r2);

        mTestableLooper.processAllMessages();
        verify(r).run();
        verify(r2).run();
    }

    @Test
    public void testMainLooper() throws Exception {
        assertNotEquals(Looper.myLooper(), Looper.getMainLooper());
        Runnable r = mock(Runnable.class);
        Runnable r2 = mock(Runnable.class);
        TestableLooper testableLooper = new TestableLooper(Looper.getMainLooper());

        try {
            testableLooper.setMessageHandler(m -> {
                if (m.getCallback() == r) return true;
                return false;
            });
            new Handler(Looper.getMainLooper()).post(r);
            testableLooper.processAllMessages();

            verify(r).run();
            verify(r2, never()).run();
        } finally {
            testableLooper.destroy();
        }
    }

    @Test
    public void testNonMainLooperAnnotation() {
        assertNotEquals(Looper.myLooper(), Looper.getMainLooper());
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testMainLooperAnnotation() {
        assertEquals(Looper.myLooper(), Looper.getMainLooper());
    }

    @Test
    public void testCorrectLooperExecution() throws Exception {
        boolean[] hasRun = new boolean[] { false };
        Runnable r = () -> {
            assertEquals("Should run on main looper", Looper.getMainLooper(), Looper.myLooper());
            hasRun[0] = true;
        };
        TestableLooper testableLooper = new TestableLooper(Looper.getMainLooper());
        try {
            new Handler(Looper.getMainLooper()).post(r);
            testableLooper.processAllMessages();

            assertTrue(hasRun[0]);
        } finally {
            testableLooper.destroy();
        }
    }
}
