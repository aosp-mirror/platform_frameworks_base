/*
 * Copyright 2024 The Android Open Source Project
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

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.MediaRoute2ProviderService.Reason;
import android.media.MediaRouter2Utils;
import android.media.RoutingSessionInfo;
import android.os.Binder;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.media.MediaRoute2ProviderServiceProxy.SystemMediaSessionCallback;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Extends {@link SystemMediaRoute2Provider} by adding system routes provided by {@link
 * MediaRoute2ProviderService provider services}.
 *
 * <p>System routes are those which can handle the system audio and/or video.
 */
/* package */ class SystemMediaRoute2Provider2 extends SystemMediaRoute2Provider {

    private static final String ROUTE_ID_PREFIX_SYSTEM = "SYSTEM";
    private static final String ROUTE_ID_SYSTEM_SEPARATOR = ".";

    private final PackageManager mPackageManager;

    @GuardedBy("mLock")
    private MediaRoute2ProviderInfo mLastSystemProviderInfo;

    @GuardedBy("mLock")
    private final Map<String, ProviderProxyRecord> mProxyRecords = new ArrayMap<>();

    /**
     * Maps package names to corresponding sessions maintained by {@link MediaRoute2ProviderService
     * provider services}.
     */
    @GuardedBy("mLock")
    private final Map<String, SystemMediaSessionRecord> mPackageNameToSessionRecord =
            new ArrayMap<>();

    /**
     * Maps route {@link MediaRoute2Info#getOriginalId original ids} to the id of the {@link
     * MediaRoute2ProviderService provider service} that manages the corresponding route.
     */
    @GuardedBy("mLock")
    private final Map<String, String> mOriginalRouteIdToProviderId = new ArrayMap<>();

    /** Maps request ids to pending session creation callbacks. */
    @GuardedBy("mLock")
    private final LongSparseArray<PendingSessionCreationCallbackImpl> mPendingSessionCreations =
            new LongSparseArray<>();

    private static final ComponentName COMPONENT_NAME =
            new ComponentName(
                    SystemMediaRoute2Provider2.class.getPackage().getName(),
                    SystemMediaRoute2Provider2.class.getName());

    public static SystemMediaRoute2Provider2 create(
            Context context, UserHandle user, Looper looper) {
        var instance = new SystemMediaRoute2Provider2(context, user, looper);
        instance.updateProviderState();
        instance.updateSessionInfosIfNeeded();
        return instance;
    }

    private SystemMediaRoute2Provider2(Context context, UserHandle user, Looper looper) {
        super(context, COMPONENT_NAME, user, looper);
        mPackageManager = context.getPackageManager();
    }

    @Override
    public void transferToRoute(
            long requestId,
            @NonNull UserHandle clientUserHandle,
            @NonNull String clientPackageName,
            String sessionOriginalId,
            String routeOriginalId,
            int transferReason) {
        synchronized (mLock) {
            var targetProviderProxyId = mOriginalRouteIdToProviderId.get(routeOriginalId);
            var targetProviderProxyRecord = mProxyRecords.get(targetProviderProxyId);
            // Holds the target route, if it's managed by a provider service. Holds null otherwise.
            var serviceTargetRoute =
                    targetProviderProxyRecord != null
                            ? targetProviderProxyRecord.getRouteByOriginalId(routeOriginalId)
                            : null;
            var existingSessionRecord = mPackageNameToSessionRecord.get(clientPackageName);
            if (existingSessionRecord != null) {
                var existingSession = existingSessionRecord.mSourceSessionInfo;
                if (targetProviderProxyId != null
                        && TextUtils.equals(
                                targetProviderProxyId, existingSession.getProviderId())) {
                    // The currently selected route and target route both belong to the same
                    // provider. We tell the provider to handle the transfer.
                    targetProviderProxyRecord.requestTransfer(
                            existingSession.getOriginalId(), serviceTargetRoute);
                } else {
                    // The target route is handled by a provider other than the target one. We need
                    // to release the existing session.
                    var currentProxyRecord = existingSessionRecord.getProxyRecord();
                    if (currentProxyRecord != null) {
                        currentProxyRecord.releaseSession(
                                requestId, existingSession.getOriginalId());
                        existingSessionRecord.removeSelfFromSessionMap();
                    }
                }
            }

            if (serviceTargetRoute != null) {
                boolean isGlobalSession = TextUtils.isEmpty(clientPackageName);
                int uid;
                if (isGlobalSession) {
                    uid = Process.INVALID_UID;
                } else {
                    uid = fetchUid(clientPackageName, clientUserHandle);
                    if (uid == Process.INVALID_UID) {
                        throw new IllegalArgumentException(
                                "Cannot resolve transfer for "
                                        + clientPackageName
                                        + " and "
                                        + clientUserHandle);
                    }
                }
                var pendingCreationCallback =
                        new PendingSessionCreationCallbackImpl(
                                targetProviderProxyId, requestId, clientPackageName);
                mPendingSessionCreations.put(requestId, pendingCreationCallback);
                targetProviderProxyRecord.requestCreateSystemMediaSession(
                        requestId,
                        uid,
                        clientPackageName,
                        routeOriginalId,
                        pendingCreationCallback);
            } else {
                // The target route is not provided by any of the services. Assume it's a system
                // provided route.
                super.transferToRoute(
                        requestId,
                        clientUserHandle,
                        clientPackageName,
                        sessionOriginalId,
                        routeOriginalId,
                        transferReason);
            }
        }
    }

    @Nullable
    @Override
    public RoutingSessionInfo getSessionForPackage(String packageName) {
        synchronized (mLock) {
            var systemSession = super.getSessionForPackage(packageName);
            if (systemSession == null) {
                return null;
            }
            var overridingSession = mPackageNameToSessionRecord.get(packageName);
            if (overridingSession != null) {
                var builder =
                        new RoutingSessionInfo.Builder(overridingSession.mTranslatedSessionInfo)
                                .setProviderId(mUniqueId)
                                .setSystemSession(true);
                for (var systemRoute : mLastSystemProviderInfo.getRoutes()) {
                    builder.addTransferableRoute(systemRoute.getOriginalId());
                }
                return builder.build();
            } else {
                return systemSession;
            }
        }
    }

    /**
     * Returns the uid that corresponds to the given name and user handle, or {@link
     * Process#INVALID_UID} if a uid couldn't be found.
     */
    @SuppressLint("MissingPermission")
    // We clear the calling identity before calling the package manager, and we are running on the
    // system_server.
    private int fetchUid(String clientPackageName, UserHandle clientUserHandle) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mPackageManager.getApplicationInfoAsUser(
                            clientPackageName, /* flags= */ 0, clientUserHandle)
                    .uid;
        } catch (PackageManager.NameNotFoundException e) {
            return Process.INVALID_UID;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    protected void onSystemSessionInfoUpdated() {
        updateSessionInfo();
    }

    @Override
    public void updateSystemMediaRoutesFromProxy(MediaRoute2ProviderServiceProxy serviceProxy) {
        var proxyRecord = ProviderProxyRecord.createFor(serviceProxy);
        synchronized (mLock) {
            if (proxyRecord == null) {
                mProxyRecords.remove(serviceProxy.mUniqueId);
            } else {
                mProxyRecords.put(serviceProxy.mUniqueId, proxyRecord);
            }
            updateProviderInfo();
        }
        updateSessionInfo();
        notifyProviderState();
        notifySessionInfoUpdated();
    }

    @Override
    public void onSystemProviderRoutesChanged(MediaRoute2ProviderInfo providerInfo) {
        synchronized (mLock) {
            mLastSystemProviderInfo = providerInfo;
            updateProviderInfo();
        }
        updateSessionInfo();
        notifySessionInfoUpdated();
    }

    /**
     * Updates the {@link #mSessionInfos} by expanding the {@link SystemMediaRoute2Provider} session
     * with information from the {@link MediaRoute2ProviderService provider services}.
     */
    private void updateSessionInfo() {
        synchronized (mLock) {
            var systemSessionInfo = mSystemSessionInfo;
            if (systemSessionInfo == null) {
                // The system session info hasn't been initialized yet. Do nothing.
                return;
            }
            var builder = new RoutingSessionInfo.Builder(systemSessionInfo);
            mProxyRecords.values().stream()
                    .flatMap(ProviderProxyRecord::getRoutesStream)
                    .map(MediaRoute2Info::getOriginalId)
                    .forEach(builder::addTransferableRoute);
            mSessionInfos.clear();
            mSessionInfos.add(builder.build());
            for (var sessionRecords : mPackageNameToSessionRecord.values()) {
                mSessionInfos.add(sessionRecords.mTranslatedSessionInfo);
            }
        }
    }

    /**
     * Returns a new a provider info that includes all routes from the system provider {@link
     * SystemMediaRoute2Provider}, along with system routes from {@link MediaRoute2ProviderService
     * provider services}.
     */
    @GuardedBy("mLock")
    private void updateProviderInfo() {
        MediaRoute2ProviderInfo.Builder builder =
                new MediaRoute2ProviderInfo.Builder(mLastSystemProviderInfo);
        mOriginalRouteIdToProviderId.clear();
        for (var proxyRecord : mProxyRecords.values()) {
            String proxyId = proxyRecord.mProxy.mUniqueId;
            proxyRecord
                    .getRoutesStream()
                    .forEach(
                            route -> {
                                builder.addRoute(route);
                                mOriginalRouteIdToProviderId.put(route.getOriginalId(), proxyId);
                            });
        }
        setProviderState(builder.build());
    }

    /**
     * Equivalent to {@link #asSystemRouteId}, except it takes a unique route id instead of a
     * original id.
     */
    private static String uniqueIdAsSystemRouteId(String providerId, String uniqueRouteId) {
        return asSystemRouteId(providerId, MediaRouter2Utils.getOriginalId(uniqueRouteId));
    }

    /**
     * Returns a unique {@link MediaRoute2Info#getOriginalId() original id} for this provider to
     * publish system media routes from {@link MediaRoute2ProviderService provider services}.
     *
     * <p>This provider will publish system media routes as part of the system routing session.
     * However, said routes may also support {@link MediaRoute2Info#FLAG_ROUTING_TYPE_REMOTE remote
     * routing}, meaning we cannot use the same id, or there would be an id collision. As a result,
     * we derive a {@link MediaRoute2Info#getOriginalId original id} that is unique among all
     * original route ids used by this provider.
     */
    private static String asSystemRouteId(String providerId, String originalRouteId) {
        return ROUTE_ID_PREFIX_SYSTEM
                + ROUTE_ID_SYSTEM_SEPARATOR
                + providerId
                + ROUTE_ID_SYSTEM_SEPARATOR
                + originalRouteId;
    }

    /**
     * Holds information about {@link MediaRoute2ProviderService provider services} registered in
     * the system.
     *
     * @param mProxy The corresponding {@link MediaRoute2ProviderServiceProxy}.
     * @param mSystemMediaRoutes The last snapshot of routes from the service that support system
     *     media routing, as defined by {@link MediaRoute2Info#supportsSystemMediaRouting()}.
     * @param mNewOriginalIdToSourceOriginalIdMap Maps the {@link #mSystemMediaRoutes} ids to the
     *     original ids of corresponding {@link MediaRoute2ProviderService service} route.
     */
    private record ProviderProxyRecord(
            MediaRoute2ProviderServiceProxy mProxy,
            Map<String, MediaRoute2Info> mSystemMediaRoutes,
            Map<String, String> mNewOriginalIdToSourceOriginalIdMap) {

        /** Returns a stream representation of the {@link #mSystemMediaRoutes}. */
        public Stream<MediaRoute2Info> getRoutesStream() {
            return mSystemMediaRoutes.values().stream();
        }

        @Nullable
        public MediaRoute2Info getRouteByOriginalId(String routeOriginalId) {
            return mSystemMediaRoutes.get(routeOriginalId);
        }

        /**
         * Requests the creation of a system media routing session.
         *
         * @param requestId The request id.
         * @param uid The uid of the package whose media to route, or {@link Process#INVALID_UID} if
         *     not applicable.
         * @param packageName The name of the package whose media to route.
         * @param originalRouteId The {@link MediaRoute2Info#getOriginalId() original route id} of
         *     the route that should be initially selected.
         * @param callback A {@link MediaRoute2ProviderServiceProxy.SystemMediaSessionCallback} for
         *     events.
         * @see MediaRoute2ProviderService#onCreateSystemRoutingSession
         */
        public void requestCreateSystemMediaSession(
                long requestId,
                int uid,
                String packageName,
                String originalRouteId,
                SystemMediaSessionCallback callback) {
            var targetRouteId = mNewOriginalIdToSourceOriginalIdMap.get(originalRouteId);
            if (targetRouteId == null) {
                Log.w(
                        TAG,
                        "Failed system media session creation due to lack of mapping for id: "
                                + originalRouteId);
                callback.onRequestFailed(
                        requestId, MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE);
            } else {
                mProxy.requestCreateSystemMediaSession(
                        requestId,
                        uid,
                        packageName,
                        targetRouteId,
                        /* sessionHints= */ null,
                        callback);
            }
        }

        public void requestTransfer(String sessionId, MediaRoute2Info targetRoute) {
            // TODO: Map the target route to the source route original id.
            throw new UnsupportedOperationException("TODO Implement");
        }

        public void releaseSession(long requestId, String originalSessionId) {
            mProxy.releaseSession(requestId, originalSessionId);
        }

        /**
         * Returns a new instance, or null if the given {@code serviceProxy} doesn't have an
         * associated {@link MediaRoute2ProviderInfo}.
         */
        @Nullable
        public static ProviderProxyRecord createFor(MediaRoute2ProviderServiceProxy serviceProxy) {
            MediaRoute2ProviderInfo providerInfo = serviceProxy.getProviderInfo();
            if (providerInfo == null) {
                return null;
            }
            Map<String, MediaRoute2Info> routesMap = new ArrayMap<>();
            Map<String, String> idMap = new ArrayMap<>();
            for (MediaRoute2Info sourceRoute : providerInfo.getRoutes()) {
                if (!sourceRoute.supportsSystemMediaRouting()) {
                    continue;
                }
                String id =
                        asSystemRouteId(providerInfo.getUniqueId(), sourceRoute.getOriginalId());
                var newRoute =
                        new MediaRoute2Info.Builder(id, sourceRoute.getName())
                                .addFeature(FEATURE_LIVE_AUDIO)
                                .build();
                routesMap.put(id, newRoute);
                idMap.put(id, sourceRoute.getOriginalId());
            }
            return new ProviderProxyRecord(
                    serviceProxy,
                    Collections.unmodifiableMap(routesMap),
                    Collections.unmodifiableMap(idMap));
        }
    }

    private class PendingSessionCreationCallbackImpl implements SystemMediaSessionCallback {

        private final String mProviderId;
        private final long mRequestId;
        private final String mClientPackageName;

        private PendingSessionCreationCallbackImpl(
                String providerId, long requestId, String clientPackageName) {
            mProviderId = providerId;
            mRequestId = requestId;
            mClientPackageName = clientPackageName;
        }

        @Override
        public void onSessionUpdate(RoutingSessionInfo sessionInfo) {
            SystemMediaSessionRecord systemMediaSessionRecord =
                    new SystemMediaSessionRecord(mProviderId, sessionInfo);
            synchronized (mLock) {
                mPackageNameToSessionRecord.put(mClientPackageName, systemMediaSessionRecord);
                mPendingSessionCreations.remove(mRequestId);
            }
        }

        @Override
        public void onRequestFailed(long requestId, @Reason int reason) {
            synchronized (mLock) {
                mPendingSessionCreations.remove(mRequestId);
            }
            notifyRequestFailed(requestId, reason);
        }

        @Override
        public void onSessionReleased() {
            // Unexpected. The session hasn't yet been created.
            throw new IllegalStateException();
        }
    }

    private class SystemMediaSessionRecord implements SystemMediaSessionCallback {

        private final String mProviderId;

        @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @NonNull
        private RoutingSessionInfo mSourceSessionInfo;

        /**
         * The same as {@link #mSourceSessionInfo}, except ids are {@link #asSystemRouteId system
         * provider ids}.
         */
        @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @NonNull
        private RoutingSessionInfo mTranslatedSessionInfo;

        SystemMediaSessionRecord(
                @NonNull String providerId, @NonNull RoutingSessionInfo sessionInfo) {
            mProviderId = providerId;
            mSourceSessionInfo = sessionInfo;
            mTranslatedSessionInfo = asSystemProviderSession(sessionInfo);
        }

        @Override
        public void onSessionUpdate(RoutingSessionInfo sessionInfo) {
            synchronized (mLock) {
                mSourceSessionInfo = sessionInfo;
                mTranslatedSessionInfo = asSystemProviderSession(sessionInfo);
            }
            notifySessionInfoUpdated();
        }

        @Override
        public void onRequestFailed(long requestId, @Reason int reason) {
            notifyRequestFailed(requestId, reason);
        }

        @Override
        public void onSessionReleased() {
            synchronized (mLock) {
                removeSelfFromSessionMap();
            }
            notifySessionInfoUpdated();
        }

        @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @Nullable
        public ProviderProxyRecord getProxyRecord() {
            ProviderProxyRecord provider = mProxyRecords.get(mProviderId);
            if (provider == null) {
                // Unexpected condition where the proxy is no longer available while there's an
                // ongoing session. Could happen due to a crash in the provider process.
                removeSelfFromSessionMap();
            }
            return provider;
        }

        @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        private void removeSelfFromSessionMap() {
            mPackageNameToSessionRecord.remove(mSourceSessionInfo.getClientPackageName());
        }

        private RoutingSessionInfo asSystemProviderSession(RoutingSessionInfo session) {
            var builder =
                    new RoutingSessionInfo.Builder(session)
                            .setProviderId(mUniqueId)
                            .clearSelectedRoutes()
                            .clearSelectableRoutes()
                            .clearDeselectableRoutes()
                            .clearTransferableRoutes();
            session.getSelectedRoutes().stream()
                    .map(it -> uniqueIdAsSystemRouteId(session.getProviderId(), it))
                    .forEach(builder::addSelectedRoute);
            session.getSelectableRoutes().stream()
                    .map(it -> uniqueIdAsSystemRouteId(session.getProviderId(), it))
                    .forEach(builder::addSelectableRoute);
            session.getDeselectableRoutes().stream()
                    .map(it -> uniqueIdAsSystemRouteId(session.getProviderId(), it))
                    .forEach(builder::addDeselectableRoute);
            session.getTransferableRoutes().stream()
                    .map(it -> uniqueIdAsSystemRouteId(session.getProviderId(), it))
                    .forEach(builder::addTransferableRoute);
            return builder.build();
        }
    }
}
