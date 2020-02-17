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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IMediaRoute2Provider;
import android.media.IMediaRoute2ProviderClient;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Maintains a connection to a particular media route provider service.
 */
// TODO: Need to revisit the bind/unbind/connect/disconnect logic in this class.
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
    public void requestCreateSession(String packageName, String routeId, long requestId,
            Bundle sessionHints) {
        if (mConnectionReady) {
            mActiveConnection.requestCreateSession(
                    packageName, routeId, requestId, sessionHints);
            updateBinding();
        }
    }

    @Override
    public void releaseSession(String sessionId) {
        if (mConnectionReady) {
            mActiveConnection.releaseSession(sessionId);
            updateBinding();
        }
    }

    @Override
    public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
        if (mConnectionReady) {
            mActiveConnection.updateDiscoveryPreference(discoveryPreference);
            updateBinding();
        }
    }

    @Override
    public void selectRoute(String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.selectRoute(sessionId, routeId);
        }
    }

    @Override
    public void deselectRoute(String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.deselectRoute(sessionId, routeId);
        }
    }

    @Override
    public void transferToRoute(String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.transferToRoute(sessionId, routeId);
        }
    }

    @Override
    public void setRouteVolume(String routeId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setRouteVolume(routeId, volume);
            updateBinding();
        }
    }

    @Override
    public void setSessionVolume(String sessionId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setSessionVolume(sessionId, volume);
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

    @Override
    public void onBindingDied(ComponentName name) {
        if (DEBUG) {
            Slog.d(TAG, this + ": Service binding died");
        }
        // TODO: Investigate whether it tries to bind endlessly when the service is
        //       badly implemented.
        if (shouldBind()) {
            unbind();
            bind();
        }
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
            MediaRoute2ProviderInfo providerInfo) {
        if (mActiveConnection != connection) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, this + ": State changed ");
        }
        setAndNotifyProviderState(providerInfo);
    }

    private void onSessionCreated(Connection connection, RoutingSessionInfo sessionInfo,
            long requestId) {
        if (mActiveConnection != connection) {
            return;
        }

        if (sessionInfo == null) {
            Slog.w(TAG, "onSessionCreated: Ignoring null sessionInfo sent from " + mComponentName);
            return;
        }

        sessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .setProviderId(getUniqueId())
                .build();

        boolean duplicateSessionAlreadyExists = false;
        synchronized (mLock) {
            for (int i = 0; i < mSessionInfos.size(); i++) {
                if (mSessionInfos.get(i).getId().equals(sessionInfo.getId())) {
                    duplicateSessionAlreadyExists = true;
                    break;
                }
            }
            mSessionInfos.add(sessionInfo);
        }

        if (duplicateSessionAlreadyExists) {
            Slog.w(TAG, "onSessionCreated: Duplicate session already exists. Ignoring.");
            return;
        }

        mCallback.onSessionCreated(this, sessionInfo, requestId);
    }

    private void onSessionCreationFailed(Connection connection, long requestId) {
        if (mActiveConnection != connection) {
            return;
        }

        if (requestId == MediaRoute2ProviderService.REQUEST_ID_UNKNOWN) {
            Slog.w(TAG, "onSessionCreationFailed: Ignoring requestId REQUEST_ID_UNKNOWN");
            return;
        }

        mCallback.onSessionCreationFailed(this, requestId);
    }

    private void onSessionUpdated(Connection connection, RoutingSessionInfo sessionInfo) {
        if (mActiveConnection != connection) {
            return;
        }
        if (sessionInfo == null) {
            Slog.w(TAG, "onSessionUpdated: Ignoring null sessionInfo sent from "
                    + mComponentName);
            return;
        }

        sessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .setProviderId(getUniqueId())
                .build();

        boolean found = false;
        synchronized (mLock) {
            for (int i = 0; i < mSessionInfos.size(); i++) {
                if (mSessionInfos.get(i).getId().equals(sessionInfo.getId())) {
                    mSessionInfos.set(i, sessionInfo);
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            Slog.w(TAG, "onSessionUpdated: Matching session info not found");
            return;
        }

        mCallback.onSessionUpdated(this, sessionInfo);
    }

    private void onSessionReleased(Connection connection, RoutingSessionInfo sessionInfo) {
        if (mActiveConnection != connection) {
            return;
        }
        if (sessionInfo == null) {
            Slog.w(TAG, "onSessionReleased: Ignoring null sessionInfo sent from " + mComponentName);
            return;
        }

        sessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .setProviderId(getUniqueId())
                .build();

        boolean found = false;
        synchronized (mLock) {
            for (int i = 0; i < mSessionInfos.size(); i++) {
                if (mSessionInfos.get(i).getId().equals(sessionInfo.getId())) {
                    mSessionInfos.remove(i);
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            Slog.w(TAG, "onSessionReleased: Matching session info not found");
            return;
        }

        mCallback.onSessionReleased(this, sessionInfo);
    }

    private void disconnect() {
        if (mActiveConnection != null) {
            mConnectionReady = false;
            mActiveConnection.dispose();
            mActiveConnection = null;
            setAndNotifyProviderState(null);
            synchronized (mLock) {
                for (RoutingSessionInfo sessionInfo : mSessionInfos) {
                    mCallback.onSessionReleased(this, sessionInfo);
                }
                mSessionInfos.clear();
            }
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

        public void requestCreateSession(String packageName, String routeId, long requestId,
                Bundle sessionHints) {
            try {
                mProvider.requestCreateSession(packageName, routeId, requestId, sessionHints);
            } catch (RemoteException ex) {
                Slog.e(TAG, "requestCreateSession: Failed to deliver request.");
            }
        }

        public void releaseSession(String sessionId) {
            try {
                mProvider.releaseSession(sessionId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "releaseSession: Failed to deliver request.");
            }
        }

        public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
            try {
                mProvider.updateDiscoveryPreference(discoveryPreference);
            } catch (RemoteException ex) {
                Slog.e(TAG, "updateDiscoveryPreference: Failed to deliver request.");
            }
        }

        public void selectRoute(String sessionId, String routeId) {
            try {
                mProvider.selectRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "selectRoute: Failed to deliver request.", ex);
            }
        }

        public void deselectRoute(String sessionId, String routeId) {
            try {
                mProvider.deselectRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "deselectRoute: Failed to deliver request.", ex);
            }
        }

        public void transferToRoute(String sessionId, String routeId) {
            try {
                mProvider.transferToRoute(sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "transferToRoute: Failed to deliver request.", ex);
            }
        }

        public void setRouteVolume(String routeId, int volume) {
            try {
                mProvider.setRouteVolume(routeId, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "setRouteVolume: Failed to deliver request.", ex);
            }
        }

        public void setSessionVolume(String sessionId, int volume) {
            try {
                mProvider.setSessionVolume(sessionId, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "setSessionVolume: Failed to deliver request.", ex);
            }
        }

        @Override
        public void binderDied() {
            mHandler.post(() -> onConnectionDied(Connection.this));
        }

        void postProviderStateUpdated(MediaRoute2ProviderInfo providerInfo) {
            mHandler.post(() -> onProviderStateUpdated(Connection.this, providerInfo));
        }

        void postSessionCreated(RoutingSessionInfo sessionInfo, long requestId) {
            mHandler.post(() -> onSessionCreated(Connection.this, sessionInfo, requestId));
        }

        void postSessionCreationFailed(long requestId) {
            mHandler.post(() -> onSessionCreationFailed(Connection.this, requestId));
        }

        void postSessionUpdated(RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionUpdated(Connection.this, sessionInfo));
        }

        void postSessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionReleased(Connection.this, sessionInfo));
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
        public void updateState(MediaRoute2ProviderInfo providerInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postProviderStateUpdated(providerInfo);
            }
        }

        @Override
        public void notifySessionCreated(RoutingSessionInfo sessionInfo, long requestId) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionCreated(sessionInfo, requestId);
            }
        }

        @Override
        public void notifySessionCreationFailed(long requestId) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionCreationFailed(requestId);
            }
        }

        @Override
        public void notifySessionUpdated(RoutingSessionInfo sessionInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionUpdated(sessionInfo);
            }
        }

        @Override
        public void notifySessionReleased(RoutingSessionInfo sessionInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionReleased(sessionInfo);
            }
        }
    }
}
