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
import android.media.tv.ITvRemoteServiceInput;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;

/**
 * Maintains a connection to a tv remote provider service.
 */
final class TvRemoteProviderProxy implements ServiceConnection {
    private static final String TAG = "TvRemoteProvProxy";  // max. 23 chars
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);
    private static final boolean DEBUG_KEY = false;


    // This should match TvRemoteProvider.ACTION_TV_REMOTE_PROVIDER
    protected static final String SERVICE_INTERFACE =
            "com.android.media.tv.remoteprovider.TvRemoteProvider";
    private final Context mContext;
    private final ComponentName mComponentName;
    private final int mUserId;
    private final int mUid;

    /**
     * State guarded by mLock.
     *  This is the first lock in sequence for an incoming call.
     *  The second lock is always {@link TvRemoteService#mLock}
     *
     *  There are currently no methods that break this sequence.
     */
    private final Object mLock = new Object();

    private ProviderMethods mProviderMethods;
    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;

    TvRemoteProviderProxy(Context context, ProviderMethods provider,
                          ComponentName componentName, int userId, int uid) {
        mContext = context;
        mProviderMethods = provider;
        mComponentName = componentName;
        mUserId = userId;
        mUid = uid;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Proxy");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mBound=" + mBound);
        pw.println(prefix + "  mActiveConnection=" + mActiveConnection);
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
        synchronized (mLock) {
            if (mActiveConnection == null && mRunning) {
                unbind();
                bind();
            }
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
                if (!mBound && DEBUG) {
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
            disconnect();
            mContext.unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) {
            Slog.d(TAG, this + ": onServiceConnected()");
        }

        if (mBound) {
            disconnect();

            ITvRemoteProvider provider = ITvRemoteProvider.Stub.asInterface(service);
            if (provider != null) {
                Connection connection = new Connection(provider);
                if (connection.register()) {
                    synchronized (mLock) {
                        mActiveConnection = connection;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, this + ": Connected successfully.");
                    }
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": Registration failed");
                    }
                }
            } else {
                Slog.e(TAG, this + ": Service returned invalid remote-control provider binder");
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) Slog.d(TAG, this + ": Service disconnected");
        disconnect();
    }

    private void disconnect() {
        synchronized (mLock) {
            if (mActiveConnection != null) {
                mActiveConnection.dispose();
                mActiveConnection = null;
            }
        }
    }

    interface ProviderMethods {
        // InputBridge
        boolean openInputBridge(TvRemoteProviderProxy provider, IBinder token, String name,
                                int width, int height, int maxPointers);

        void closeInputBridge(TvRemoteProviderProxy provider, IBinder token);

        void clearInputBridge(TvRemoteProviderProxy provider, IBinder token);

        void sendKeyDown(TvRemoteProviderProxy provider, IBinder token, int keyCode);

        void sendKeyUp(TvRemoteProviderProxy provider, IBinder token, int keyCode);

        void sendPointerDown(TvRemoteProviderProxy provider, IBinder token, int pointerId, int x,
                             int y);

        void sendPointerUp(TvRemoteProviderProxy provider, IBinder token, int pointerId);

        void sendPointerSync(TvRemoteProviderProxy provider, IBinder token);
    }

    private final class Connection {
        private final ITvRemoteProvider mTvRemoteProvider;
        private final RemoteServiceInputProvider mServiceInputProvider;

        public Connection(ITvRemoteProvider provider) {
            mTvRemoteProvider = provider;
            mServiceInputProvider = new RemoteServiceInputProvider(this);
        }

        public boolean register() {
            if (DEBUG) Slog.d(TAG, "Connection::register()");
            try {
                mTvRemoteProvider.setRemoteServiceInputSink(mServiceInputProvider);
                return true;
            } catch (RemoteException ex) {
                dispose();
                return false;
            }
        }

        public void dispose() {
            if (DEBUG) Slog.d(TAG, "Connection::dispose()");
            mServiceInputProvider.dispose();
        }


        public void onInputBridgeConnected(IBinder token) {
            if (DEBUG) Slog.d(TAG, this + ": onInputBridgeConnected");
            try {
                mTvRemoteProvider.onInputBridgeConnected(token);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver onInputBridgeConnected. ", ex);
            }
        }

        void openInputBridge(final IBinder token, final String name, final int width,
                             final int height, final int maxPointers) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": openInputBridge," +
                                " token=" + token + ", name=" + name);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods.openInputBridge(TvRemoteProviderProxy.this, token,
                                                             name, width, height, maxPointers)) {
                            onInputBridgeConnected(token);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "openInputBridge, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void closeInputBridge(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": closeInputBridge," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.closeInputBridge(TvRemoteProviderProxy.this, token);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "closeInputBridge, Invalid connection or incorrect uid: " +
                                        Binder.getCallingUid());
                    }
                }
            }
        }

        void clearInputBridge(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": clearInputBridge," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.clearInputBridge(TvRemoteProviderProxy.this, token);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "clearInputBridge, Invalid connection or incorrect uid: " +
                                        Binder.getCallingUid());
                    }
                }
            }
        }

        void sendTimestamp(final IBinder token, final long timestamp) {
            if (DEBUG) {
                Slog.e(TAG, "sendTimestamp is deprecated, please remove all usages of this API.");
            }
        }

        void sendKeyDown(final IBinder token, final int keyCode) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendKeyDown," +
                                " token=" + token + ", keyCode=" + keyCode);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.sendKeyDown(TvRemoteProviderProxy.this, token, keyCode);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendKeyDown, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendKeyUp(final IBinder token, final int keyCode) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendKeyUp," +
                                " token=" + token + ", keyCode=" + keyCode);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.sendKeyUp(TvRemoteProviderProxy.this, token, keyCode);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendKeyUp, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerDown(final IBinder token, final int pointerId, final int x, final int y) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerDown," +
                                " token=" + token + ", pointerId=" + pointerId);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.sendPointerDown(TvRemoteProviderProxy.this, token,
                                pointerId, x, y);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerDown, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerUp(final IBinder token, final int pointerId) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerUp," +
                                " token=" + token + ", pointerId=" + pointerId);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        mProviderMethods.sendPointerUp(TvRemoteProviderProxy.this, token,
                                pointerId);
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerUp, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }

        void sendPointerSync(final IBinder token) {
            synchronized (mLock) {
                if (mActiveConnection == this && Binder.getCallingUid() == mUid) {
                    if (DEBUG_KEY) {
                        Slog.d(TAG, this + ": sendPointerSync," +
                                " token=" + token);
                    }
                    final long idToken = Binder.clearCallingIdentity();
                    try {
                        if (mProviderMethods != null) {
                            mProviderMethods.sendPointerSync(TvRemoteProviderProxy.this, token);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(idToken);
                    }
                } else {
                    if (DEBUG) {
                        Slog.w(TAG,
                                "sendPointerSync, Invalid connection or incorrect uid: " + Binder
                                        .getCallingUid());
                    }
                }
            }
        }
    }

    /**
     * Receives events from the connected provider.
     * <p>
     * This inner class is static and only retains a weak reference to the connection
     * to prevent the client from being leaked in case the service is holding an
     * active reference to the client's callback.
     * </p>
     */
    private static final class RemoteServiceInputProvider extends ITvRemoteServiceInput.Stub {
        private final WeakReference<Connection> mConnectionRef;

        public RemoteServiceInputProvider(Connection connection) {
            mConnectionRef = new WeakReference<Connection>(connection);
        }

        public void dispose() {
            // Terminate the connection.
            mConnectionRef.clear();
        }

        @Override
        public void openInputBridge(IBinder token, String name, int width,
                                    int height, int maxPointers) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.openInputBridge(token, name, width, height, maxPointers);
            }
        }

        @Override
        public void closeInputBridge(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.closeInputBridge(token);
            }
        }

        @Override
        public void clearInputBridge(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.clearInputBridge(token);
            }
        }

        @Override
        public void sendTimestamp(IBinder token, long timestamp) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendTimestamp(token, timestamp);
            }
        }

        @Override
        public void sendKeyDown(IBinder token, int keyCode) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendKeyDown(token, keyCode);
            }
        }

        @Override
        public void sendKeyUp(IBinder token, int keyCode) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendKeyUp(token, keyCode);
            }
        }

        @Override
        public void sendPointerDown(IBinder token, int pointerId, int x, int y)
                throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerDown(token, pointerId, x, y);
            }
        }

        @Override
        public void sendPointerUp(IBinder token, int pointerId) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerUp(token, pointerId);
            }
        }

        @Override
        public void sendPointerSync(IBinder token) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.sendPointerSync(token);
            }
        }
    }
}
