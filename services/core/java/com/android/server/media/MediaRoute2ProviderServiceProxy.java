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
import android.media.MediaRoute2Info;
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
import com.android.media.flags.Flags;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maintains a connection to a particular {@link MediaRoute2ProviderService}. */
final class MediaRoute2ProviderServiceProxy extends MediaRoute2Provider {
    private static final String TAG = "MR2ProviderSvcProxy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final int mUserId;
    private final Handler mHandler;
    private final boolean mIsSelfScanOnlyProvider;
    private final ServiceConnection mServiceConnection = new ServiceConnectionImpl();

    // Connection state
    private boolean mRunning;
    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    private boolean mIsManagerScanning;
    private RouteDiscoveryPreference mLastDiscoveryPreference = null;
    private boolean mLastDiscoveryPreferenceIncludesThisPackage = false;

    @GuardedBy("mLock")
    final List<RoutingSessionInfo> mReleasingSessions = new ArrayList<>();

    MediaRoute2ProviderServiceProxy(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull ComponentName componentName,
            boolean isSelfScanOnlyProvider,
            int userId) {
        super(componentName);
        mContext = Objects.requireNonNull(context, "Context must not be null.");
        mIsSelfScanOnlyProvider = isSelfScanOnlyProvider;
        mUserId = userId;
        mHandler = new Handler(looper);
    }

    public void setManagerScanning(boolean managerScanning) {
        if (mIsManagerScanning != managerScanning) {
            mIsManagerScanning = managerScanning;
            updateBinding();
        }
    }

    @Override
    public void requestCreateSession(
            long requestId,
            String packageName,
            String routeOriginalId,
            Bundle sessionHints,
            @RoutingSessionInfo.TransferReason int transferReason,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        if (mConnectionReady) {
            mActiveConnection.requestCreateSession(
                    requestId, packageName, routeOriginalId, sessionHints);
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
    public void updateDiscoveryPreference(
            Set<String> activelyScanningPackages, RouteDiscoveryPreference discoveryPreference) {
        mLastDiscoveryPreference = discoveryPreference;
        mLastDiscoveryPreferenceIncludesThisPackage =
                activelyScanningPackages.contains(mComponentName.getPackageName());
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
    public void transferToRoute(
            long requestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName,
            String sessionOriginalId,
            String routeOriginalId,
            @RoutingSessionInfo.TransferReason int transferReason) {
        if (mConnectionReady) {
            mActiveConnection.transferToRoute(requestId, sessionOriginalId, routeOriginalId);
        }
    }

    @Override
    public void setRouteVolume(long requestId, String routeOriginalId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setRouteVolume(requestId, routeOriginalId, volume);
            updateBinding();
        }
    }

    @Override
    public void setSessionVolume(long requestId, String sessionOriginalId, int volume) {
        if (mConnectionReady) {
            mActiveConnection.setSessionVolume(requestId, sessionOriginalId, volume);
            updateBinding();
        }
    }

    @Override
    public void prepareReleaseSession(@NonNull String sessionUniqueId) {
        synchronized (mLock) {
            for (RoutingSessionInfo session : mSessionInfos) {
                if (TextUtils.equals(session.getId(), sessionUniqueId)) {
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

    public void start(boolean rebindIfDisconnected) {
        if (!mRunning) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Starting");
            }
            mRunning = true;
            if (!Flags.enablePreventionOfKeepAliveRouteProviders()) {
                updateBinding();
            }
        }
        if (rebindIfDisconnected && mActiveConnection == null && shouldBind()) {
            unbind();
            bind();
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

    private void updateBinding() {
        if (shouldBind()) {
            bind();
        } else {
            unbind();
        }
    }

    private boolean shouldBind() {
        if (!mRunning) {
            return false;
        }
        boolean bindDueToManagerScan =
                mIsManagerScanning && !Flags.enablePreventionOfManagerScansWhenNoAppsScan();
        if (!getSessionInfos().isEmpty() || bindDueToManagerScan) {
            // We bind if any manager is scanning (regardless of whether an app is scanning) to give
            // the opportunity for providers to publish routing sessions that were established
            // directly between the app and the provider (typically via AndroidX MediaRouter). See
            // b/176774510#comment20 for more information.
            return true;
        }
        boolean anAppIsScanning =
                mLastDiscoveryPreference != null
                        && !mLastDiscoveryPreference.getPreferredFeatures().isEmpty();
        return anAppIsScanning
                && (mLastDiscoveryPreferenceIncludesThisPackage || !mIsSelfScanOnlyProvider);
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            Intent service = new Intent(MediaRoute2ProviderService.SERVICE_INTERFACE);
            service.setComponent(mComponentName);
            try {
                mBound =
                        mContext.bindServiceAsUser(
                                service,
                                mServiceConnection,
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
            mContext.unbindService(mServiceConnection);
        }
    }

    private void onServiceConnectedInternal(IBinder service) {
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

    private void onServiceDisconnectedInternal() {
        if (DEBUG) {
            Slog.d(TAG, this + ": Service disconnected");
        }
        disconnect();
    }

    private void onBindingDiedInternal(ComponentName name) {
        unbind();
        if (Flags.enablePreventionOfKeepAliveRouteProviders()) {
            Slog.w(
                    TAG,
                    TextUtils.formatSimple(
                            "Route provider service (%s) binding died, but we did not rebind.",
                            name.toString()));
        } else if (shouldBind()) {
            Slog.w(
                    TAG,
                    TextUtils.formatSimple(
                            "Rebound to provider service (%s) after binding died.",
                            name.toString()));
            bind();
        }
    }

    private void onConnectionReady(Connection connection) {
        if (mActiveConnection == connection) {
            mConnectionReady = true;
            if (mLastDiscoveryPreference != null) {
                updateDiscoveryPreference(
                        mLastDiscoveryPreferenceIncludesThisPackage
                                ? Set.of(mComponentName.getPackageName())
                                : Set.of(),
                        mLastDiscoveryPreference);
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

    private void onSessionReleased(Connection connection, RoutingSessionInfo releasedSession) {
        if (mActiveConnection != connection) {
            return;
        }
        if (releasedSession == null) {
            Slog.w(TAG, "onSessionReleased: Ignoring null session sent from " + mComponentName);
            return;
        }

        releasedSession = assignProviderIdForSession(releasedSession);

        boolean found = false;
        synchronized (mLock) {
            for (RoutingSessionInfo session : mSessionInfos) {
                if (TextUtils.equals(session.getId(), releasedSession.getId())) {
                    mSessionInfos.remove(session);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (RoutingSessionInfo session : mReleasingSessions) {
                    if (TextUtils.equals(session.getId(), releasedSession.getId())) {
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

        mCallback.onSessionReleased(this, releasedSession);
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
    protected String getDebugString() {
        return TextUtils.formatSimple(
                "ProviderServiceProxy - package: %s, bound: %b, connection (active:%b, ready:%b)",
                mComponentName.getPackageName(),
                mBound,
                mActiveConnection != null,
                mConnectionReady);
    }

    // All methods in this class are called on the main thread.
    private final class ServiceConnectionImpl implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            onServiceConnectedInternal(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            onServiceDisconnectedInternal();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onBindingDiedInternal(name);
        }
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
        public void notifyProviderUpdated(@NonNull MediaRoute2ProviderInfo providerInfo) {
            Objects.requireNonNull(providerInfo, "providerInfo must not be null");

            for (MediaRoute2Info route : providerInfo.getRoutes()) {
                if (route.isSystemRoute()) {
                    throw new SecurityException(
                            "Only the system is allowed to publish system routes. "
                                    + "Disallowed route: "
                                    + route);
                }

                if (route.getSuitabilityStatus()
                        == MediaRoute2Info.SUITABILITY_STATUS_NOT_SUITABLE_FOR_TRANSFER) {
                    throw new SecurityException(
                            "Only the system is allowed to set not suitable for transfer status. "
                                    + "Disallowed route: "
                                    + route);
                }
            }

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
