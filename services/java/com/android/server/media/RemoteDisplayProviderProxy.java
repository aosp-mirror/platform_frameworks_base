/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IRemoteDisplayCallback;
import android.media.IRemoteDisplayProvider;
import android.media.RemoteDisplayState;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.IBinder.DeathRecipient;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Maintains a connection to a particular remote display provider service.
 */
final class RemoteDisplayProviderProxy implements ServiceConnection {
    private static final String TAG = "RemoteDisplayProvider";  // max. 23 chars
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final ComponentName mComponentName;
    private final int mUserId;
    private final Handler mHandler;

    private Callback mDisplayStateCallback;

    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    // Logical state
    private int mDiscoveryMode;
    private String mSelectedDisplayId;
    private RemoteDisplayState mDisplayState;
    private boolean mScheduledDisplayStateChangedCallback;

    public RemoteDisplayProviderProxy(Context context, ComponentName componentName,
            int userId) {
        mContext = context;
        mComponentName = componentName;
        mUserId = userId;
        mHandler = new Handler();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Proxy");
        pw.println(prefix + "  mUserId=" + mUserId);
        pw.println(prefix + "  mRunning=" + mRunning);
        pw.println(prefix + "  mBound=" + mBound);
        pw.println(prefix + "  mActiveConnection=" + mActiveConnection);
        pw.println(prefix + "  mConnectionReady=" + mConnectionReady);
        pw.println(prefix + "  mDiscoveryMode=" + mDiscoveryMode);
        pw.println(prefix + "  mSelectedDisplayId=" + mSelectedDisplayId);
        pw.println(prefix + "  mDisplayState=" + mDisplayState);
    }

    public void setCallback(Callback callback) {
        mDisplayStateCallback = callback;
    }

    public RemoteDisplayState getDisplayState() {
        return mDisplayState;
    }

    public void setDiscoveryMode(int mode) {
        if (mDiscoveryMode != mode) {
            mDiscoveryMode = mode;
            if (mConnectionReady) {
                mActiveConnection.setDiscoveryMode(mode);
            }
            updateBinding();
        }
    }

    public void setSelectedDisplay(String id) {
        if (!Objects.equals(mSelectedDisplayId, id)) {
            if (mConnectionReady && mSelectedDisplayId != null) {
                mActiveConnection.disconnect(mSelectedDisplayId);
            }
            mSelectedDisplayId = id;
            if (mConnectionReady && id != null) {
                mActiveConnection.connect(id);
            }
            updateBinding();
        }
    }

    public void setDisplayVolume(int volume) {
        if (mConnectionReady && mSelectedDisplayId != null) {
            mActiveConnection.setVolume(mSelectedDisplayId, volume);
        }
    }

    public void adjustDisplayVolume(int delta) {
        if (mConnectionReady && mSelectedDisplayId != null) {
            mActiveConnection.adjustVolume(mSelectedDisplayId, delta);
        }
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public String getFlattenedComponentName() {
        return mComponentName.flattenToShortString();
    }

    public void start() {
        if (!mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Starting");
            }

            mRunning = true;
            updateBinding();
        }
    }

    public void stop() {
        if (mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Stopping");
            }

            mRunning = false;
            updateBinding();
        }
    }

    public void rebindIfDisconnected() {
        if (mActiveConnection == null && shouldBind()) {
            unbind();
            bind();
        }
    }

    private void updateBinding() {
        if (shouldBind()) {
            bind();
        } else {
            unbind();
        }
    }

    private boolean shouldBind() {
        if (mRunning) {
            // Bind whenever there is a discovery request or selected display.
            if (mDiscoveryMode != RemoteDisplayState.DISCOVERY_MODE_NONE
                    || mSelectedDisplayId != null) {
                return true;
            }
        }
        return false;
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(RemoteDisplayState.SERVICE_INTERFACE);
            service.setComponent(mComponentName);
            try {
                mBound = mContext.bindServiceAsUser(service, this, Context.BIND_AUTO_CREATE,
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
            Slog.d(TAG, this + ": Connected");
        }

        if (mBound) {
            disconnect();

            IRemoteDisplayProvider provider = IRemoteDisplayProvider.Stub.asInterface(service);
            if (provider != null) {
                Connection connection = new Connection(provider);
                if (connection.register()) {
                    mActiveConnection = connection;
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": Registration failed");
                    }
                }
            } else {
                Slog.e(TAG, this + ": Service returned invalid remote display provider binder");
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) {
            Slog.d(TAG, this + ": Service disconnected");
        }
        disconnect();
    }

    private void onConnectionReady(Connection connection) {
        if (mActiveConnection == connection) {
            mConnectionReady = true;

            if (mDiscoveryMode != RemoteDisplayState.DISCOVERY_MODE_NONE) {
                mActiveConnection.setDiscoveryMode(mDiscoveryMode);
            }
            if (mSelectedDisplayId != null) {
                mActiveConnection.connect(mSelectedDisplayId);
            }
        }
    }

    private void onConnectionDied(Connection connection) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Service connection died");
            }
            disconnect();
        }
    }

    private void onDisplayStateChanged(Connection connection, RemoteDisplayState state) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Slog.d(TAG, this + ": State changed, state=" + state);
            }
            setDisplayState(state);
        }
    }

    private void disconnect() {
        if (mActiveConnection != null) {
            if (mSelectedDisplayId != null) {
                mActiveConnection.disconnect(mSelectedDisplayId);
            }
            mConnectionReady = false;
            mActiveConnection.dispose();
            mActiveConnection = null;
            setDisplayState(null);
        }
    }

    private void setDisplayState(RemoteDisplayState state) {
        if (!Objects.equals(mDisplayState, state)) {
            mDisplayState = state;
            if (!mScheduledDisplayStateChangedCallback) {
                mScheduledDisplayStateChangedCallback = true;
                mHandler.post(mDisplayStateChanged);
            }
        }
    }

    @Override
    public String toString() {
        return "Service connection " + mComponentName.flattenToShortString();
    }

    private final Runnable mDisplayStateChanged = new Runnable() {
        @Override
        public void run() {
            mScheduledDisplayStateChangedCallback = false;
            if (mDisplayStateCallback != null) {
                mDisplayStateCallback.onDisplayStateChanged(
                        RemoteDisplayProviderProxy.this, mDisplayState);
            }
        }
    };

    public interface Callback {
        void onDisplayStateChanged(RemoteDisplayProviderProxy provider, RemoteDisplayState state);
    }

    private final class Connection implements DeathRecipient {
        private final IRemoteDisplayProvider mProvider;
        private final ProviderCallback mCallback;

        public Connection(IRemoteDisplayProvider provider) {
            mProvider = provider;
            mCallback = new ProviderCallback(this);
        }

        public boolean register() {
            try {
                mProvider.asBinder().linkToDeath(this, 0);
                mProvider.setCallback(mCallback);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onConnectionReady(Connection.this);
                    }
                });
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            mProvider.asBinder().unlinkToDeath(this, 0);
            mCallback.dispose();
        }

        public void setDiscoveryMode(int mode) {
            try {
                mProvider.setDiscoveryMode(mode);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to set discovery mode.", ex);
            }
        }

        public void connect(String id) {
            try {
                mProvider.connect(id);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to connect to display.", ex);
            }
        }

        public void disconnect(String id) {
            try {
                mProvider.disconnect(id);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to disconnect from display.", ex);
            }
        }

        public void setVolume(String id, int volume) {
            try {
                mProvider.setVolume(id, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to set display volume.", ex);
            }
        }

        public void adjustVolume(String id, int volume) {
            try {
                mProvider.adjustVolume(id, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to adjust display volume.", ex);
            }
        }

        @Override
        public void binderDied() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onConnectionDied(Connection.this);
                }
            });
        }

        void postStateChanged(final RemoteDisplayState state) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    onDisplayStateChanged(Connection.this, state);
                }
            });
        }
    }

    /**
     * Receives callbacks from the service.
     * <p>
     * This inner class is static and only retains a weak reference to the connection
     * to prevent the client from being leaked in case the service is holding an
     * active reference to the client's callback.
     * </p>
     */
    private static final class ProviderCallback extends IRemoteDisplayCallback.Stub {
        private final WeakReference<Connection> mConnectionRef;

        public ProviderCallback(Connection connection) {
            mConnectionRef = new WeakReference<Connection>(connection);
        }

        public void dispose() {
            mConnectionRef.clear();
        }

        @Override
        public void onStateChanged(RemoteDisplayState state) throws RemoteException {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postStateChanged(state);
            }
        }
    }
}
