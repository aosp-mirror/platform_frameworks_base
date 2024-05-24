/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.util.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PersistentConnectionManagerTest extends SysuiTestCase {
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECTION_MIN_DURATION_MS = 5000;
    private static final String DUMPSYS_NAME = "dumpsys_name";

    private FakeSystemClock mFakeClock = new FakeSystemClock();
    private FakeExecutor mFakeExecutor = new FakeExecutor(mFakeClock);

    @Mock
    private ObservableServiceConnection<Proxy> mConnection;

    @Mock
    private ObservableServiceConnection.Callback<Proxy> mConnectionCallback;

    @Mock
    private Observer mObserver;

    @Mock
    private DumpManager mDumpManager;

    private static class Proxy {
    }

    private PersistentConnectionManager<Proxy> mConnectionManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mConnectionManager = new PersistentConnectionManager<>(
                mFakeClock,
                mFakeExecutor,
                mDumpManager,
                DUMPSYS_NAME,
                mConnection,
                MAX_RETRIES,
                RETRY_DELAY_MS,
                CONNECTION_MIN_DURATION_MS,
                mObserver);
    }

    private ObservableServiceConnection.Callback<Proxy> captureCallbackAndSend(
            ObservableServiceConnection<Proxy> mConnection, Proxy proxy) {
        ArgumentCaptor<ObservableServiceConnection.Callback<Proxy>> connectionCallbackCaptor =
                ArgumentCaptor.forClass(ObservableServiceConnection.Callback.class);

        verify(mConnection).addCallback(connectionCallbackCaptor.capture());
        verify(mConnection).bind();
        Mockito.clearInvocations(mConnection);

        final ObservableServiceConnection.Callback callback = connectionCallbackCaptor.getValue();
        if (proxy != null) {
            callback.onConnected(mConnection, proxy);
        } else {
            callback.onDisconnected(mConnection, 0);
        }

        return callback;
    }

    /**
     * Validates initial connection.
     */
    @Test
    public void testConnect() {
        mConnectionManager.start();
        captureCallbackAndSend(mConnection, Mockito.mock(Proxy.class));
    }

    /**
     * Ensures reconnection on disconnect.
     */
    @Test
    public void testRetryOnBindFailure() {
        mConnectionManager.start();
        ArgumentCaptor<ObservableServiceConnection.Callback<Proxy>> connectionCallbackCaptor =
                ArgumentCaptor.forClass(ObservableServiceConnection.Callback.class);

        verify(mConnection).addCallback(connectionCallbackCaptor.capture());

        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            verify(mConnection).bind();
            Mockito.clearInvocations(mConnection);
            connectionCallbackCaptor.getValue().onDisconnected(mConnection, 0);
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }
    }

    /**
     * Ensures manual unbind does not reconnect.
     */
    @Test
    public void testStopDoesNotReconnect() {
        mConnectionManager.start();
        ArgumentCaptor<ObservableServiceConnection.Callback<Proxy>> connectionCallbackCaptor =
                ArgumentCaptor.forClass(ObservableServiceConnection.Callback.class);

        verify(mConnection).addCallback(connectionCallbackCaptor.capture());
        verify(mConnection).bind();
        Mockito.clearInvocations(mConnection);
        mConnectionManager.stop();
        mFakeExecutor.advanceClockToNext();
        mFakeExecutor.runAllReady();
        verify(mConnection, never()).bind();
    }

    /**
     * Ensures rebind on package change.
     */
    @Test
    public void testAttemptOnPackageChange() {
        mConnectionManager.start();
        verify(mConnection).bind();
        ArgumentCaptor<Observer.Callback> callbackCaptor =
                ArgumentCaptor.forClass(Observer.Callback.class);
        captureCallbackAndSend(mConnection, Mockito.mock(Proxy.class));

        verify(mObserver).addCallback(callbackCaptor.capture());

        callbackCaptor.getValue().onSourceChanged();
        verify(mConnection).bind();
    }

    @Test
    public void testAddConnectionCallback() {
        mConnectionManager.addConnectionCallback(mConnectionCallback);
        verify(mConnection).addCallback(mConnectionCallback);
    }

    @Test
    public void testRemoveConnectionCallback() {
        mConnectionManager.removeConnectionCallback(mConnectionCallback);
        verify(mConnection).removeCallback(mConnectionCallback);
    }
}
