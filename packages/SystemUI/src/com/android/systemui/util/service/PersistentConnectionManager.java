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
import static com.android.systemui.util.service.dagger.ObservableServiceModule.DUMPSYS_NAME;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.MAX_RECONNECT_ATTEMPTS;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.MIN_CONNECTION_DURATION_MS;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.OBSERVER;
import static com.android.systemui.util.service.dagger.ObservableServiceModule.SERVICE_CONNECTION;

import android.util.Log;

import androidx.annotation.NonNull;

import com.android.app.tracing.TraceStateLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The {@link PersistentConnectionManager} is responsible for maintaining a connection to a
 * {@link ObservableServiceConnection}.
 *
 * @param <T> The transformed connection type handled by the service.
 */
public class PersistentConnectionManager<T> implements Dumpable {
    private static final String TAG = "PersistentConnManager";

    private final SystemClock mSystemClock;
    private final DelayableExecutor mBgExecutor;
    private final int mBaseReconnectDelayMs;
    private final int mMaxReconnectAttempts;
    private final int mMinConnectionDuration;
    private final Observer mObserver;
    private final DumpManager mDumpManager;
    private final String mDumpsysName;
    private final TraceStateLogger mConnectionReasonLogger;

    private int mReconnectAttempts = 0;
    private Runnable mCurrentReconnectCancelable;

    private final ObservableServiceConnection<T> mConnection;

    private final Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {
            mConnectionReasonLogger.log("ConnectionReasonRetry");
            mCurrentReconnectCancelable = null;
            mConnection.bind();
        }
    };

    private final Observer.Callback mObserverCallback = () -> initiateConnectionAttempt(
            "ConnectionReasonObserver");

    private final ObservableServiceConnection.Callback<T> mConnectionCallback =
            new ObservableServiceConnection.Callback<>() {
                private long mStartTime = -1;

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

                    if (mStartTime <= 0) {
                        Log.e(TAG, "onDisconnected called with invalid connection start time: "
                                + mStartTime);
                        return;
                    }

                    final float connectionDuration = mSystemClock.currentTimeMillis() - mStartTime;
                    // Reset the start time.
                    mStartTime = -1;

                    if (connectionDuration > mMinConnectionDuration) {
                        Log.i(TAG, "immediately reconnecting since service was connected for "
                                + connectionDuration
                                + "ms which is longer than the min duration of "
                                + mMinConnectionDuration + "ms");
                        initiateConnectionAttempt("ConnectionReasonMinDurationMet");
                    } else {
                        scheduleConnectionAttempt();
                    }
                }
            };

    @Inject
    public PersistentConnectionManager(
            SystemClock clock,
            @Background DelayableExecutor bgExecutor,
            DumpManager dumpManager,
            @Named(DUMPSYS_NAME) String dumpsysName,
            @Named(SERVICE_CONNECTION) ObservableServiceConnection<T> serviceConnection,
            @Named(MAX_RECONNECT_ATTEMPTS) int maxReconnectAttempts,
            @Named(BASE_RECONNECT_DELAY_MS) int baseReconnectDelayMs,
            @Named(MIN_CONNECTION_DURATION_MS) int minConnectionDurationMs,
            @Named(OBSERVER) Observer observer) {
        mSystemClock = clock;
        mBgExecutor = bgExecutor;
        mConnection = serviceConnection;
        mObserver = observer;
        mDumpManager = dumpManager;
        mDumpsysName = TAG + "#" + dumpsysName;
        mConnectionReasonLogger = new TraceStateLogger(mDumpsysName);

        mMaxReconnectAttempts = maxReconnectAttempts;
        mBaseReconnectDelayMs = baseReconnectDelayMs;
        mMinConnectionDuration = minConnectionDurationMs;
    }

    /**
     * Begins the {@link PersistentConnectionManager} by connecting to the associated service.
     */
    public void start() {
        mDumpManager.registerCriticalDumpable(mDumpsysName, this);
        mConnection.addCallback(mConnectionCallback);
        mObserver.addCallback(mObserverCallback);
        initiateConnectionAttempt("ConnectionReasonStart");
    }

    /**
     * Brings down the {@link PersistentConnectionManager}, disconnecting from the service.
     */
    public void stop() {
        mConnection.removeCallback(mConnectionCallback);
        mObserver.removeCallback(mObserverCallback);
        mConnection.unbind();
        mDumpManager.unregisterDumpable(mDumpsysName);
    }

    /**
     * Add a callback to the {@link ObservableServiceConnection}.
     *
     * @param callback The callback to add.
     */
    public void addConnectionCallback(ObservableServiceConnection.Callback<T> callback) {
        mConnection.addCallback(callback);
    }

    /**
     * Remove a callback from the {@link ObservableServiceConnection}.
     *
     * @param callback The callback to remove.
     */
    public void removeConnectionCallback(ObservableServiceConnection.Callback<T> callback) {
        mConnection.removeCallback(callback);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mMaxReconnectAttempts: " + mMaxReconnectAttempts);
        pw.println("mBaseReconnectDelayMs: " + mBaseReconnectDelayMs);
        pw.println("mMinConnectionDuration: " + mMinConnectionDuration);
        pw.println("mReconnectAttempts: " + mReconnectAttempts);
        mConnection.dump(pw);
    }

    private void initiateConnectionAttempt(String reason) {
        mConnectionReasonLogger.log(reason);
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
            Log.d(TAG, "exceeded max connection attempts.");
            return;
        }

        final long reconnectDelayMs =
                (long) Math.scalb(mBaseReconnectDelayMs, mReconnectAttempts);

        Log.d(TAG,
                "scheduling connection attempt in " + reconnectDelayMs + "milliseconds");
        mCurrentReconnectCancelable = mBgExecutor.executeDelayed(mConnectRunnable,
                reconnectDelayMs);

        mReconnectAttempts++;
    }
}
