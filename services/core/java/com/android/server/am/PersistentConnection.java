/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.am;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Connects to a given service component on a given user.
 *
 * - Call {@link #connect()} to create a connection.
 * - Call {@link #disconnect()} to disconnect.  Make sure to disconnect when the user stops.
 *
 * Add onConnected/onDisconnected callbacks as needed.
 */
public abstract class PersistentConnection<T> {
    private final Object mLock = new Object();

    private final String mTag;
    private final Context mContext;
    private final Handler mHandler;
    private final int mUserId;
    private final ComponentName mComponentName;

    @GuardedBy("mLock")
    private boolean mStarted;

    @GuardedBy("mLock")
    private boolean mIsConnected;

    @GuardedBy("mLock")
    private T mService;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                Slog.i(mTag, "Connected: " + mComponentName.flattenToShortString()
                        + " u" + mUserId);

                mIsConnected = true;
                mService = asInterface(service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Slog.i(mTag, "Disconnected: " + mComponentName.flattenToShortString()
                        + " u" + mUserId);

                cleanUpConnectionLocked();
            }
        }
    };

    public PersistentConnection(@NonNull String tag, @NonNull Context context,
            @NonNull Handler handler, int userId, @NonNull ComponentName componentName) {
        mTag = tag;
        mContext = context;
        mHandler = handler;
        mUserId = userId;
        mComponentName = componentName;
    }

    public final ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * @return whether connected.
     */
    public final boolean isConnected() {
        synchronized (mLock) {
            return mIsConnected;
        }
    }

    /**
     * @return the service binder interface.
     */
    public final T getServiceBinder() {
        synchronized (mLock) {
            return mService;
        }
    }

    /**
     * Connects to the service.
     */
    public final void connect() {
        synchronized (mLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;

            final Intent service = new Intent().setComponent(mComponentName);

            final boolean success = mContext.bindServiceAsUser(service, mServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    mHandler, UserHandle.of(mUserId));

            if (!success) {
                Slog.e(mTag, "Binding: " + service.getComponent() + " u" + mUserId
                        + " failed.");
            }
        }
    }

    private void cleanUpConnectionLocked() {
        mIsConnected = false;
        mService = null;
    }

    /**
     * Disconnect from the service.
     */
    public final void disconnect() {
        synchronized (mLock) {
            if (!mStarted) {
                return;
            }
            Slog.i(mTag, "Stopping: " + mComponentName.flattenToShortString() + " u" + mUserId);
            mStarted = false;
            mContext.unbindService(mServiceConnection);

            cleanUpConnectionLocked();
        }
    }

    /** Must be implemented by a subclass to convert an {@link IBinder} to a stub. */
    protected abstract T asInterface(IBinder binder);

    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            pw.print(prefix);
            pw.print(mComponentName.flattenToShortString());
            pw.print(mStarted ? "  [started]" : "  [not started]");
            pw.print(mIsConnected ? "  [connected]" : "  [not connected]");
            pw.println();
        }
    }
}
