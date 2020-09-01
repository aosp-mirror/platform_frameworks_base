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

package com.android.mediaroutertest;

import static android.media.MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderService;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.concurrent.GuardedBy;

public class StubMediaRoute2ProviderService extends MediaRoute2ProviderService {
    private static final String TAG = "SampleMR2ProviderSvc";
    private static final Object sLock = new Object();

    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";
    public static final String ROUTE_ID3_SESSION_CREATION_FAILED =
            "route_id3_session_creation_failed";
    public static final String ROUTE_NAME3 = "Sample Route 3 - Session creation failed";
    public static final String ROUTE_ID4_TO_SELECT_AND_DESELECT =
            "route_id4_to_select_and_deselect";
    public static final String ROUTE_NAME4 = "Sample Route 4 - Route to select and deselect";
    public static final String ROUTE_ID5_TO_TRANSFER_TO = "route_id5_to_transfer_to";
    public static final String ROUTE_NAME5 = "Sample Route 5 - Route to transfer to";

    public static final String ROUTE_ID6_TO_BE_IGNORED = "route_id6_to_be_ignored";
    public static final String ROUTE_NAME6 = "Sample Route 6 - Route to be ignored";

    public static final String ROUTE_ID_SPECIAL_FEATURE = "route_special_feature";
    public static final String ROUTE_NAME_SPECIAL_FEATURE = "Special Feature Route";

    public static final int VOLUME_MAX = 100;
    public static final int SESSION_VOLUME_MAX = 50;
    public static final int SESSION_VOLUME_INITIAL = 20;
    public static final String ROUTE_ID_FIXED_VOLUME = "route_fixed_volume";
    public static final String ROUTE_NAME_FIXED_VOLUME = "Fixed Volume Route";
    public static final String ROUTE_ID_VARIABLE_VOLUME = "route_variable_volume";
    public static final String ROUTE_NAME_VARIABLE_VOLUME = "Variable Volume Route";

    public static final String FEATURE_SAMPLE =
            "com.android.mediaroutertest.FEATURE_SAMPLE";
    public static final String FEATURE_SPECIAL =
            "com.android.mediaroutertest..FEATURE_SPECIAL";

    Map<String, MediaRoute2Info> mRoutes = new HashMap<>();
    Map<String, String> mRouteIdToSessionId = new HashMap<>();
    private int mNextSessionId = 1000;

    @GuardedBy("sLock")
    private static StubMediaRoute2ProviderService sInstance;
    private Proxy mProxy;
    private Spy mSpy;

    private void initializeRoutes() {
        MediaRoute2Info route1 = new MediaRoute2Info.Builder(ROUTE_ID1, ROUTE_NAME1)
                .addFeature(FEATURE_SAMPLE)
                .setType(TYPE_REMOTE_TV)
                .build();
        MediaRoute2Info route2 = new MediaRoute2Info.Builder(ROUTE_ID2, ROUTE_NAME2)
                .addFeature(FEATURE_SAMPLE)
                .setType(TYPE_REMOTE_SPEAKER)
                .build();
        MediaRoute2Info route3 = new MediaRoute2Info.Builder(
                ROUTE_ID3_SESSION_CREATION_FAILED, ROUTE_NAME3)
                .addFeature(FEATURE_SAMPLE)
                .build();
        MediaRoute2Info route4 = new MediaRoute2Info.Builder(
                ROUTE_ID4_TO_SELECT_AND_DESELECT, ROUTE_NAME4)
                .addFeature(FEATURE_SAMPLE)
                .build();
        MediaRoute2Info route5 = new MediaRoute2Info.Builder(
                ROUTE_ID5_TO_TRANSFER_TO, ROUTE_NAME5)
                .addFeature(FEATURE_SAMPLE)
                .build();
        MediaRoute2Info route6 = new MediaRoute2Info.Builder(
                ROUTE_ID6_TO_BE_IGNORED, ROUTE_NAME6)
                .addFeature(FEATURE_SAMPLE)
                .build();
        MediaRoute2Info routeSpecial =
                new MediaRoute2Info.Builder(ROUTE_ID_SPECIAL_FEATURE, ROUTE_NAME_SPECIAL_FEATURE)
                        .addFeature(FEATURE_SAMPLE)
                        .addFeature(FEATURE_SPECIAL)
                        .build();
        MediaRoute2Info fixedVolumeRoute =
                new MediaRoute2Info.Builder(ROUTE_ID_FIXED_VOLUME, ROUTE_NAME_FIXED_VOLUME)
                        .addFeature(FEATURE_SAMPLE)
                        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
                        .build();
        MediaRoute2Info variableVolumeRoute =
                new MediaRoute2Info.Builder(ROUTE_ID_VARIABLE_VOLUME, ROUTE_NAME_VARIABLE_VOLUME)
                        .addFeature(FEATURE_SAMPLE)
                        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                        .setVolumeMax(VOLUME_MAX)
                        .build();

        mRoutes.put(route1.getId(), route1);
        mRoutes.put(route2.getId(), route2);
        mRoutes.put(route3.getId(), route3);
        mRoutes.put(route4.getId(), route4);
        mRoutes.put(route5.getId(), route5);
        mRoutes.put(route6.getId(), route6);

        mRoutes.put(routeSpecial.getId(), routeSpecial);
        mRoutes.put(fixedVolumeRoute.getId(), fixedVolumeRoute);
        mRoutes.put(variableVolumeRoute.getId(), variableVolumeRoute);
    }

    public static StubMediaRoute2ProviderService getInstance() {
        synchronized (sLock) {
            return sInstance;
        }
    }

    /**
     * Adds a route and publishes it. It could replace a route in the provider if
     * they have the same route id.
     */
    public void addRoute(@NonNull MediaRoute2Info route) {
        Objects.requireNonNull(route, "route must not be null");
        mRoutes.put(route.getOriginalId(), route);
        publishRoutes();
    }

    /**
     * Removes a route and publishes it.
     */
    public void removeRoute(@NonNull String routeId) {
        Objects.requireNonNull(routeId, "routeId must not be null");
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route != null) {
            mRoutes.remove(routeId);
            publishRoutes();
        }
    }

    @Override
    public void onCreate() {
        synchronized (sLock) {
            sInstance = this;
        }
        initializeRoutes();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (sLock) {
            if (sInstance == this) {
                sInstance = null;
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        publishRoutes();
        return super.onBind(intent);
    }

    @Override
    public void onSetRouteVolume(long requestId, String routeId, int volume) {
        Proxy proxy = mProxy;
        if (proxy != null) {
            proxy.onSetRouteVolume(routeId, volume, requestId);
            return;
        }

        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        volume = Math.max(0, Math.min(volume, route.getVolumeMax()));
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setVolume(volume)
                .build());
        publishRoutes();
    }

    @Override
    public void onSetSessionVolume(long requestId, String sessionId, int volume) {
        RoutingSessionInfo sessionInfo = getSessionInfo(sessionId);
        if (sessionInfo == null) {
            return;
        }
        volume = Math.max(0, Math.min(volume, sessionInfo.getVolumeMax()));
        RoutingSessionInfo newSessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .setVolume(volume)
                .build();
        notifySessionUpdated(newSessionInfo);
    }

    @Override
    public void onCreateSession(long requestId, String packageName, String routeId,
            @Nullable Bundle sessionHints) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null || TextUtils.equals(ROUTE_ID3_SESSION_CREATION_FAILED, routeId)) {
            notifyRequestFailed(requestId, REASON_UNKNOWN_ERROR);
            return;
        }
        // Ignores the request intentionally for testing
        if (TextUtils.equals(ROUTE_ID6_TO_BE_IGNORED, routeId)) {
            return;
        }
        maybeDeselectRoute(routeId);

        final String sessionId = String.valueOf(mNextSessionId);
        mNextSessionId++;

        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(packageName)
                .build());
        mRouteIdToSessionId.put(routeId, sessionId);

        RoutingSessionInfo sessionInfo = new RoutingSessionInfo.Builder(sessionId, packageName)
                .addSelectedRoute(routeId)
                .addSelectableRoute(ROUTE_ID4_TO_SELECT_AND_DESELECT)
                .addTransferableRoute(ROUTE_ID5_TO_TRANSFER_TO)
                .setVolumeHandling(PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(SESSION_VOLUME_MAX)
                .setVolume(SESSION_VOLUME_INITIAL)
                // Set control hints with given sessionHints
                .setControlHints(sessionHints)
                .build();
        notifySessionCreated(requestId, sessionInfo);
        publishRoutes();
    }

    @Override
    public void onReleaseSession(long requestId, String sessionId) {
        Spy spy = mSpy;
        if (spy != null) {
            spy.onReleaseSession(requestId, sessionId);
        }

        RoutingSessionInfo sessionInfo = getSessionInfo(sessionId);
        if (sessionInfo == null) {
            return;
        }

        for (String routeId : sessionInfo.getSelectedRoutes()) {
            mRouteIdToSessionId.remove(routeId);
            MediaRoute2Info route = mRoutes.get(routeId);
            if (route != null) {
                mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                        .setClientPackageName(null)
                        .build());
            }
        }
        notifySessionReleased(sessionId);
        publishRoutes();
    }

    @Override
    public void onSelectRoute(long requestId, String sessionId, String routeId) {
        RoutingSessionInfo sessionInfo = getSessionInfo(sessionId);
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null || sessionInfo == null) {
            return;
        }
        maybeDeselectRoute(routeId);

        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(sessionInfo.getClientPackageName())
                .build());
        mRouteIdToSessionId.put(routeId, sessionId);

        RoutingSessionInfo newSessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .addSelectedRoute(routeId)
                .removeSelectableRoute(routeId)
                .addDeselectableRoute(routeId)
                .build();
        notifySessionUpdated(newSessionInfo);
    }

    @Override
    public void onDeselectRoute(long requestId, String sessionId, String routeId) {
        RoutingSessionInfo sessionInfo = getSessionInfo(sessionId);
        MediaRoute2Info route = mRoutes.get(routeId);

        if (sessionInfo == null || route == null
                || !sessionInfo.getSelectedRoutes().contains(routeId)) {
            return;
        }

        mRouteIdToSessionId.remove(routeId);
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(null)
                .build());

        if (sessionInfo.getSelectedRoutes().size() == 1) {
            notifySessionReleased(sessionId);
            return;
        }

        RoutingSessionInfo newSessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .removeSelectedRoute(routeId)
                .addSelectableRoute(routeId)
                .removeDeselectableRoute(routeId)
                .build();
        notifySessionUpdated(newSessionInfo);
    }

    @Override
    public void onTransferToRoute(long requestId, String sessionId, String routeId) {
        RoutingSessionInfo sessionInfo = getSessionInfo(sessionId);
        MediaRoute2Info route = mRoutes.get(routeId);

        if (sessionInfo == null || route == null) {
            return;
        }

        for (String selectedRouteId : sessionInfo.getSelectedRoutes()) {
            mRouteIdToSessionId.remove(selectedRouteId);
            MediaRoute2Info selectedRoute = mRoutes.get(selectedRouteId);
            if (selectedRoute != null) {
                mRoutes.put(selectedRouteId, new MediaRoute2Info.Builder(selectedRoute)
                        .setClientPackageName(null)
                        .build());
            }
        }

        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(sessionInfo.getClientPackageName())
                .build());
        mRouteIdToSessionId.put(routeId, sessionId);

        RoutingSessionInfo newSessionInfo = new RoutingSessionInfo.Builder(sessionInfo)
                .clearSelectedRoutes()
                .addSelectedRoute(routeId)
                .removeDeselectableRoute(routeId)
                .removeTransferableRoute(routeId)
                .build();
        notifySessionUpdated(newSessionInfo);
        publishRoutes();
    }

    void maybeDeselectRoute(String routeId) {
        if (!mRouteIdToSessionId.containsKey(routeId)) {
            return;
        }

        String sessionId = mRouteIdToSessionId.get(routeId);
        onDeselectRoute(REQUEST_ID_NONE, sessionId, routeId);
    }

    void publishRoutes() {
        notifyRoutes(mRoutes.values());
    }

    public void setProxy(@Nullable Proxy proxy) {
        mProxy = proxy;
    }

    public void setSpy(@Nullable Spy spy) {
        mSpy = spy;
    }

    /**
     * It overrides the original service
     */
    public static class Proxy {
        public void onSetRouteVolume(String routeId, int volume, long requestId) {}
    }

    /**
     * It gets notified but doesn't prevent the original methods to be called.
     */
    public static class Spy {
        public void onReleaseSession(long requestId, String sessionId) {}
    }
}
