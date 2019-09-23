/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.tv.ITvRemoteProvider;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Maintains a connection to a tv remote provider service.
 */
final class TvRemoteProviderProxy implements ServiceConnection {
    private static final String TAG = "TvRemoteProviderProxy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);


    // This should match TvRemoteProvider.ACTION_TV_REMOTE_PROVIDER
    protected static final String SERVICE_INTERFACE =
            "com.android.media.tv.remoteprovider.TvRemoteProvider";
    private final Context mContext;
    private final Object mLock;
    private final ComponentName mComponentName;
    private final int mUserId;
    private final int mUid;

    // State changes happen only in the main thread, hence no lock is needed
    private boolean mRunning;
    private boolean mBound;
    private boolean mConnected;

    TvRemoteProviderProxy(Context context, Object lock,
                          ComponentName componentName, int userId, int uid) {
        mContext = context;
        mLock = lock;
        mComponentName = componentName;
        mUserId = userId;
        mUid = uid;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Proxy");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mBound=" + mBound);
        pw.println(prefix + "  mConnected=" + mConnected);
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public void start() {
        if (!mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Starting");
            }

            mRunning = true;
            bind();
        }
    }

    public void stop() {
        if (mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Stopping");
            }

            mRunning = false;
            unbind();
        }
    }

    public void rebindIfDisconnected() {
        if (mRunning && !mConnected) {
            unbind();
            bind();
        }
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(SERVICE_INTERFACE);
            service.setComponent(mComponentName);
            try {
                mBound = mContext.bindServiceAsUser(service, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(mUserId));
                if (DEBUG && !mBound) {
                    Slog.d(TAG, this + ": Bind failed");
                }
            } catch (SecurityException ex) {
                if (DEBUG) {
                    Slog.d(TAG, this + ": Bind failed", ex);
                }
            }
        }
    }

    private void unbind() {
        if (mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Unbinding");
            }

            mBound = false;
            mContext.unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) {
            Slog.d(TAG, this + ": onServiceConnected()");
        }

        mConnected = true;

        final ITvRemoteProvider provider = ITvRemoteProvider.Stub.asInterface(service);
        if (provider == null) {
            Slog.e(TAG, this + ": Invalid binder");
            return;
        }

        try {
            provider.setRemoteServiceInputSink(new TvRemoteServiceInput(mLock, provider));
        } catch (RemoteException e) {
            Slog.e(TAG, this + ": Failed remote call to setRemoteServiceInputSink");
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mConnected = false;

        if (DEBUG) {
            Slog.d(TAG, this + ": onServiceDisconnected()");
        }
    }
}
