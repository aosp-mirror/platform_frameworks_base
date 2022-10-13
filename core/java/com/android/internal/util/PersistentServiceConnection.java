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

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * {@link PersistentServiceConnection} is a concrete implementation of {@link ServiceConnection}
 * that maintains the binder connection by handling reconnection when a failure occurs.
 *
 * @param <T> The transformed connection type handled by the service.
 *
 * When the target process is killed (by OOM-killer, force-stopped, crash, etc..) then this class
 * will trigger a reconnection to the target. This should be used carefully.
 *
 * NOTE: This class does *not* handle package-updates -- i.e. even if the binding dies due to
 * the target package being updated, this class won't reconnect.  This is because this class doesn't
 * know what to do when the service component has gone missing, for example.  If the user of this
 * class wants to restore the connection, then it should call {@link #unbind()} and {@link #bind}
 * explicitly.
 */
public class PersistentServiceConnection<T> extends ObservableServiceConnection<T> {
    private final Callback<T> mConnectionCallback = new Callback<T>() {
        private long mConnectedTime;

        @Override
        public void onConnected(ObservableServiceConnection<T> connection, T service) {
            mConnectedTime = mInjector.uptimeMillis();
        }

        @Override
        public void onDisconnected(ObservableServiceConnection<T> connection,
                @DisconnectReason int reason) {
            if (reason == DISCONNECT_REASON_UNBIND) return;
            synchronized (mLock) {
                if ((mInjector.uptimeMillis() - mConnectedTime) > mMinConnectionDurationMs) {
                    mReconnectAttempts = 0;
                    bindInternalLocked();
                } else {
                    scheduleConnectionAttemptLocked();
                }
            }
        }
    };

    private final Object mLock = new Object();
    private final Injector mInjector;
    private final Handler mHandler;
    private final int mMinConnectionDurationMs;
    private final int mMaxReconnectAttempts;
    private final int mBaseReconnectDelayMs;
    @GuardedBy("mLock")
    private int mReconnectAttempts;
    @GuardedBy("mLock")
    private Object mCancelToken;

    private final Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mCancelToken = null;
                bindInternalLocked();
            }
        }
    };

    /**
     * Default constructor for {@link PersistentServiceConnection}.
     *
     * @param context     The context from which the service will be bound with.
     * @param executor    The executor for connection callbacks to be delivered on
     * @param transformer A {@link ServiceTransformer} for transforming
     */
    public PersistentServiceConnection(Context context,
            Executor executor,
            Handler handler,
            ServiceTransformer<T> transformer,
            Intent serviceIntent,
            int flags,
            int minConnectionDurationMs,
            int maxReconnectAttempts,
            int baseReconnectDelayMs) {
        this(context,
                executor,
                handler,
                transformer,
                serviceIntent,
                flags,
                minConnectionDurationMs,
                maxReconnectAttempts,
                baseReconnectDelayMs,
                new Injector());
    }

    @VisibleForTesting
    public PersistentServiceConnection(
            Context context,
            Executor executor,
            Handler handler,
            ServiceTransformer<T> transformer,
            Intent serviceIntent,
            int flags,
            int minConnectionDurationMs,
            int maxReconnectAttempts,
            int baseReconnectDelayMs,
            Injector injector) {
        super(context, executor, transformer, serviceIntent, flags);
        mHandler = handler;
        mMinConnectionDurationMs = minConnectionDurationMs;
        mMaxReconnectAttempts = maxReconnectAttempts;
        mBaseReconnectDelayMs = baseReconnectDelayMs;
        mInjector = injector;
    }

    /** {@inheritDoc} */
    @Override
    public boolean bind() {
        synchronized (mLock) {
            addCallback(mConnectionCallback);
            mReconnectAttempts = 0;
            return bindInternalLocked();
        }
    }

    @GuardedBy("mLock")
    private boolean bindInternalLocked() {
        return super.bind();
    }

    /** {@inheritDoc} */
    @Override
    public void unbind() {
        synchronized (mLock) {
            removeCallback(mConnectionCallback);
            cancelPendingConnectionAttemptLocked();
            super.unbind();
        }
    }

    @GuardedBy("mLock")
    private void cancelPendingConnectionAttemptLocked() {
        if (mCancelToken != null) {
            mHandler.removeCallbacksAndMessages(mCancelToken);
            mCancelToken = null;
        }
    }

    @GuardedBy("mLock")
    private void scheduleConnectionAttemptLocked() {
        cancelPendingConnectionAttemptLocked();

        if (mReconnectAttempts >= mMaxReconnectAttempts) {
            return;
        }

        final long reconnectDelayMs =
                (long) Math.scalb(mBaseReconnectDelayMs, mReconnectAttempts);

        mCancelToken = new Object();
        mHandler.postDelayed(mConnectRunnable, mCancelToken, reconnectDelayMs);
        mReconnectAttempts++;
    }

    /**
     * Injector for testing
     */
    @VisibleForTesting
    public static class Injector {
        /**
         * Returns milliseconds since boot, not counting time spent in deep sleep. Can be overridden
         * in tests with a fake clock.
         */
        public long uptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }
}
