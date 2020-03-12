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
import android.media.IMediaRoute2ProviderService;
import android.media.IMediaRoute2ProviderServiceCallback;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;

/**
 * Maintains a connection to a particular {@link MediaRoute2ProviderService}.
 */
// TODO: Need to revisit the bind/unbind/connect/disconnect logic in this class.
final class MediaRoute2ProviderServiceProxy extends MediaRoute2Provider
        implements ServiceConnection {
    private static final String TAG = "MR2ProviderSvcProxy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final int mUserId;
    private final Handler mHandler;

    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    private RouteDiscoveryPreference mPendingDiscoveryPreference = null;

    MediaRoute2ProviderServiceProxy(@NonNull Context context, @NonNull ComponentName componentName,
            int userId) {
        super(componentName);
        mContext = Objects.requireNonNull(context, "Context must not be null.");
        mUserId = userId;
        mHandler = new Handler(Looper.myLooper());
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
    public void requestCreateSession(long requestId, String packageName, String routeId,
            Bundle sessionHints) {
        if (mConnectionReady) {
            mActiveConnection.requestCreateSession(requestId, packageName, routeId, sessionHints);
            updateBinding();
        }
    }

    @Override
    public void releaseSession(long requestId, String sessionId) {
        if (mConnectionReady) {
            mActiveConnection.releaseSession(requestId, sessionId);
            updateBinding();
        }
    }

    @Override
    public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
        if (mConnectionReady) {
            mActiveConnection.updateDiscoveryPreference(discoveryPreference);
            updateBinding();
        } else {
            mPendingDiscoveryPreference = discoveryPreference;
        }
    }

    @Override
    public void selectRoute(long requestId, String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.selectRoute(requestId, sessionId, routeId);
        }
    }

    @Override
    public void deselectRoute(long requestId, String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.deselectRoute(requestId, sessionId, routeId);
        }
    }

    @Override
    public void transferToRoute(long requestId, String sessionId, String routeId) {
        if (mConnectionReady) {
            mActiveConnection.transferToRoute(requestId, sessionId, routeId);
        }
    }

    @Override
    public void setRouteVolume(long requestId, String routeId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setRouteVolume(requestId, routeId, volume);
            updateBinding();
        }
    }

    @Override
    public void setSessionVolume(long requestId, String sessionId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setSessionVolume(requestId, sessionId, volume);
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
            IMediaRoute2ProviderService serviceBinder =
                    IMediaRoute2ProviderService.Stub.asInterface(service);
            if (serviceBinder != null) {
                Connection connection = new Connection(serviceBinder);
                if (connection.register()) {
                    mActiveConnection = connection;
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, this + ": Registration failed");
                    }
                }
            } else {
                Slog.e(TAG, this + ": Service returned invalid binder");
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
            if (mPendingDiscoveryPreference != null) {
                updateDiscoveryPreference(mPendingDiscoveryPreference);
                mPendingDiscoveryPreference = null;
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

    private void onSessionCreated(Connection connection, long requestId,
            RoutingSessionInfo sessionInfo) {
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

        mCallback.onSessionCreated(this, requestId, sessionInfo);
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

    private void onRequestFailed(Connection connection, long requestId, int reason) {
        if (mActiveConnection != connection) {
            return;
        }

        if (requestId == MediaRoute2ProviderService.REQUEST_ID_NONE) {
            Slog.w(TAG, "onRequestFailed: Ignoring requestId REQUEST_ID_NONE");
            return;
        }

        mCallback.onRequestFailed(this, requestId, reason);
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
        private final IMediaRoute2ProviderService mService;
        private final ServiceCallbackStub mCallbackStub;

        Connection(IMediaRoute2ProviderService serviceBinder) {
            mService = serviceBinder;
            mCallbackStub = new ServiceCallbackStub(this);
        }

        public boolean register() {
            try {
                mService.asBinder().linkToDeath(this, 0);
                mService.setCallback(mCallbackStub);
                mHandler.post(() -> onConnectionReady(Connection.this));
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            mService.asBinder().unlinkToDeath(this, 0);
            mCallbackStub.dispose();
        }

        public void requestCreateSession(long requestId, String packageName, String routeId,
                Bundle sessionHints) {
            try {
                mService.requestCreateSession(requestId, packageName, routeId, sessionHints);
            } catch (RemoteException ex) {
                Slog.e(TAG, "requestCreateSession: Failed to deliver request.");
            }
        }

        public void releaseSession(long requestId, String sessionId) {
            try {
                mService.releaseSession(requestId, sessionId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "releaseSession: Failed to deliver request.");
            }
        }

        public void updateDiscoveryPreference(RouteDiscoveryPreference discoveryPreference) {
            try {
                mService.updateDiscoveryPreference(discoveryPreference);
            } catch (RemoteException ex) {
                Slog.e(TAG, "updateDiscoveryPreference: Failed to deliver request.");
            }
        }

        public void selectRoute(long requestId, String sessionId, String routeId) {
            try {
                mService.selectRoute(requestId, sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "selectRoute: Failed to deliver request.", ex);
            }
        }

        public void deselectRoute(long requestId, String sessionId, String routeId) {
            try {
                mService.deselectRoute(requestId, sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "deselectRoute: Failed to deliver request.", ex);
            }
        }

        public void transferToRoute(long requestId, String sessionId, String routeId) {
            try {
                mService.transferToRoute(requestId, sessionId, routeId);
            } catch (RemoteException ex) {
                Slog.e(TAG, "transferToRoute: Failed to deliver request.", ex);
            }
        }

        public void setRouteVolume(long requestId, String routeId, int volume) {
            try {
                mService.setRouteVolume(requestId, routeId, volume);
            } catch (RemoteException ex) {
                Slog.e(TAG, "setRouteVolume: Failed to deliver request.", ex);
            }
        }

        public void setSessionVolume(long requestId, String sessionId, int volume) {
            try {
                mService.setSessionVolume(requestId, sessionId, volume);
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

        void postSessionCreated(long requestId, RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionCreated(Connection.this, requestId, sessionInfo));
        }

        void postSessionUpdated(RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionUpdated(Connection.this, sessionInfo));
        }

        void postSessionReleased(RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionReleased(Connection.this, sessionInfo));
        }

        void postRequestFailed(long requestId, int reason) {
            mHandler.post(() -> onRequestFailed(Connection.this, requestId, reason));
        }
    }

    private static final class ServiceCallbackStub extends
            IMediaRoute2ProviderServiceCallback.Stub {
        private final WeakReference<Connection> mConnectionRef;

        ServiceCallbackStub(Connection connection) {
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
        public void notifySessionCreated(long requestId, RoutingSessionInfo sessionInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionCreated(requestId, sessionInfo);
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

        @Override
        public void notifyRequestFailed(long requestId, int reason) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postRequestFailed(requestId, reason);
            }
        }
    }
}
