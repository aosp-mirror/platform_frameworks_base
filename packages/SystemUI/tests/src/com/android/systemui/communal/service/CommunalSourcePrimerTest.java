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

package com.android.systemui.communal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.IBinder;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.CommunalSourceMonitor;
import com.android.systemui.shared.communal.ICommunalSource;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalSourcePrimerTest extends SysuiTestCase {
    private static final String TEST_COMPONENT_NAME = "com.google.tests/.CommualService";
    private static final ComponentName TEST_COMPONENT =
            ComponentName.unflattenFromString(TEST_COMPONENT_NAME);
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;

    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Mock
    private CommunalSourceMonitor mCommunalSourceMonitor;

    @Mock
    private CommunalSourceImpl.Factory mCommunalSourceFactory;

    @Mock
    private CommunalSourceImpl mCommunalSourceImpl;

    @Mock
    private IBinder mServiceProxy;

    private CommunalSourcePrimer mPrimer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mResources.getInteger(R.integer.config_communalSourceMaxReconnectAttempts))
                .thenReturn(MAX_RETRIES);
        when(mResources.getInteger(R.integer.config_communalSourceReconnectBaseDelay))
                .thenReturn(RETRY_DELAY_MS);
        when(mResources.getString(R.string.config_communalSourceComponent))
                .thenReturn(TEST_COMPONENT_NAME);
        when(mCommunalSourceFactory.create(any(ICommunalSource.class)))
                .thenReturn(mCommunalSourceImpl);

        mPrimer = new CommunalSourcePrimer(mContext, mResources, mFakeExecutor,
                mCommunalSourceMonitor, mCommunalSourceFactory);
    }

    @Test
    public void testNoConnectWithEmptyComponent() {
        when(mResources.getString(R.string.config_communalSourceComponent)).thenReturn(null);
        final CommunalSourcePrimer emptyComponentPrimer = new CommunalSourcePrimer(mContext,
                mResources, mFakeExecutor, mCommunalSourceMonitor, mCommunalSourceFactory);

        emptyComponentPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();
        // When there is no component, we should not register any broadcast receives or bind to
        // any service
        verify(mContext, times(0))
                .registerReceiver(any(BroadcastReceiver.class), any(IntentFilter.class));
        verify(mContext, times(0)).bindService(any(Intent.class), anyInt(),
                any(Executor.class), any(ServiceConnection.class));
    }

    private ServiceConnection givenOnBootCompleted(boolean bindSucceed) {
        ArgumentCaptor<ServiceConnection> connectionCapture =
                ArgumentCaptor.forClass(ServiceConnection.class);

        when(mContext.bindService(any(Intent.class), anyInt(), any(Executor.class),
                any(ServiceConnection.class))).thenReturn(bindSucceed);

        mPrimer.onBootCompleted();
        mFakeExecutor.runAllReady();

        verify(mContext).bindService(any(Intent.class), anyInt(), any(Executor.class),
                connectionCapture.capture());

        // Simulate successful connection.
        return connectionCapture.getValue();
    }

    @Test
    public void testConnect() {
        final ServiceConnection connection = givenOnBootCompleted(true);

        // Simulate successful connection.
        connection.onServiceConnected(TEST_COMPONENT, mServiceProxy);

        // Verify source created and monitor informed.
        verify(mCommunalSourceFactory).create(any(ICommunalSource.class));
        verify(mCommunalSourceMonitor).setSource(mCommunalSourceImpl);
    }

    @Test
    public void testRetryOnBindFailure() {
        // Fail to bind on connection.
        givenOnBootCompleted(false);

        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            verify(mContext, times(1)).bindService(any(Intent.class),
                    anyInt(), any(Executor.class), any(ServiceConnection.class));
            clearInvocations(mContext);
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }

        // Verify no more attempts occur.
        verify(mContext, times(0)).bindService(any(Intent.class), anyInt(),
                any(Executor.class), any(ServiceConnection.class));

        // Verify source is not created and monitor is not informed.
        verify(mCommunalSourceFactory, times(0))
                .create(any(ICommunalSource.class));
        verify(mCommunalSourceMonitor, times(0))
                .setSource(any(CommunalSourceImpl.class));
    }

    @Test
    public void testAttemptOnPackageChange() {
        ArgumentCaptor<BroadcastReceiver> receiverCapture =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        // Fail to bind initially.
        givenOnBootCompleted(false);

        // Capture broadcast receiver.
        verify(mContext).registerReceiver(receiverCapture.capture(), any(IntentFilter.class));

        clearInvocations(mContext);

        // Inform package has been added.
        receiverCapture.getValue().onReceive(mContext, new Intent());

        // Verify bind has been attempted.
        verify(mContext, times(1)).bindService(any(Intent.class), anyInt(),
                any(Executor.class), any(ServiceConnection.class));
    }

    @Test
    public void testRetryOnServiceDisconnected() {
        verifyConnectionFailureReconnect(v -> v.onServiceDisconnected(TEST_COMPONENT));
    }

    @Test
    public void testRetryOnBindingDied() {
        verifyConnectionFailureReconnect(v -> v.onBindingDied(TEST_COMPONENT));
    }

    private void verifyConnectionFailureReconnect(ConnectionHandler connectionHandler) {
        // Fail to bind on connection.
        final ServiceConnection connection = givenOnBootCompleted(false);

        clearInvocations(mContext, mCommunalSourceMonitor);

        connectionHandler.onConnectionMade(connection);

        // Ensure source is cleared.
        verify(mCommunalSourceMonitor).setSource(null);

        // Ensure request made to bind. This is not a reattempt so it should happen in the same
        // execution loop.
        verify(mContext).bindService(any(Intent.class), anyInt(), any(Executor.class),
                any(ServiceConnection.class));
    }

    interface ConnectionHandler {
        void onConnectionMade(ServiceConnection connection);
    }
}
