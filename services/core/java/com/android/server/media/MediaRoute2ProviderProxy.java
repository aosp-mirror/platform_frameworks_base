/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IMediaRoute2Provider;
import android.media.IMediaRoute2ProviderClient;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RouteSessionInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a connection to a particular media route provider service.
 */
final class MediaRoute2ProviderProxy extends MediaRoute2Provider implements ServiceConnection {
    private static final String TAG = "MR2ProviderProxy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final int mUserId;
    private final Handler mHandler;

    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    MediaRoute2ProviderProxy(@NonNull Context context, @NonNull ComponentName componentName,
            int userId) {
        super(componentName);
        mContext = Objects.requireNonNull(context, "Context must not be null.");
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
    }

    @Override
    public void requestCreateSession(String packageName, String routeId, String controlCategory,
            long requestId) {
        if (mConnectionReady) {
            mActiveConnection.requestCreateSession(packageName, routeId, controlCategory,
                    requestId);
            updateBinding();
        }
    }

    @Override
    public void releaseSession(int sessionId) {
        if (mConnectionReady) {
            mActiveConnection.releaseSession(sessionId);
            updateBinding();
        }
    }

    @Override
    public void selectRoute(int sessionId, MediaRoute2Info route) {
        if (mConnectionReady) {
            mActiveConnection.selectRoute(sessionId, route.getId());
        }
    }

    @Override
    public void deselectRoute(int sessionId, MediaRoute2Info route) {
        if (mConnectionReady) {
            mActiveConnection.deselectRoute(sessionId, route.getId());
        }
    }

    @Override
    public void transferToRoute(int sessionId, MediaRoute2Info route) {
        if (mConnectionReady) {
            mActiveConnection.transferToRoute(sessionId, route.getId());
        }
    }

    @Override
    public void sendControlRequest(MediaRoute2Info route, Intent request) {
        if (mConnectionReady) {
            mActiveConnection.sendControlRequest(route.getId(), request);
            updateBinding();
        }
    }

    @Override
    public void requestSetVolume(MediaRoute2Info route, int volume) {
        if (mConnectionReady) {
            mActiveConnection.requestSetVolume(route.getId(), volume);
            updateBinding();
        }
    }

    @Override
    public void requestUpdateVolume(MediaRoute2Info route, int delta) {
        if (mConnectionReady) {
            mActiveConnection.requestUpdateVolume(route.getId(), delta);
            updateBinding();
        }
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
        //TODO: Binding could be delayed until it's necessary.
        if (mRunning) {
            return true;
        }
        return false;
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(MediaRoute2ProviderService.SERVICE_INTERFACE);
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
            Slog.d(TAG, this + ": Connected");
        }

        if (mBound) {
            disconnect();

            IMediaRoute2Provider provider = IMediaRoute2Provider.Stub.asInterface(service);
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

    private void onProviderStateUpdated(Connection connection,
            MediaRoute2ProviderInfo providerInfo, List<RouteSessionInfo> sessionInfos) {
        if (mActiveConnection != connection) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, this + ": State changed ");
        }
        setAndNotifyProviderState(providerInfo, sessionInfos);
    }

    private void onSessionCreated(Connection connection, @Nullable RouteSessionInfo sessionInfo,
            long requestId) {
        if (mActiveConnection != connection) {
            return;
        }
        if (sessionInfo != null) {
            sessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                    .setProviderId(getUniqueId())
                    .build();
        }
        mCallback.onSessionCreated(this, sessionInfo, requestId);
    }

    private void onSessionInfoChanged(Connection connection, RouteSessionInfo sessionInfo) {
        if (mActiveConnection != connection) {
            return;
        }
        if (sessionInfo == null) {
            Slog.w(TAG, "onSessionInfoChanged: Ignoring null sessionInfo sent from "
                    + mComponentName);
            return;
        }
        mCallback.onSessionInfoChanged(this, sessionInfo);
    }

    private void disconnect() {
        if (mActiveConnection != null) {
            mConnectionReady = false;
            mActiveConnection.dispose();
            mActiveConnection = null;
            setAndNotifyProviderState(null, Collections.emptyList());
        }
    }

    @Override
    public String toString() {
        return "Service connection " + mComponentName.flattenToShortString();
    }

    private final class Connection implements DeathRecipient {
        private final IMediaRoute2Provider mProvider;
        private final ProviderClient mClient;

        Connection(IMediaRoute2Provider provider) {
            mProvider = provider;
            mClient = new ProviderClient(this);
        }

        public boolean register() {
            try {
                mProvider.asBinder().linkToDeath(this, 0);
                mProvider.setClient(mClient);
                mHandler.post(() -> onConnectionReady(Connection.this));
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            mProvider.asBinder().unlinkToDeath(this, 0);
            mClient.dispose();
        }

        public void requestCreateSession(String packageName, String routeId, String controlCategory,
                long requestId) {
            try {
                mProvider.requestCreateSession(packageName, routeId,
                        controlCategory, requestId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to create a session.", ex);
            }
        }

        public void releaseSession(int sessionId) {
            try {
                mProvider.releaseSession(sessionId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to release a session.", ex);
            }
        }

        public void selectRoute(int sessionId, String routeId) {
            try {
                mProvider.selectRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to select a route for a session.", ex);
            }
        }

        public void deselectRoute(int sessionId, String routeId) {
            try {
                mProvider.deselectRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to deselect a route from a session.", ex);
            }
        }

        public void transferToRoute(int sessionId, String routeId) {
            try {
                mProvider.transferToRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to transfer a session to a route.", ex);
            }
        }

        public void sendControlRequest(String routeId, Intent request) {
            try {
                mProvider.notifyControlRequestSent(routeId, request);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to send control request.", ex);
            }
        }

        public void requestSetVolume(String routeId, int volume) {
            try {
                mProvider.requestSetVolume(routeId, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to request set volume.", ex);
            }
        }

        public void requestUpdateVolume(String routeId, int delta) {
            try {
                mProvider.requestUpdateVolume(routeId, delta);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to deliver request to request update volume.", ex);
            }
        }

        @Override
        public void binderDied() {
            mHandler.post(() -> onConnectionDied(Connection.this));
        }

        void postProviderStateUpdated(MediaRoute2ProviderInfo providerInfo,
                List<RouteSessionInfo> sessionInfos) {
            mHandler.post(() -> onProviderStateUpdated(Connection.this,
                    providerInfo, sessionInfos));
        }

        void postSessionCreated(@Nullable RouteSessionInfo sessionInfo, long requestId) {
            mHandler.post(() -> onSessionCreated(Connection.this, sessionInfo,
                    requestId));
        }

        void postSessionInfoChanged(RouteSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionInfoChanged(Connection.this, sessionInfo));
        }
    }

    private static final class ProviderClient extends IMediaRoute2ProviderClient.Stub  {
        private final WeakReference<Connection> mConnectionRef;

        ProviderClient(Connection connection) {
            mConnectionRef = new WeakReference<>(connection);
        }

        public void dispose() {
            mConnectionRef.clear();
        }

        @Override
        public void updateState(MediaRoute2ProviderInfo providerInfo,
                List<RouteSessionInfo> sessionInfos) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postProviderStateUpdated(providerInfo, sessionInfos);
            }
        }

        @Override
        public void notifySessionCreated(@Nullable RouteSessionInfo sessionInfo, long requestId) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionCreated(sessionInfo, requestId);
            }
        }

        @Override
        public void notifySessionInfoChanged(RouteSessionInfo sessionInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionInfoChanged(sessionInfo);
            }
        }
    }
}
