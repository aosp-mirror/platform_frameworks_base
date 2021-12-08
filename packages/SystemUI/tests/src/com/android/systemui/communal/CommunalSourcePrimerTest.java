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

package com.android.systemui.communal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;;
import com.android.systemui.util.ref.GcWeakReference;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalSourcePrimerTest extends SysuiTestCase {
    private static final String TEST_COMPONENT_NAME = "com.google.tests/.CommunalService";
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECTION_MIN_DURATION_MS = 5000;

    // A simple implementation of {@link CommunalSource.Observer} to capture a callback value.
    // Used to ensure the references to a {@link CommunalSource.Observer.Callback} can be fully
    // removed.
    private static class FakeObserver implements CommunalSource.Observer {
        public GcWeakReference<Callback> mLastCallback;

        @Override
        public void addCallback(Callback callback) {
            mLastCallback = new GcWeakReference<>(callback);
        }

        @Override
        public void removeCallback(Callback callback) {
            if (mLastCallback.get() == callback) {
                mLastCallback = null;
            }
        }
    }

    // A simple implementation of {@link CommunalSource} to capture callback values. This
    // implementation better emulates the {@link WeakReference} wrapping behavior of
    // {@link CommunalSource} implementations than a mock.
    private static class FakeSource implements CommunalSource {
        @Override
        public ListenableFuture<CommunalViewResult> requestCommunalView(Context context) {
            return null;
        }
    }

    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    private FakeSystemClock mFakeClock = new FakeSystemClock();
    private FakeExecutor mFakeExecutor = new FakeExecutor(mFakeClock);

    private FakeSource mSource = new FakeSource();

    @Mock
    private CommunalSourceMonitor mCommunalSourceMonitor;

    @Mock
    private CommunalSource.Connector mConnector;

    @Mock
    private CommunalSource.Connection mConnection;

    private FakeObserver mObserver = new FakeObserver();

    private CommunalSourcePrimer mPrimer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mResources.getInteger(R.integer.config_communalSourceMaxReconnectAttempts))
                .thenReturn(MAX_RETRIES);
        when(mResources.getInteger(R.integer.config_communalSourceReconnectBaseDelay))
                .thenReturn(RETRY_DELAY_MS);
        when(mResources.getInteger(R.integer.config_communalSourceReconnectBaseDelay))
                .thenReturn(RETRY_DELAY_MS);
        when(mResources.getString(R.string.config_communalSourceComponent))
                .thenReturn(TEST_COMPONENT_NAME);
        when(mResources.getInteger(R.integer.config_connectionMinDuration))
                .thenReturn(CONNECTION_MIN_DURATION_MS);

        mPrimer = new CommunalSourcePrimer(mContext, mResources, mFakeClock, mFakeExecutor,
                mCommunalSourceMonitor, Optional.of(mConnector), Optional.of(mObserver));
    }

    private CommunalSource.Connection.Callback captureCallbackAndSend(
            CommunalSource.Connector connector, Optional<CommunalSource> source) {
        ArgumentCaptor<CommunalSource.Connection.Callback> connectionCallback =
                ArgumentCaptor.forClass(CommunalSource.Connection.Callback.class);

        verify(connector).connect(connectionCallback.capture());
        Mockito.clearInvocations(connector);

        final CommunalSource.Connection.Callback callback = connectionCallback.getValue();
        callback.onSourceEstablished(source);

        return callback;
    }

    @Test
    public void testConnect() {
        mPrimer.onBootCompleted();
        captureCallbackAndSend(mConnector, Optional.of(mSource));
        verify(mCommunalSourceMonitor).setSource(mSource);
    }

    @Test
    public void testRetryOnBindFailure() throws Exception {
        mPrimer.onBootCompleted();

        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            captureCallbackAndSend(mConnector, Optional.empty());
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }

        verify(mCommunalSourceMonitor, never()).setSource(Mockito.notNull());
    }

    @Test
    public void testRetryOnDisconnectFailure() throws Exception {
        mPrimer.onBootCompleted();
        // Verify attempts happen. Note that we account for the retries plus initial attempt, which
        // is not scheduled.
        for (int attemptCount = 0; attemptCount < MAX_RETRIES + 1; attemptCount++) {
            final CommunalSource.Connection.Callback callback =
                    captureCallbackAndSend(mConnector, Optional.of(mSource));
            verify(mCommunalSourceMonitor).setSource(Mockito.notNull());
            clearInvocations(mCommunalSourceMonitor);
            callback.onDisconnected();
            mFakeExecutor.advanceClockToNext();
            mFakeExecutor.runAllReady();
        }

        verify(mConnector, never()).connect(any());
    }

    @Test
    public void testAttemptOnPackageChange() {
        mPrimer.onBootCompleted();
        captureCallbackAndSend(mConnector, Optional.empty());

        mObserver.mLastCallback.get().onSourceChanged();

        verify(mConnector, times(1)).connect(any());
    }

    @Test
    public void testDisconnect() {
        mPrimer.onBootCompleted();
        final CommunalSource.Connection.Callback callback =
                captureCallbackAndSend(mConnector, Optional.of(mSource));
        verify(mCommunalSourceMonitor).setSource(mSource);

        mFakeClock.advanceTime(CONNECTION_MIN_DURATION_MS + 1);
        callback.onDisconnected();

        verify(mConnector).connect(any());
    }
}
