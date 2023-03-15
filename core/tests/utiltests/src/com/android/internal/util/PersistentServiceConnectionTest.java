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

package com.android.internal.util;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.android.internal.util.ObservableServiceConnection.ServiceTransformer;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public class PersistentServiceConnectionTest {
    private static final ComponentName COMPONENT_NAME =
            new ComponentName("test.package", "component");
    private static final int MAX_RETRIES = 2;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int CONNECTION_MIN_DURATION_MS = 5000;
    private PersistentServiceConnection<Proxy> mConnection;

    public static class Proxy {
    }

    @Mock
    private Context mContext;
    @Mock
    private Intent mIntent;
    @Mock
    private Proxy mResult;
    @Mock
    private IBinder mBinder;
    @Mock
    private ServiceTransformer<Proxy> mTransformer;
    @Mock
    private ObservableServiceConnection.Callback<Proxy> mCallback;
    private TestHandler mHandler;
    private final FakeExecutor mFakeExecutor = new FakeExecutor();
    private OffsettableClock mClock;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mClock = new OffsettableClock.Stopped();
        mHandler = spy(new TestHandler(null, mClock));

        mConnection = new PersistentServiceConnection<>(
                mContext,
                mFakeExecutor,
                mHandler,
                mTransformer,
                mIntent,
                /* flags= */ Context.BIND_AUTO_CREATE,
                CONNECTION_MIN_DURATION_MS,
                MAX_RETRIES,
                RETRY_DELAY_MS,
                new TestInjector(mClock));

        mClock.fastForward(1000);
        mConnection.addCallback(mCallback);
        when(mTransformer.convert(mBinder)).thenReturn(mResult);
    }

    @After
    public void tearDown() {
        mFakeExecutor.clearAll();
    }

    @Test
    public void testConnect() {
        mConnection.bind();
        mConnection.onServiceConnected(COMPONENT_NAME, mBinder);
        mFakeExecutor.runAll();
        // Ensure that we did not schedule a retry
        verify(mHandler, never()).postDelayed(any(), anyLong());
    }

    @Test
    public void testRetryOnBindFailure() {
        mConnection.bind();

        verify(mContext, times(1)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));

        // After disconnect, a reconnection should be attempted after the RETRY_DELAY_MS
        mConnection.onServiceDisconnected(COMPONENT_NAME);
        mFakeExecutor.runAll();
        advanceTime(RETRY_DELAY_MS);
        verify(mContext, times(2)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));

        // Reconnect attempt #2
        mConnection.onServiceDisconnected(COMPONENT_NAME);
        mFakeExecutor.runAll();
        advanceTime(RETRY_DELAY_MS * 2);
        verify(mContext, times(3)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));

        // There should be no more reconnect attempts, since the maximum is 2
        mConnection.onServiceDisconnected(COMPONENT_NAME);
        mFakeExecutor.runAll();
        advanceTime(RETRY_DELAY_MS * 4);
        verify(mContext, times(3)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));
    }

    @Test
    public void testManualUnbindDoesNotReconnect() {
        mConnection.bind();

        verify(mContext, times(1)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));

        mConnection.unbind();
        // Ensure that disconnection after unbind does not reconnect.
        mConnection.onServiceDisconnected(COMPONENT_NAME);
        mFakeExecutor.runAll();
        advanceTime(RETRY_DELAY_MS);

        verify(mContext, times(1)).bindService(
                eq(mIntent),
                anyInt(),
                eq(mFakeExecutor),
                eq(mConnection));
    }

    private void advanceTime(long millis) {
        mClock.fastForward(millis);
        mHandler.timeAdvance();
    }

    static class TestInjector extends PersistentServiceConnection.Injector {
        private final OffsettableClock mClock;

        TestInjector(OffsettableClock clock) {
            mClock = clock;
        }

        @Override
        public long uptimeMillis() {
            return mClock.now();
        }
    }

    static class FakeExecutor implements Executor {
        private final Queue<Runnable> mQueue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            mQueue.add(command);
        }

        public void runAll() {
            while (!mQueue.isEmpty()) {
                mQueue.remove().run();
            }
        }

        public void clearAll() {
            while (!mQueue.isEmpty()) {
                mQueue.remove();
            }
        }
    }
}
