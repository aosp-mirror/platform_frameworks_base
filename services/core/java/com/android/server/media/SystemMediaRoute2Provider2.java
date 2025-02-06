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
import static android.media.MediaRoute2Info.FEATURE_LIVE_VIDEO;

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
import java.util.Set;
import java.util.stream.Stream;

/**
 * Extends {@link SystemMediaRoute2Provider} by adding system routes provided by {@link
 * MediaRoute2ProviderService provider services}.
 *
 * <p>System routes are those which can handle the system audio and/or video.
 */
/* package */ class SystemMediaRoute2Provider2 extends SystemMediaRoute2Provider {

    private static final String UNIQUE_SYSTEM_ID_PREFIX = "SYSTEM";
    private static final String UNIQUE_SYSTEM_ID_SEPARATOR = "-";
    private static final boolean FORCE_GLOBAL_ROUTING_SESSION = true;
    private static final String PACKAGE_NAME_FOR_GLOBAL_SESSION = "";

    private final PackageManager mPackageManager;

    @GuardedBy("mLock")
    private MediaRoute2ProviderInfo mLastSystemProviderInfo;

    @GuardedBy("mLock")
    private final Map<String, ProviderProxyRecord> mProxyRecords = new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<String, SystemMediaSessionRecord> mSessionOriginalIdToSessionRecord =
            new ArrayMap<>();

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
    private final LongSparseArray<SystemMediaSessionCallbackImpl> mPendingSessionCreations =
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
            if (FORCE_GLOBAL_ROUTING_SESSION) {
                clientPackageName = PACKAGE_NAME_FOR_GLOBAL_SESSION;
            }
            var targetProviderProxyId = mOriginalRouteIdToProviderId.get(routeOriginalId);
            var targetProviderProxyRecord = mProxyRecords.get(targetProviderProxyId);
            // Holds the target route, if it's managed by a provider service. Holds null otherwise.
            var serviceTargetRoute =
                    targetProviderProxyRecord != null
                            ? targetProviderProxyRecord.getRouteByOriginalId(routeOriginalId)
                            : null;
            var existingSessionRecord = getSessionRecordByPackageName(clientPackageName);
            if (existingSessionRecord != null) {
                var existingSession = existingSessionRecord.mSourceSessionInfo;
                if (targetProviderProxyId != null
                        && TextUtils.equals(
                                targetProviderProxyId, existingSession.getProviderId())) {
                    // The currently selected route and target route both belong to the same
                    // provider. We tell the provider to handle the transfer.
                    if (serviceTargetRoute == null) {
                        notifyRequestFailed(
                                requestId, MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE);
                    } else {
                        targetProviderProxyRecord.mProxy.transferToRoute(
                                requestId,
                                clientUserHandle,
                                clientPackageName,
                                existingSession.getOriginalId(),
                                targetProviderProxyRecord.mNewOriginalIdToSourceOriginalIdMap.get(
                                        routeOriginalId),
                                transferReason);
                    }
                    return;
                } else {
                    // The target route is handled by a provider other than the target one. We need
                    // to release the existing session.
                    var currentProxyRecord = existingSessionRecord.getProxyRecord();
                    if (currentProxyRecord != null) {
                        currentProxyRecord.releaseSession(
                                requestId, existingSession.getOriginalId());
                        existingSessionRecord.removeSelfFromSessionMaps();
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
                        new SystemMediaSessionCallbackImpl(
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
            var overridingSession = getSessionRecordByPackageName(packageName);
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

    @Override
    public void setRouteVolume(long requestId, String routeOriginalId, int volume) {
        synchronized (mLock) {
            var targetProviderProxyId = mOriginalRouteIdToProviderId.get(routeOriginalId);
            var targetProviderProxyRecord = mProxyRecords.get(targetProviderProxyId);
            // Holds the target route, if it's managed by a provider service. Holds null otherwise.
            if (targetProviderProxyRecord != null) {
                var serviceTargetRoute =
                        targetProviderProxyRecord.mNewOriginalIdToSourceOriginalIdMap.get(
                                routeOriginalId);
                if (serviceTargetRoute != null) {
                    targetProviderProxyRecord.mProxy.setRouteVolume(
                            requestId, serviceTargetRoute, volume);
                } else {
                    notifyRequestFailed(
                            requestId, MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE);
                }
            }
        }
        super.setRouteVolume(requestId, routeOriginalId, volume);
    }

    @Override
    public void setSessionVolume(long requestId, String sessionOriginalId, int volume) {
        if (SYSTEM_SESSION_ID.equals(sessionOriginalId)) {
            super.setSessionVolume(requestId, sessionOriginalId, volume);
            return;
        }
        synchronized (mLock) {
            var sessionRecord = getSessionRecordByOriginalId(sessionOriginalId);
            var proxyRecord = sessionRecord != null ? sessionRecord.getProxyRecord() : null;
            if (proxyRecord != null) {
                proxyRecord.mProxy.setSessionVolume(
                        requestId, sessionRecord.getServiceSessionId(), volume);
                return;
            }
        }
        notifyRequestFailed(requestId, MediaRoute2ProviderService.REASON_ROUTE_NOT_AVAILABLE);
    }

    @GuardedBy("mLock")
    private SystemMediaSessionRecord getSessionRecordByOriginalId(String sessionOriginalId) {
        if (FORCE_GLOBAL_ROUTING_SESSION) {
            return getSessionRecordByPackageName(PACKAGE_NAME_FOR_GLOBAL_SESSION);
        } else {
            return mSessionOriginalIdToSessionRecord.get(sessionOriginalId);
        }
    }

    @GuardedBy("mLock")
    private SystemMediaSessionRecord getSessionRecordByPackageName(String clientPackageName) {
        if (FORCE_GLOBAL_ROUTING_SESSION) {
            clientPackageName = PACKAGE_NAME_FOR_GLOBAL_SESSION;
        }
        return mPackageNameToSessionRecord.get(clientPackageName);
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
        notifyGlobalSessionInfoUpdated();
    }

    @Override
    public void onSystemProviderRoutesChanged(MediaRoute2ProviderInfo providerInfo) {
        synchronized (mLock) {
            mLastSystemProviderInfo = providerInfo;
            updateProviderInfo();
        }
        updateSessionInfo();
        notifyGlobalSessionInfoUpdated();
    }

    /**
     * Updates the {@link #mSessionInfos} by expanding the {@link SystemMediaRoute2Provider} session
     * with information from the {@link MediaRoute2ProviderService provider services}.
     */
    private void updateSessionInfo() {
        synchronized (mLock) {
            var globalSessionInfoRecord =
                    getSessionRecordByPackageName(PACKAGE_NAME_FOR_GLOBAL_SESSION);
            var globalSessionInfo =
                    globalSessionInfoRecord != null
                            ? globalSessionInfoRecord.mTranslatedSessionInfo
                            : null;
            if (globalSessionInfo == null) {
                globalSessionInfo = mSystemSessionInfo;
            }
            if (globalSessionInfo == null) {
                // The system session info hasn't been initialized yet. Do nothing.
                return;
            }
            var builder = new RoutingSessionInfo.Builder(globalSessionInfo);
            if (globalSessionInfo == mSystemSessionInfo) {
                // The session is the system one. So we make all the service-provided routes
                // available for transfer. The system transferable routes are already there.
                mProxyRecords.values().stream()
                        .flatMap(ProviderProxyRecord::getRoutesStream)
                        .map(MediaRoute2Info::getOriginalId)
                        .forEach(builder::addTransferableRoute);
            } else {
                // The session is service-provided. So we add the system-provided routes as
                // transferable.
                mLastSystemProviderInfo.getRoutes().stream()
                        .map(MediaRoute2Info::getOriginalId)
                        .forEach(builder::addTransferableRoute);
            }
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

    @Override
    /* package */ void notifyGlobalSessionInfoUpdated() {
        if (mCallback == null) {
            return;
        }

        RoutingSessionInfo sessionInfo;
        Set<String> packageNamesWithRoutingSessionOverrides;
        synchronized (mLock) {
            if (mSessionInfos.isEmpty()) {
                return;
            }
            packageNamesWithRoutingSessionOverrides = mPackageNameToSessionRecord.keySet();
            sessionInfo = mSessionInfos.getFirst();
        }

        mCallback.onSessionUpdated(this, sessionInfo, packageNamesWithRoutingSessionOverrides);
    }

    private void onSessionOverrideUpdated(RoutingSessionInfo sessionInfo) {
        // TODO: b/362507305 - Consider adding routes from other provider services. This is not a
        // trivial change because a provider1-route to provider2-route transfer has seemingly two
        // possible approachies. Either we first release the current session and then create the new
        // one, in which case the audio is briefly going to leak through the system route. On the
        // other hand, if we first create the provider2 session, then there will be a period during
        // which there will be two overlapping routing policies asking for the exact same media
        // stream.
        var builder = new RoutingSessionInfo.Builder(sessionInfo);
        mLastSystemProviderInfo.getRoutes().stream()
                .map(MediaRoute2Info::getOriginalId)
                .forEach(builder::addTransferableRoute);
        mCallback.onSessionUpdated(
                /* provider= */ this,
                builder.build(),
                /* packageNamesWithRoutingSessionOverrides= */ Set.of());
    }

    /**
     * Equivalent to {@link #asUniqueSystemId}, except it takes a unique id instead of an original
     * id.
     */
    private static String uniqueIdAsSystemRouteId(String providerId, String uniqueRouteId) {
        return asUniqueSystemId(providerId, MediaRouter2Utils.getOriginalId(uniqueRouteId));
    }

    /**
     * Returns a unique {@link MediaRoute2Info#getOriginalId() original id} for this provider to
     * publish system media routes and sessions from {@link MediaRoute2ProviderService provider
     * services}.
     *
     * <p>This provider will publish system media routes as part of the system routing session.
     * However, said routes may also support {@link MediaRoute2Info#FLAG_ROUTING_TYPE_REMOTE remote
     * routing}, meaning we cannot use the same id, or there would be an id collision. As a result,
     * we derive a {@link MediaRoute2Info#getOriginalId original id} that is unique among all
     * original route ids used by this provider.
     */
    private static String asUniqueSystemId(String providerId, String originalId) {
        return UNIQUE_SYSTEM_ID_PREFIX
                + UNIQUE_SYSTEM_ID_SEPARATOR
                + providerId
                + UNIQUE_SYSTEM_ID_SEPARATOR
                + originalId;
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
                        asUniqueSystemId(providerInfo.getUniqueId(), sourceRoute.getOriginalId());
                var newRouteBuilder = new MediaRoute2Info.Builder(id, sourceRoute);
                if ((sourceRoute.getSupportedRoutingTypes()
                                & MediaRoute2Info.FLAG_ROUTING_TYPE_SYSTEM_AUDIO)
                        != 0) {
                    newRouteBuilder.addFeature(FEATURE_LIVE_AUDIO);
                }
                if ((sourceRoute.getSupportedRoutingTypes()
                                & MediaRoute2Info.FLAG_ROUTING_TYPE_SYSTEM_VIDEO)
                        != 0) {
                    newRouteBuilder.addFeature(FEATURE_LIVE_VIDEO);
                }
                routesMap.put(id, newRouteBuilder.build());
                idMap.put(id, sourceRoute.getOriginalId());
            }
            return new ProviderProxyRecord(
                    serviceProxy,
                    Collections.unmodifiableMap(routesMap),
                    Collections.unmodifiableMap(idMap));
        }
    }

    private class SystemMediaSessionCallbackImpl implements SystemMediaSessionCallback {

        private final String mProviderId;
        private final long mRequestId;
        private final String mClientPackageName;
        // Accessed only on mHandler.
        @Nullable private SystemMediaSessionRecord mSessionRecord;

        private SystemMediaSessionCallbackImpl(
                String providerId, long requestId, String clientPackageName) {
            mProviderId = providerId;
            mRequestId = requestId;
            mClientPackageName = clientPackageName;
        }

        @Override
        public void onSessionUpdate(@NonNull RoutingSessionInfo sessionInfo) {
            mHandler.post(
                    () -> {
                        if (mSessionRecord != null) {
                            mSessionRecord.onSessionUpdate(sessionInfo);
                        } else {
                            SystemMediaSessionRecord systemMediaSessionRecord =
                                    new SystemMediaSessionRecord(mProviderId, sessionInfo);
                            RoutingSessionInfo translatedSession;
                            synchronized (mLock) {
                                mSessionRecord = systemMediaSessionRecord;
                                mSessionOriginalIdToSessionRecord.put(
                                        systemMediaSessionRecord.mOriginalId,
                                        systemMediaSessionRecord);
                                mPackageNameToSessionRecord.put(
                                        mClientPackageName, systemMediaSessionRecord);
                                mPendingSessionCreations.remove(mRequestId);
                                translatedSession = systemMediaSessionRecord.mTranslatedSessionInfo;
                            }
                            onSessionOverrideUpdated(translatedSession);
                        }
                    });
        }

        @Override
        public void onRequestFailed(long requestId, @Reason int reason) {
            mHandler.post(
                    () -> {
                        if (mSessionRecord != null) {
                            mSessionRecord.onRequestFailed(requestId, reason);
                        }
                        synchronized (mLock) {
                            mPendingSessionCreations.remove(mRequestId);
                        }
                        notifyRequestFailed(requestId, reason);
                    });
        }

        @Override
        public void onSessionReleased() {
            mHandler.post(
                    () -> {
                        if (mSessionRecord != null) {
                            mSessionRecord.onSessionReleased();
                        } else {
                            // Should never happen. The session hasn't yet been created.
                            throw new IllegalStateException();
                        }
                    });
        }
    }

    private class SystemMediaSessionRecord implements SystemMediaSessionCallback {

        private final String mProviderId;

        /**
         * The {@link RoutingSessionInfo#getOriginalId() original id} with which this session is
         * published.
         *
         * <p>Derived from the service routing session, using {@link #asUniqueSystemId}.
         */
        private final String mOriginalId;

        // @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @NonNull private RoutingSessionInfo mSourceSessionInfo;

        /**
         * The same as {@link #mSourceSessionInfo}, except ids are {@link #asUniqueSystemId system
         * provider ids}.
         */
        // @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @NonNull private RoutingSessionInfo mTranslatedSessionInfo;

        SystemMediaSessionRecord(
                @NonNull String providerId, @NonNull RoutingSessionInfo sessionInfo) {
            mProviderId = providerId;
            mSourceSessionInfo = sessionInfo;
            mOriginalId =
                    asUniqueSystemId(sessionInfo.getProviderId(), sessionInfo.getOriginalId());
            mTranslatedSessionInfo = asSystemProviderSession(sessionInfo);
        }

        // @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        public String getServiceSessionId() {
            return mSourceSessionInfo.getOriginalId();
        }

        @Override
        public void onSessionUpdate(@NonNull RoutingSessionInfo sessionInfo) {
            RoutingSessionInfo translatedSessionInfo = asSystemProviderSession(sessionInfo);
            synchronized (mLock) {
                mSourceSessionInfo = sessionInfo;
                mTranslatedSessionInfo = translatedSessionInfo;
            }
            onSessionOverrideUpdated(translatedSessionInfo);
        }

        @Override
        public void onRequestFailed(long requestId, @Reason int reason) {
            notifyRequestFailed(requestId, reason);
        }

        @Override
        public void onSessionReleased() {
            synchronized (mLock) {
                removeSelfFromSessionMaps();
            }
            notifyGlobalSessionInfoUpdated();
        }

        // @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        @Nullable
        public ProviderProxyRecord getProxyRecord() {
            ProviderProxyRecord provider = mProxyRecords.get(mProviderId);
            if (provider == null) {
                // Unexpected condition where the proxy is no longer available while there's an
                // ongoing session. Could happen due to a crash in the provider process.
                removeSelfFromSessionMaps();
            }
            return provider;
        }

        // @GuardedBy("SystemMediaRoute2Provider2.this.mLock")
        private void removeSelfFromSessionMaps() {
            mSessionOriginalIdToSessionRecord.remove(mOriginalId);
            mPackageNameToSessionRecord.remove(mSourceSessionInfo.getClientPackageName());
        }

        private RoutingSessionInfo asSystemProviderSession(RoutingSessionInfo session) {
            var builder =
                    new RoutingSessionInfo.Builder(session, mOriginalId)
                            .setProviderId(mUniqueId)
                            .setSystemSession(true)
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
