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

import static com.android.systemui.util.service.dagger.ObservableServiceModule.BASE_RECONNECT_DELAY_MS;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.MAX_RECONNECT_ATTEMPTS;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.MIN_CONNECTION_DURATION_MS;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.OBSERVER;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.SERVICE_CONNECTION;

import android.util.Log;

import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The {@link PersistentConnectionManager} is responsible for maintaining a connection to a
 * {@link ObservableServiceConnection}.
 * @param <T> The transformed connection type handled by the service.
 */
public class PersistentConnectionManager<T> {
    private static final String TAG = "PersistentConnManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SystemClock mSystemClock;
    private final DelayableExecutor mMainExecutor;
    private final int mBaseReconnectDelayMs;
    private final int mMaxReconnectAttempts;
    private final int mMinConnectionDuration;
    private final Observer mObserver;

    private int mReconnectAttempts = 0;
    private Runnable mCurrentReconnectCancelable;

    private final ObservableServiceConnection<T> mConnection;

    private final Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {
            mCurrentReconnectCancelable = null;
            mConnection.bind();
        }
    };

    private final Observer.Callback mObserverCallback = () -> initiateConnectionAttempt();

    private final ObservableServiceConnection.Callback mConnectionCallback =
            new ObservableServiceConnection.Callback() {
        private long mStartTime;

        @Override
        public void onConnected(ObservableServiceConnection connection, Object proxy) {
            mStartTime = mSystemClock.currentTimeMillis();
        }

        @Override
        public void onDisconnected(ObservableServiceConnection connection, int reason) {
            // Do not attempt to reconnect if we were manually unbound
            if (reason == ObservableServiceConnection.DISCONNECT_REASON_UNBIND) {
                return;
            }

            if (mSystemClock.currentTimeMillis() - mStartTime > mMinConnectionDuration) {
                initiateConnectionAttempt();
            } else {
                scheduleConnectionAttempt();
            }
        }
    };

    @Inject
    public PersistentConnectionManager(
            SystemClock clock,
            DelayableExecutor mainExecutor,
            @Named(SERVICE_CONNECTION) ObservableServiceConnection<T> serviceConnection,
            @Named(MAX_RECONNECT_ATTEMPTS) int maxReconnectAttempts,
            @Named(BASE_RECONNECT_DELAY_MS) int baseReconnectDelayMs,
            @Named(MIN_CONNECTION_DURATION_MS) int minConnectionDurationMs,
            @Named(OBSERVER) Observer observer) {
        mSystemClock = clock;
        mMainExecutor = mainExecutor;
        mConnection = serviceConnection;
        mObserver = observer;

        mMaxReconnectAttempts = maxReconnectAttempts;
        mBaseReconnectDelayMs = baseReconnectDelayMs;
        mMinConnectionDuration = minConnectionDurationMs;
    }

    /**
     * Begins the {@link PersistentConnectionManager} by connecting to the associated service.
     */
    public void start() {
        mConnection.addCallback(mConnectionCallback);
        mObserver.addCallback(mObserverCallback);
        initiateConnectionAttempt();
    }

    /**
     * Brings down the {@link PersistentConnectionManager}, disconnecting from the service.
     */
    public void stop() {
        mConnection.removeCallback(mConnectionCallback);
        mObserver.removeCallback(mObserverCallback);
        mConnection.unbind();
    }

    private void initiateConnectionAttempt() {
        // Reset attempts
        mReconnectAttempts = 0;

        // The first attempt is always a direct invocation rather than delayed.
        mConnection.bind();
    }

    private void scheduleConnectionAttempt() {
        // always clear cancelable if present.
        if (mCurrentReconnectCancelable != null) {
            mCurrentReconnectCancelable.run();
            mCurrentReconnectCancelable = null;
        }

        if (mReconnectAttempts >= mMaxReconnectAttempts) {
            if (DEBUG) {
                Log.d(TAG, "exceeded max connection attempts.");
            }
            return;
        }

        final long reconnectDelayMs =
                (long) Math.scalb(mBaseReconnectDelayMs, mReconnectAttempts);

        if (DEBUG) {
            Log.d(TAG,
                    "scheduling connection attempt in " + reconnectDelayMs + "milliseconds");
        }

        mCurrentReconnectCancelable = mMainExecutor.executeDelayed(mConnectRunnable,
                reconnectDelayMs);

        mReconnectAttempts++;
    }
}
