/*
 * Copyright 2020 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.AttributionSource;
import android.os.PersistableBundle;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

/**
 * Test of {@link AdapterStateListener}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingManagerTest {

    private static final Executor EXECUTOR = UwbTestUtils.getExecutor();
    private static final PersistableBundle PARAMS = new PersistableBundle();
    private static final @RangingChangeReason int REASON = RangingChangeReason.UNKNOWN;
    private static final int UID = 343453;
    private static final String PACKAGE_NAME = "com.uwb.test";
    private static final AttributionSource ATTRIBUTION_SOURCE =
            new AttributionSource.Builder(UID).setPackageName(PACKAGE_NAME).build();

    @Test
    public void testOpenSession_OpenRangingInvoked() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingManager rangingManager = new RangingManager(adapter);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);
        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), any(), any(), any());
    }

    @Test
    public void testOnRangingOpened_InvalidSessionHandle() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingManager rangingManager = new RangingManager(adapter);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);

        rangingManager.onRangingOpened(new SessionHandle(2));
        verify(callback, times(0)).onOpened(any());
    }

    @Test
    public void testOnRangingOpened_MultipleSessionsRegistered() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession.Callback callback1 = mock(RangingSession.Callback.class);
        RangingSession.Callback callback2 = mock(RangingSession.Callback.class);
        ArgumentCaptor<SessionHandle> sessionHandleCaptor =
                ArgumentCaptor.forClass(SessionHandle.class);

        RangingManager rangingManager = new RangingManager(adapter);
        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback1);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle1 = sessionHandleCaptor.getValue();

        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback2);
        verify(adapter, times(2)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle2 = sessionHandleCaptor.getValue();

        rangingManager.onRangingOpened(sessionHandle1);
        verify(callback1, times(1)).onOpened(any());
        verify(callback2, times(0)).onOpened(any());

        rangingManager.onRangingOpened(sessionHandle2);
        verify(callback1, times(1)).onOpened(any());
        verify(callback2, times(1)).onOpened(any());
    }

    @Test
    public void testCorrectCallbackInvoked() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingManager rangingManager = new RangingManager(adapter);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);

        ArgumentCaptor<SessionHandle> sessionHandleCaptor =
                ArgumentCaptor.forClass(SessionHandle.class);

        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle handle = sessionHandleCaptor.getValue();

        rangingManager.onRangingOpened(handle);
        verify(callback, times(1)).onOpened(any());

        rangingManager.onRangingStarted(handle, PARAMS);
        verify(callback, times(1)).onStarted(eq(PARAMS));

        rangingManager.onRangingStartFailed(handle, REASON, PARAMS);
        verify(callback, times(1)).onStartFailed(eq(REASON), eq(PARAMS));

        RangingReport report = UwbTestUtils.getRangingReports(1);
        rangingManager.onRangingResult(handle, report);
        verify(callback, times(1)).onReportReceived(eq(report));

        rangingManager.onRangingReconfigured(handle, PARAMS);
        verify(callback, times(1)).onReconfigured(eq(PARAMS));

        rangingManager.onRangingReconfigureFailed(handle, REASON, PARAMS);
        verify(callback, times(1)).onReconfigureFailed(eq(REASON), eq(PARAMS));

        rangingManager.onRangingStopped(handle, REASON, PARAMS);
        verify(callback, times(1)).onStopped(eq(REASON), eq(PARAMS));

        rangingManager.onRangingStopFailed(handle, REASON, PARAMS);
        verify(callback, times(1)).onStopFailed(eq(REASON), eq(PARAMS));

        rangingManager.onRangingClosed(handle, REASON, PARAMS);
        verify(callback, times(1)).onClosed(eq(REASON), eq(PARAMS));
    }

    @Test
    public void testOnRangingClosed_MultipleSessionsRegistered() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        // Verify that if multiple sessions are registered, only the session that is
        // requested to close receives the associated callbacks
        RangingSession.Callback callback1 = mock(RangingSession.Callback.class);
        RangingSession.Callback callback2 = mock(RangingSession.Callback.class);

        RangingManager rangingManager = new RangingManager(adapter);
        ArgumentCaptor<SessionHandle> sessionHandleCaptor =
                ArgumentCaptor.forClass(SessionHandle.class);

        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback1);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle1 = sessionHandleCaptor.getValue();

        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback2);
        verify(adapter, times(2)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle2 = sessionHandleCaptor.getValue();

        rangingManager.onRangingClosed(sessionHandle1, REASON, PARAMS);
        verify(callback1, times(1)).onClosed(anyInt(), any());
        verify(callback2, times(0)).onClosed(anyInt(), any());

        rangingManager.onRangingClosed(sessionHandle2, REASON, PARAMS);
        verify(callback1, times(1)).onClosed(anyInt(), any());
        verify(callback2, times(1)).onClosed(anyInt(), any());
    }

    @Test
    public void testOnRangingReport_MultipleSessionsRegistered() throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingSession.Callback callback1 = mock(RangingSession.Callback.class);
        RangingSession.Callback callback2 = mock(RangingSession.Callback.class);

        ArgumentCaptor<SessionHandle> sessionHandleCaptor =
                ArgumentCaptor.forClass(SessionHandle.class);

        RangingManager rangingManager = new RangingManager(adapter);
        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback1);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle1 = sessionHandleCaptor.getValue();

        rangingManager.onRangingStarted(sessionHandle1, PARAMS);
        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback2);
        verify(adapter, times(2)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle sessionHandle2 = sessionHandleCaptor.getValue();
        rangingManager.onRangingStarted(sessionHandle2, PARAMS);

        rangingManager.onRangingResult(sessionHandle1, UwbTestUtils.getRangingReports(1));
        verify(callback1, times(1)).onReportReceived(any());
        verify(callback2, times(0)).onReportReceived(any());

        rangingManager.onRangingResult(sessionHandle2, UwbTestUtils.getRangingReports(1));
        verify(callback1, times(1)).onReportReceived(any());
        verify(callback2, times(1)).onReportReceived(any());
    }

    @Test
    public void testReasons() throws RemoteException {
        runReason(RangingChangeReason.LOCAL_API,
                RangingSession.Callback.REASON_LOCAL_REQUEST);

        runReason(RangingChangeReason.MAX_SESSIONS_REACHED,
                RangingSession.Callback.REASON_MAX_SESSIONS_REACHED);

        runReason(RangingChangeReason.PROTOCOL_SPECIFIC,
                RangingSession.Callback.REASON_PROTOCOL_SPECIFIC_ERROR);

        runReason(RangingChangeReason.REMOTE_REQUEST,
                RangingSession.Callback.REASON_REMOTE_REQUEST);

        runReason(RangingChangeReason.SYSTEM_POLICY,
                RangingSession.Callback.REASON_SYSTEM_POLICY);

        runReason(RangingChangeReason.BAD_PARAMETERS,
                RangingSession.Callback.REASON_BAD_PARAMETERS);

        runReason(RangingChangeReason.UNKNOWN,
                RangingSession.Callback.REASON_UNKNOWN);
    }

    private void runReason(@RangingChangeReason int reasonIn,
            @RangingSession.Callback.Reason int reasonOut) throws RemoteException {
        IUwbAdapter adapter = mock(IUwbAdapter.class);
        RangingManager rangingManager = new RangingManager(adapter);
        RangingSession.Callback callback = mock(RangingSession.Callback.class);

        ArgumentCaptor<SessionHandle> sessionHandleCaptor =
                ArgumentCaptor.forClass(SessionHandle.class);

        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback);
        verify(adapter, times(1)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        SessionHandle handle = sessionHandleCaptor.getValue();

        rangingManager.onRangingOpenFailed(handle, reasonIn, PARAMS);
        verify(callback, times(1)).onOpenFailed(eq(reasonOut), eq(PARAMS));

        // Open a new session
        rangingManager.openSession(ATTRIBUTION_SOURCE, PARAMS, EXECUTOR, callback);
        verify(adapter, times(2)).openRanging(
                eq(ATTRIBUTION_SOURCE), sessionHandleCaptor.capture(), any(), any());
        handle = sessionHandleCaptor.getValue();
        rangingManager.onRangingOpened(handle);

        rangingManager.onRangingStartFailed(handle, reasonIn, PARAMS);
        verify(callback, times(1)).onStartFailed(eq(reasonOut), eq(PARAMS));

        rangingManager.onRangingReconfigureFailed(handle, reasonIn, PARAMS);
        verify(callback, times(1)).onReconfigureFailed(eq(reasonOut), eq(PARAMS));

        rangingManager.onRangingStopFailed(handle, reasonIn, PARAMS);
        verify(callback, times(1)).onStopFailed(eq(reasonOut), eq(PARAMS));

        rangingManager.onRangingClosed(handle, reasonIn, PARAMS);
        verify(callback, times(1)).onClosed(eq(reasonOut), eq(PARAMS));
    }
}
