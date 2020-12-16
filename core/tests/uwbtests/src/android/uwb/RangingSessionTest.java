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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.PersistableBundle;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;

/**
 * Test of {@link RangingSession}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingSessionTest {
    private static final IUwbAdapter ADAPTER = mock(IUwbAdapter.class);
    private static final Executor EXECUTOR = UwbTestUtils.getExecutor();
    private static final PersistableBundle PARAMS = new PersistableBundle();
    private static final @RangingSession.Callback.CloseReason int CLOSE_REASON =
            RangingSession.Callback.CLOSE_REASON_LOCAL_GENERIC_ERROR;

    @Test
    public void testOnRangingStarted_OnOpenSuccessCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);
        verifyOpenState(session, false);

        session.onRangingStarted(PARAMS);
        verifyOpenState(session, true);

        // Verify that the onOpenSuccess callback was invoked
        verify(callback, times(1)).onOpenSuccess(eq(session), any());
        verify(callback, times(0)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingStarted_CannotOpenClosedSession() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);

        session.onRangingStarted(PARAMS);
        verifyOpenState(session, true);
        verify(callback, times(1)).onOpenSuccess(eq(session), any());
        verify(callback, times(0)).onClosed(anyInt(), any());

        session.onRangingClosed(CLOSE_REASON, PARAMS);
        verifyOpenState(session, false);
        verify(callback, times(1)).onOpenSuccess(eq(session), any());
        verify(callback, times(1)).onClosed(anyInt(), any());

        // Now invoke the ranging started callback and ensure the session remains closed
        session.onRangingStarted(PARAMS);
        verifyOpenState(session, false);
        verify(callback, times(1)).onOpenSuccess(eq(session), any());
        verify(callback, times(1)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingClosed_OnClosedCalledWhenSessionNotOpen() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);
        verifyOpenState(session, false);

        session.onRangingClosed(CLOSE_REASON, PARAMS);
        verifyOpenState(session, false);

        // Verify that the onOpenSuccess callback was invoked
        verify(callback, times(0)).onOpenSuccess(eq(session), any());
        verify(callback, times(1)).onClosed(anyInt(), any());
    }

    @Test public void testOnRangingClosed_OnClosedCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);
        session.onRangingStarted(PARAMS);
        session.onRangingClosed(CLOSE_REASON, PARAMS);
        verify(callback, times(1)).onClosed(anyInt(), any());

        verifyOpenState(session, false);
        session.onRangingClosed(CLOSE_REASON, PARAMS);
        verify(callback, times(2)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingResult_OnReportReceivedCalled() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);
        verifyOpenState(session, false);

        session.onRangingStarted(PARAMS);
        verifyOpenState(session, true);

        RangingReport report = UwbTestUtils.getRangingReports(1);
        session.onRangingResult(report);
        verify(callback, times(1)).onReportReceived(eq(report));
    }

    @Test
    public void testClose() throws RemoteException {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);
        session.onRangingStarted(PARAMS);

        // Calling close multiple times should invoke closeRanging until the session receives
        // the onClosed callback.
        int totalCallsBeforeOnRangingClosed = 3;
        for (int i = 1; i <= totalCallsBeforeOnRangingClosed; i++) {
            session.close();
            verifyOpenState(session, true);
            verify(ADAPTER, times(i)).closeRanging(handle);
            verify(callback, times(0)).onClosed(anyInt(), any());
        }

        // After onClosed is invoked, then the adapter should no longer be called for each call to
        // the session's close.
        final int totalCallsAfterOnRangingClosed = 2;
        for (int i = 1; i <= totalCallsAfterOnRangingClosed; i++) {
            session.onRangingClosed(CLOSE_REASON, PARAMS);
            verifyOpenState(session, false);
            verify(ADAPTER, times(totalCallsBeforeOnRangingClosed)).closeRanging(handle);
            verify(callback, times(i)).onClosed(anyInt(), any());
        }
    }

    @Test
    public void testOnRangingResult_OnReportReceivedCalledWhenOpen() {
        SessionHandle handle = new SessionHandle(123);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);

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
        RangingSession session = new RangingSession(EXECUTOR, callback, ADAPTER, handle);

        assertFalse(session.isOpen());

        // Verify that the onReportReceived callback was invoked
        RangingReport report = UwbTestUtils.getRangingReports(1);
        session.onRangingResult(report);
        verify(callback, times(0)).onReportReceived(report);
    }

    private void verifyOpenState(RangingSession session, boolean expected) {
        assertEquals(expected, session.isOpen());
    }
}
