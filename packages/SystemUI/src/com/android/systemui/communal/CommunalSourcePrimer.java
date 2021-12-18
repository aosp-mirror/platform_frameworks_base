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

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import java.util.Optional;

import javax.inject.Inject;

/**
 * The {@link CommunalSourcePrimer} is responsible for priming SystemUI with a pre-configured
 * Communal source. The SystemUI service binds to the component to retrieve the
 * {@link CommunalSource}. {@link CommunalSourcePrimer} has no effect
 * if there is no pre-defined value.
 */
@SysUISingleton
public class CommunalSourcePrimer extends CoreStartable {
    private static final String TAG = "CommunalSourcePrimer";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SystemClock mSystemClock;
    private final DelayableExecutor mMainExecutor;
    private final CommunalSourceMonitor mMonitor;
    private final int mBaseReconnectDelayMs;
    private final int mMaxReconnectAttempts;
    private final int mMinConnectionDuration;

    private int mReconnectAttempts = 0;
    private Runnable mCurrentReconnectCancelable;

    private final Optional<CommunalSource.Observer> mObserver;
    private final Optional<CommunalSource.Connector> mConnector;

    private CommunalSource.Connection mCurrentConnection;

    private final Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {
            mCurrentReconnectCancelable = null;
            connect();
        }
    };

    private final CommunalSource.Observer.Callback mObserverCallback = () -> {
        initiateConnectionAttempt();
    };

    @Inject
    public CommunalSourcePrimer(Context context, @Main Resources resources,
            SystemClock clock,
            DelayableExecutor mainExecutor,
            CommunalSourceMonitor monitor,
            Optional<CommunalSource.Connector> connector,
            Optional<CommunalSource.Observer> observer) {
        super(context);
        mSystemClock = clock;
        mMainExecutor = mainExecutor;
        mMonitor = monitor;
        mConnector = connector;
        mObserver = observer;

        mMaxReconnectAttempts = resources.getInteger(
                R.integer.config_communalSourceMaxReconnectAttempts);
        mBaseReconnectDelayMs = resources.getInteger(
                R.integer.config_communalSourceReconnectBaseDelay);
        mMinConnectionDuration = resources.getInteger(
                R.integer.config_connectionMinDuration);
    }

    @Override
    public void start() {
    }

    private void initiateConnectionAttempt() {
        // Reset attempts
        mReconnectAttempts = 0;
        mMonitor.setSource(null);

        // The first attempt is always a direct invocation rather than delayed.
        connect();
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

    @Override
    protected void onBootCompleted() {
        if (mObserver.isPresent()) {
            mObserver.get().addCallback(mObserverCallback);
        }
        initiateConnectionAttempt();
    }

    private void connect() {
        if (DEBUG) {
            Log.d(TAG, "attempting to connect to communal source");
        }

        if (mCurrentConnection != null) {
            if (DEBUG) {
                Log.d(TAG, "canceling in-flight connection");
            }
            mCurrentConnection.disconnect();
        }

        mCurrentConnection = mConnector.get().connect(new CommunalSource.Connection.Callback() {
            private long mStartTime;

            @Override
            public void onSourceEstablished(Optional<CommunalSource> optionalSource) {
                mStartTime = mSystemClock.currentTimeMillis();

                if (optionalSource.isPresent()) {
                    final CommunalSource source = optionalSource.get();
                    mMonitor.setSource(source);
                } else {
                    scheduleConnectionAttempt();
                }
            }

            @Override
            public void onDisconnected() {
                if (mSystemClock.currentTimeMillis() - mStartTime > mMinConnectionDuration) {
                    initiateConnectionAttempt();
                } else {
                    scheduleConnectionAttempt();
                }
            }
        });
    }
}
