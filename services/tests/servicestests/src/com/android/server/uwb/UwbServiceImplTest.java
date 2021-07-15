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

package com.android.server.uwb;

import static android.Manifest.permission.UWB_PRIVILEGED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;
import android.uwb.IUwbAdapter;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.RangingReport;
import android.uwb.RangingSession;
import android.uwb.SessionHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link UwbServiceImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class UwbServiceImplTest {
    private static final int UID = 343453;
    private static final String PACKAGE_NAME = "com.uwb.test";
    private static final AttributionSource ATTRIBUTION_SOURCE =
            new AttributionSource.Builder(UID).setPackageName(PACKAGE_NAME).build();

    @Mock private IUwbAdapter mVendorService;
    @Mock private IBinder mVendorServiceBinder;
    @Mock private Context mContext;
    @Mock private UwbInjector mUwbInjector;
    @Captor private ArgumentCaptor<IUwbRangingCallbacks> mRangingCbCaptor;
    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mClientDeathCaptor;
    @Captor private ArgumentCaptor<IBinder.DeathRecipient> mVendorServiceDeathCaptor;

    private UwbServiceImpl mUwbServiceImpl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mUwbInjector.getVendorService()).thenReturn(mVendorService);
        when(mUwbInjector.checkUwbRangingPermissionForDataDelivery(any(), any())).thenReturn(true);
        when(mVendorService.asBinder()).thenReturn(mVendorServiceBinder);
        mUwbServiceImpl = new UwbServiceImpl(mContext, mUwbInjector);
    }

    @Test
    public void testApiCallThrowsIllegalStateExceptionIfVendorServiceNotFound() throws Exception {
        when(mUwbInjector.getVendorService()).thenReturn(null);

        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        try {
            mUwbServiceImpl.registerAdapterStateCallbacks(cb);
            fail();
        } catch (IllegalStateException e) { /* pass */ }
    }

    @Test
    public void testRegisterAdapterStateCallbacks() throws Exception {
        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        mUwbServiceImpl.registerAdapterStateCallbacks(cb);

        verify(mVendorService).registerAdapterStateCallbacks(cb);
    }

    @Test
    public void testUnregisterAdapterStateCallbacks() throws Exception {
        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        mUwbServiceImpl.unregisterAdapterStateCallbacks(cb);

        verify(mVendorService).unregisterAdapterStateCallbacks(cb);
    }

    @Test
    public void testGetTimestampResolutionNanos() throws Exception {
        final long timestamp = 34L;
        when(mVendorService.getTimestampResolutionNanos()).thenReturn(timestamp);
        assertThat(mUwbServiceImpl.getTimestampResolutionNanos()).isEqualTo(timestamp);

        verify(mVendorService).getTimestampResolutionNanos();
    }

    @Test
    public void testGetSpecificationInfo() throws Exception {
        final PersistableBundle specification = new PersistableBundle();
        when(mVendorService.getSpecificationInfo()).thenReturn(specification);
        assertThat(mUwbServiceImpl.getSpecificationInfo()).isEqualTo(specification);

        verify(mVendorService).getSpecificationInfo();
    }

    @Test
    public void testOpenRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);

        mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);

        verify(mVendorService).openRanging(
                eq(ATTRIBUTION_SOURCE), eq(sessionHandle), mRangingCbCaptor.capture(),
                eq(parameters));
        assertThat(mRangingCbCaptor.getValue()).isNotNull();
    }

    @Test
    public void testStartRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final PersistableBundle parameters = new PersistableBundle();

        mUwbServiceImpl.startRanging(sessionHandle, parameters);

        verify(mVendorService).startRanging(sessionHandle, parameters);
    }

    @Test
    public void testReconfigureRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final PersistableBundle parameters = new PersistableBundle();

        mUwbServiceImpl.reconfigureRanging(sessionHandle, parameters);

        verify(mVendorService).reconfigureRanging(sessionHandle, parameters);
    }

    @Test
    public void testStopRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);

        mUwbServiceImpl.stopRanging(sessionHandle);

        verify(mVendorService).stopRanging(sessionHandle);
    }

    @Test
    public void testCloseRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);

        mUwbServiceImpl.closeRanging(sessionHandle);

        verify(mVendorService).closeRanging(sessionHandle);
    }

    @Test
    public void testRangingCallbacks() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);

        mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);

        verify(mVendorService).openRanging(
                eq(ATTRIBUTION_SOURCE), eq(sessionHandle), mRangingCbCaptor.capture(),
                eq(parameters));
        assertThat(mRangingCbCaptor.getValue()).isNotNull();

        // Invoke vendor service callbacks and ensure that the corresponding app callback is
        // invoked.
        mRangingCbCaptor.getValue().onRangingOpened(sessionHandle);
        verify(cb).onRangingOpened(sessionHandle);

        mRangingCbCaptor.getValue().onRangingOpenFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingOpenFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);

        mRangingCbCaptor.getValue().onRangingStarted(sessionHandle, parameters);
        verify(cb).onRangingStarted(sessionHandle, parameters);

        mRangingCbCaptor.getValue().onRangingStartFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingStartFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);

        mRangingCbCaptor.getValue().onRangingReconfigured(sessionHandle, parameters);
        verify(cb).onRangingReconfigured(sessionHandle, parameters);

        mRangingCbCaptor.getValue().onRangingReconfigureFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingReconfigureFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);

        mRangingCbCaptor.getValue().onRangingStopped(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingStopped(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);

        mRangingCbCaptor.getValue().onRangingStopFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingStopFailed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);

        final RangingReport rangingReport = new RangingReport.Builder().build();
        mRangingCbCaptor.getValue().onRangingResult(sessionHandle, rangingReport);
        verify(cb).onRangingResult(sessionHandle, rangingReport);

        mRangingCbCaptor.getValue().onRangingClosed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
        verify(cb).onRangingClosed(
                sessionHandle, RangingSession.Callback.REASON_GENERIC_ERROR, parameters);
    }

    @Test
    public void testHandleClientDeath() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);

        mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);

        verify(mVendorService).openRanging(
                eq(ATTRIBUTION_SOURCE), eq(sessionHandle), mRangingCbCaptor.capture(),
                eq(parameters));
        assertThat(mRangingCbCaptor.getValue()).isNotNull();

        verify(cbBinder).linkToDeath(mClientDeathCaptor.capture(), anyInt());
        assertThat(mClientDeathCaptor.getValue()).isNotNull();

        clearInvocations(cb);

        // Invoke cb, ensure it reaches the client.
        mRangingCbCaptor.getValue().onRangingOpened(sessionHandle);
        verify(cb).onRangingOpened(sessionHandle);

        // Trigger client death and ensure the session is stopped.
        mClientDeathCaptor.getValue().binderDied();
        verify(mVendorService).stopRanging(sessionHandle);
        verify(mVendorService).closeRanging(sessionHandle);

        // Invoke cb, it should be ignored.
        mRangingCbCaptor.getValue().onRangingStarted(sessionHandle, parameters);
        verify(cb, never()).onRangingStarted(any(), any());
    }

    @Test
    public void testHandleVendorServiceDeath() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);

        mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);

        verify(mVendorServiceBinder).linkToDeath(mVendorServiceDeathCaptor.capture(), anyInt());
        assertThat(mVendorServiceDeathCaptor.getValue()).isNotNull();

        verify(mVendorService).openRanging(
                eq(ATTRIBUTION_SOURCE), eq(sessionHandle), mRangingCbCaptor.capture(),
                eq(parameters));
        assertThat(mRangingCbCaptor.getValue()).isNotNull();

        clearInvocations(cb);

        // Invoke cb, ensure it reaches the client.
        mRangingCbCaptor.getValue().onRangingOpened(sessionHandle);
        verify(cb).onRangingOpened(sessionHandle);

        // Trigger vendor service death and ensure that the client is informed of session end.
        mVendorServiceDeathCaptor.getValue().binderDied();
        verify(cb).onRangingClosed(
                eq(sessionHandle), eq(RangingSession.Callback.REASON_UNKNOWN),
                argThat((p) -> p.isEmpty()));
    }

    @Test
    public void testThrowSecurityExceptionWhenCalledWithoutUwbPrivilegedPermission()
            throws Exception {
        doThrow(new SecurityException()).when(mContext).enforceCallingOrSelfPermission(
                eq(UWB_PRIVILEGED), any());
        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        try {
            mUwbServiceImpl.registerAdapterStateCallbacks(cb);
            fail();
        } catch (SecurityException e) { /* pass */ }
    }

    @Test
    public void testThrowSecurityExceptionWhenOpenRangingCalledWithoutUwbRangingPermission()
            throws Exception {
        doThrow(new SecurityException()).when(mUwbInjector).enforceUwbRangingPermissionForPreflight(
                any());

        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);
        try {
            mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);
            fail();
        } catch (SecurityException e) { /* pass */ }
    }

    @Test
    public void testOnRangingResultCallbackNotSentWithoutUwbRangingPermission() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();
        final IBinder cbBinder = mock(IBinder.class);
        when(cb.asBinder()).thenReturn(cbBinder);

        mUwbServiceImpl.openRanging(ATTRIBUTION_SOURCE, sessionHandle, cb, parameters);

        verify(mVendorService).openRanging(
                eq(ATTRIBUTION_SOURCE), eq(sessionHandle), mRangingCbCaptor.capture(),
                eq(parameters));
        assertThat(mRangingCbCaptor.getValue()).isNotNull();

        when(mUwbInjector.checkUwbRangingPermissionForDataDelivery(any(), any())).thenReturn(false);

        // Ensure the ranging cb is not delivered to the client.
        final RangingReport rangingReport = new RangingReport.Builder().build();
        mRangingCbCaptor.getValue().onRangingResult(sessionHandle, rangingReport);
        verify(cb, never()).onRangingResult(sessionHandle, rangingReport);
    }
}
