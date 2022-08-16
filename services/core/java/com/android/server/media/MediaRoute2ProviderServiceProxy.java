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

import static android.media.MediaRoute2ProviderService.REQUEST_ID_NONE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

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
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a connection to a particular {@link MediaRoute2ProviderService}.
 */
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

    private boolean mIsManagerScanning;
    private RouteDiscoveryPreference mLastDiscoveryPreference = null;

    @GuardedBy("mLock")
    final List<RoutingSessionInfo> mReleasingSessions = new ArrayList<>();

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

    public void setManagerScanning(boolean managerScanning) {
        if (mIsManagerScanning != managerScanning) {
            mIsManagerScanning = managerScanning;
            updateBinding();
        }
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
        mLastDiscoveryPreference = discoveryPreference;
        if (mConnectionReady) {
            mActiveConnection.updateDiscoveryPreference(discoveryPreference);
        }
        updateBinding();
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

    @Override
    public void prepareReleaseSession(@NonNull String sessionId) {
        synchronized (mLock) {
            for (RoutingSessionInfo session : mSessionInfos) {
                if (TextUtils.equals(session.getId(), sessionId)) {
                    mSessionInfos.remove(session);
                    mReleasingSessions.add(session);
                    break;
                }
            }
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
        //TODO: When we are connecting to the service, calling this will unbind and bind again.
        // We'd better not unbind if we are connecting.
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
            // Bind when there is a discovery preference or an active route session.
            return (mLastDiscoveryPreference != null
                    && !mLastDiscoveryPreference.getPreferredFeatures().isEmpty())
                    || !getSessionInfos().isEmpty()
                    || mIsManagerScanning;
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
        unbind();
        if (shouldBind()) {
            bind();
        }
    }

    private void onConnectionReady(Connection connection) {
        if (mActiveConnection == connection) {
            mConnectionReady = true;
            if (mLastDiscoveryPreference != null) {
                updateDiscoveryPreference(mLastDiscoveryPreference);
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

    private void onProviderUpdated(Connection connection, MediaRoute2ProviderInfo providerInfo) {
        if (mActiveConnection != connection) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, this + ": updated");
        }
        setAndNotifyProviderState(providerInfo);
    }

    private void onSessionCreated(Connection connection, long requestId,
            RoutingSessionInfo newSession) {
        if (mActiveConnection != connection) {
            return;
        }

        if (newSession == null) {
            Slog.w(TAG, "onSessionCreated: Ignoring null session sent from " + mComponentName);
            return;
        }

        newSession = assignProviderIdForSession(newSession);
        String newSessionId = newSession.getId();

        synchronized (mLock) {
            if (mSessionInfos.stream()
                    .anyMatch(session -> TextUtils.equals(session.getId(), newSessionId))
                    || mReleasingSessions.stream()
                    .anyMatch(session -> TextUtils.equals(session.getId(), newSessionId))) {
                Slog.w(TAG, "onSessionCreated: Duplicate session already exists. Ignoring.");
                return;
            }
            mSessionInfos.add(newSession);
        }

        mCallback.onSessionCreated(this, requestId, newSession);
    }

    private int findSessionByIdLocked(RoutingSessionInfo session) {
        for (int i = 0; i < mSessionInfos.size(); i++) {
            if (TextUtils.equals(mSessionInfos.get(i).getId(), session.getId())) {
                return i;
            }
        }
        return -1;
    }


    private void onSessionsUpdated(Connection connection, List<RoutingSessionInfo> sessions) {
        if (mActiveConnection != connection) {
            return;
        }

        int targetIndex = 0;
        synchronized (mLock) {
            for (RoutingSessionInfo session : sessions) {
                if (session == null) continue;
                session = assignProviderIdForSession(session);

                int sourceIndex = findSessionByIdLocked(session);
                if (sourceIndex < 0) {
                    mSessionInfos.add(targetIndex++, session);
                    dispatchSessionCreated(REQUEST_ID_NONE, session);
                } else if (sourceIndex < targetIndex) {
                    Slog.w(TAG, "Ignoring duplicate session ID: " + session.getId());
                } else {
                    mSessionInfos.set(sourceIndex, session);
                    Collections.swap(mSessionInfos, sourceIndex, targetIndex++);
                    dispatchSessionUpdated(session);
                }
            }
            for (int i = mSessionInfos.size() - 1; i >= targetIndex; i--) {
                RoutingSessionInfo releasedSession = mSessionInfos.remove(i);
                dispatchSessionReleased(releasedSession);
            }
        }
    }

    private void onSessionReleased(Connection connection, RoutingSessionInfo releaedSession) {
        if (mActiveConnection != connection) {
            return;
        }
        if (releaedSession == null) {
            Slog.w(TAG, "onSessionReleased: Ignoring null session sent from " + mComponentName);
            return;
        }

        releaedSession = assignProviderIdForSession(releaedSession);

        boolean found = false;
        synchronized (mLock) {
            for (RoutingSessionInfo session : mSessionInfos) {
                if (TextUtils.equals(session.getId(), releaedSession.getId())) {
                    mSessionInfos.remove(session);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (RoutingSessionInfo session : mReleasingSessions) {
                    if (TextUtils.equals(session.getId(), releaedSession.getId())) {
                        mReleasingSessions.remove(session);
                        return;
                    }
                }
            }
        }

        if (!found) {
            Slog.w(TAG, "onSessionReleased: Matching session info not found");
            return;
        }

        mCallback.onSessionReleased(this, releaedSession);
    }

    private void dispatchSessionCreated(long requestId, RoutingSessionInfo session) {
        mHandler.sendMessage(
                obtainMessage(mCallback::onSessionCreated, this, requestId, session));
    }

    private void dispatchSessionUpdated(RoutingSessionInfo session) {
        mHandler.sendMessage(
                obtainMessage(mCallback::onSessionUpdated, this, session));
    }

    private void dispatchSessionReleased(RoutingSessionInfo session) {
        mHandler.sendMessage(
                obtainMessage(mCallback::onSessionReleased, this, session));
    }

    private RoutingSessionInfo assignProviderIdForSession(RoutingSessionInfo sessionInfo) {
        return new RoutingSessionInfo.Builder(sessionInfo)
                .setOwnerPackageName(mComponentName.getPackageName())
                .setProviderId(getUniqueId())
                .build();
    }

    private void onRequestFailed(Connection connection, long requestId, int reason) {
        if (mActiveConnection != connection) {
            return;
        }

        if (requestId == REQUEST_ID_NONE) {
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
                mReleasingSessions.clear();
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

        void postProviderUpdated(MediaRoute2ProviderInfo providerInfo) {
            mHandler.post(() -> onProviderUpdated(Connection.this, providerInfo));
        }

        void postSessionCreated(long requestId, RoutingSessionInfo sessionInfo) {
            mHandler.post(() -> onSessionCreated(Connection.this, requestId, sessionInfo));
        }

        void postSessionsUpdated(List<RoutingSessionInfo> sessionInfo) {
            mHandler.post(() -> onSessionsUpdated(Connection.this, sessionInfo));
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
        public void notifyProviderUpdated(MediaRoute2ProviderInfo providerInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postProviderUpdated(providerInfo);
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
        public void notifySessionsUpdated(List<RoutingSessionInfo> sessionInfo) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                connection.postSessionsUpdated(sessionInfo);
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
