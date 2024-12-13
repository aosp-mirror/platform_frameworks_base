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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RoutingSessionInfo;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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

    @GuardedBy("mLock")
    private MediaRoute2ProviderInfo mLastSystemProviderInfo;

    @GuardedBy("mLock")
    private final Map<String, ProviderProxyRecord> mProxyRecords = new HashMap<>();

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
            setProviderState(buildProviderInfo());
        }
        updateSessionInfo();
        notifyProviderState();
        notifySessionInfoUpdated();
    }

    @Override
    public void onSystemProviderRoutesChanged(MediaRoute2ProviderInfo providerInfo) {
        synchronized (mLock) {
            mLastSystemProviderInfo = providerInfo;
            setProviderState(buildProviderInfo());
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
                    .map(MediaRoute2Info::getId)
                    .forEach(builder::addTransferableRoute);
            mSessionInfos.clear();
            mSessionInfos.add(builder.build());
        }
    }

    /**
     * Returns a new a provider info that includes all routes from the system provider {@link
     * SystemMediaRoute2Provider}, along with system routes from {@link MediaRoute2ProviderService
     * provider services}.
     */
    @GuardedBy("mLock")
    private MediaRoute2ProviderInfo buildProviderInfo() {
        MediaRoute2ProviderInfo.Builder builder =
                new MediaRoute2ProviderInfo.Builder(mLastSystemProviderInfo);
        mProxyRecords.values().stream()
                .flatMap(ProviderProxyRecord::getRoutesStream)
                .forEach(builder::addRoute);
        return builder.build();
    }

    /**
     * Holds information about {@link MediaRoute2ProviderService provider services} registered in
     * the system.
     *
     * @param mProxy The corresponding {@link MediaRoute2ProviderServiceProxy}.
     * @param mSystemMediaRoutes The last snapshot of routes from the service that support system
     *     media routing, as defined by {@link MediaRoute2Info#supportsSystemMediaRouting()}.
     */
    private record ProviderProxyRecord(
            MediaRoute2ProviderServiceProxy mProxy,
            Collection<MediaRoute2Info> mSystemMediaRoutes) {

        /** Returns a stream representation of the {@link #mSystemMediaRoutes}. */
        public Stream<MediaRoute2Info> getRoutesStream() {
            return mSystemMediaRoutes.stream();
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
            ArraySet<MediaRoute2Info> routes = new ArraySet<>();
            providerInfo.getRoutes().stream()
                    .filter(MediaRoute2Info::supportsSystemMediaRouting)
                    .forEach(
                            route -> {
                                String id =
                                        ROUTE_ID_PREFIX_SYSTEM
                                                + route.getProviderId()
                                                + ROUTE_ID_SYSTEM_SEPARATOR
                                                + route.getOriginalId();
                                routes.add(
                                        new MediaRoute2Info.Builder(id, route.getName())
                                                .addFeature(FEATURE_LIVE_AUDIO)
                                                .build());
                            });
            return new ProviderProxyRecord(serviceProxy, Collections.unmodifiableSet(routes));
        }
    }
}
