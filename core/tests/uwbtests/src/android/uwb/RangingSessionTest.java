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

package android.uwb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.PersistableBundle;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.Executor;

/**
 * Test of {@link RangingSession}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingSessionTest {
    private static final Executor EXECUTOR = UwbTestUtils.getExecutor();
    private static final PersistableBundle PARAMS = new PersistableBundle();
    private static final @RangingSession.Callback.Reason int REASON =
            RangingSession.Callback.REASON_GENERIC_ERROR;

    @Test
    public void testOnRangingOpened_OnOpenSuccessCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        verifyOpenState(session, false);

        session.onRangingOpened();
        verifyOpenState(session, true);

        // Verify that the onOpenSuccess callback was invoked
        verify(callback, times(1)).onOpened(eq(session));
        verify(callback, times(0)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingOpened_CannotOpenClosedSession() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);

        session.onRangingOpened();
        verifyOpenState(session, true);
        verify(callback, times(1)).onOpened(eq(session));
        verify(callback, times(0)).onClosed(anyInt(), any());

        session.onRangingClosed(REASON, PARAMS);
        verifyOpenState(session, false);
        verify(callback, times(1)).onOpened(eq(session));
        verify(callback, times(1)).onClosed(anyInt(), any());

        // Now invoke the ranging started callback and ensure the session remains closed
        session.onRangingOpened();
        verifyOpenState(session, false);
        verify(callback, times(1)).onOpened(eq(session));
        verify(callback, times(1)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingClosed_OnClosedCalledWhenSessionNotOpen() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        verifyOpenState(session, false);

        session.onRangingClosed(REASON, PARAMS);
        verifyOpenState(session, false);

        // Verify that the onOpenSuccess callback was invoked
        verify(callback, times(0)).onOpened(eq(session));
        verify(callback, times(1)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingClosed_OnClosedCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        session.onRangingStarted(PARAMS);
        session.onRangingClosed(REASON, PARAMS);
        verify(callback, times(1)).onClosed(anyInt(), any());

        verifyOpenState(session, false);
        session.onRangingClosed(REASON, PARAMS);
        verify(callback, times(2)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingResult_OnReportReceivedCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        verifyOpenState(session, false);

        session.onRangingStarted(PARAMS);
        verifyOpenState(session, true);

        RangingReport report = UwbTestUtils.getRangingReports(1);
        session.onRangingResult(report);
        verify(callback, times(1)).onReportReceived(eq(report));
    }

    @Test
    public void testStart_CannotStartIfAlreadyStarted() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        doAnswer(new StartAnswer(session)).when(adapter).startRanging(any(), any());
        session.onRangingOpened();

        session.start(PARAMS);
        verify(callback, times(1)).onStarted(any());

        // Calling start again should throw an illegal state
        verifyThrowIllegalState(() -> session.start(PARAMS));
        verify(callback, times(1)).onStarted(any());
    }

    @Test
    public void testStop_CannotStopIfAlreadyStopped() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        doAnswer(new StartAnswer(session)).when(adapter).startRanging(any(), any());
        doAnswer(new StopAnswer(session)).when(adapter).stopRanging(any());
        session.onRangingOpened();
        session.start(PARAMS);

        verifyNoThrowIllegalState(session::stop);
        verify(callback, times(1)).onStopped();

        // Calling stop again should throw an illegal state
        verifyThrowIllegalState(session::stop);
        verify(callback, times(1)).onStopped();
    }

    @Test
    public void testReconfigure_OnlyWhenOpened() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        doAnswer(new StartAnswer(session)).when(adapter).startRanging(any(), any());
        doAnswer(new ReconfigureAnswer(session)).when(adapter).reconfigureRanging(any(), any());

        verifyThrowIllegalState(() -> session.reconfigure(PARAMS));
        verify(callback, times(0)).onReconfigured(any());
        verifyOpenState(session, false);

        session.onRangingOpened();
        verifyNoThrowIllegalState(() -> session.reconfigure(PARAMS));
        verify(callback, times(1)).onReconfigured(any());
        verifyOpenState(session, true);

        session.onRangingStarted(PARAMS);
        verifyNoThrowIllegalState(() -> session.reconfigure(PARAMS));
        verify(callback, times(2)).onReconfigured(any());
        verifyOpenState(session, true);

        session.onRangingStopped();
        verifyNoThrowIllegalState(() -> session.reconfigure(PARAMS));
        verify(callback, times(3)).onReconfigured(any());
        verifyOpenState(session, true);


        session.onRangingClosed(REASON, PARAMS);
        verifyThrowIllegalState(() -> session.reconfigure(PARAMS));
        verify(callback, times(3)).onReconfigured(any());
        verifyOpenState(session, false);
    }

    @Test
    public void testClose_NoCallbackUntilInvoked() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        session.onRangingOpened();

        // Calling close multiple times should invoke closeRanging until the session receives
        // the onClosed callback.
        int totalCallsBeforeOnRangingClosed = 3;
        for (int i = 1; i <= totalCallsBeforeOnRangingClosed; i++) {
            session.close();
            verifyOpenState(session, true);
            verify(adapter, times(i)).closeRanging(handle);
            verify(callback, times(0)).onClosed(anyInt(), any());
        }

        // After onClosed is invoked, then the adapter should no longer be called for each call to
        // the session's close.
        final int totalCallsAfterOnRangingClosed = 2;
        for (int i = 1; i <= totalCallsAfterOnRangingClosed; i++) {
            session.onRangingClosed(REASON, PARAMS);
            verifyOpenState(session, false);
            verify(adapter, times(totalCallsBeforeOnRangingClosed)).closeRanging(handle);
            verify(callback, times(i)).onClosed(anyInt(), any());
        }
    }

    @Test
    public void testClose_OnClosedCalled() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        doAnswer(new CloseAnswer(session)).when(adapter).closeRanging(any());
        session.onRangingOpened();

        session.close();
        verify(callback, times(1)).onClosed(anyInt(), any());
    }

    @Test
    public void testClose_CannotInteractFurther() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);
        doAnswer(new CloseAnswer(session)).when(adapter).closeRanging(any());
        session.close();

        verifyThrowIllegalState(() -> session.start(PARAMS));
        verifyThrowIllegalState(() -> session.reconfigure(PARAMS));
        verifyThrowIllegalState(() -> session.stop());
        verifyNoThrowIllegalState(() -> session.close());
    }

    @Test
    public void testOnRangingResult_OnReportReceivedCalledWhenOpen() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);

        assertFalse(session.isOpen());
        session.onRangingStarted(PARAMS);
        assertTrue(session.isOpen());

        // Verify that the onReportReceived callback was invoked
        RangingReport report = UwbTestUtils.getRangingReports(1);
        session.onRangingResult(report);
        verify(callback, times(1)).onReportReceived(report);
    }

    @Test
    public void testOnRangingResult_OnReportReceivedNotCalledWhenNotOpen() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, adapter, handle);

        assertFalse(session.isOpen());

        // Verify that the onReportReceived callback was invoked
        RangingReport report = UwbTestUtils.getRangingReports(1);
        session.onRangingResult(report);
        verify(callback, times(0)).onReportReceived(report);
    }

    private void verifyOpenState(RangingSession session, boolean expected) {
        assertEquals(expected, session.isOpen());
    }

    private void verifyThrowIllegalState(Runnable runnable) {
        try {
            runnable.run();
            fail();
        } catch (IllegalStateException e) {
            // Pass
        }
    }

    private void verifyNoThrowIllegalState(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalStateException e) {
            fail();
        }
    }

    abstract class AdapterAnswer implements Answer {
        protected RangingSession mSession;

        protected AdapterAnswer(RangingSession session) {
            mSession = session;
        }
    }

    class StartAnswer extends AdapterAnswer {
        StartAnswer(RangingSession session) {
            super(session);
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            mSession.onRangingStarted(PARAMS);
            return null;
        }
    }

    class ReconfigureAnswer extends AdapterAnswer {
        ReconfigureAnswer(RangingSession session) {
            super(session);
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            mSession.onRangingReconfigured(PARAMS);
            return null;
        }
    }

    class StopAnswer extends AdapterAnswer {
        StopAnswer(RangingSession session) {
            super(session);
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            mSession.onRangingStopped();
            return null;
        }
    }

    class CloseAnswer extends AdapterAnswer {
        CloseAnswer(RangingSession session) {
            super(session);
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            mSession.onRangingClosed(REASON, PARAMS);
            return null;
        }
    }
}
