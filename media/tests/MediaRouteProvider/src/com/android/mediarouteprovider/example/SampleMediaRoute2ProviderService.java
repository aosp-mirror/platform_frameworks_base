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

package com.android.mediarouteprovider.example;

import static android.media.MediaRoute2Info.DEVICE_TYPE_SPEAKER;
import static android.media.MediaRoute2Info.DEVICE_TYPE_TV;

import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.RouteSessionInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

public class SampleMediaRoute2ProviderService extends MediaRoute2ProviderService {
    private static final String TAG = "SampleMR2ProviderSvc";

    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";
    public static final String ROUTE_ID3_SESSION_CREATION_FAILED =
            "route_id3_session_creation_failed";
    public static final String ROUTE_NAME3 = "Sample Route 3 - Session creation failed";

    public static final String ROUTE_ID_SPECIAL_CATEGORY = "route_special_category";
    public static final String ROUTE_NAME_SPECIAL_CATEGORY = "Special Category Route";

    public static final int VOLUME_MAX = 100;
    public static final String ROUTE_ID_FIXED_VOLUME = "route_fixed_volume";
    public static final String ROUTE_NAME_FIXED_VOLUME = "Fixed Volume Route";
    public static final String ROUTE_ID_VARIABLE_VOLUME = "route_variable_volume";
    public static final String ROUTE_NAME_VARIABLE_VOLUME = "Variable Volume Route";

    public static final String ACTION_REMOVE_ROUTE =
            "com.android.mediarouteprovider.action_remove_route";

    public static final String CATEGORY_SAMPLE =
            "com.android.mediarouteprovider.CATEGORY_SAMPLE";
    public static final String CATEGORY_SPECIAL =
            "com.android.mediarouteprovider.CATEGORY_SPECIAL";

    public static final int SESSION_ID_1 = 1000;

    Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    private void initializeRoutes() {
        MediaRoute2Info route1 = new MediaRoute2Info.Builder(ROUTE_ID1, ROUTE_NAME1)
                .addSupportedCategory(CATEGORY_SAMPLE)
                .setDeviceType(DEVICE_TYPE_TV)
                .build();
        MediaRoute2Info route2 = new MediaRoute2Info.Builder(ROUTE_ID2, ROUTE_NAME2)
                .addSupportedCategory(CATEGORY_SAMPLE)
                .setDeviceType(DEVICE_TYPE_SPEAKER)
                .build();
        MediaRoute2Info route3 = new MediaRoute2Info.Builder(
                ROUTE_ID3_SESSION_CREATION_FAILED, ROUTE_NAME3)
                .addSupportedCategory(CATEGORY_SAMPLE)
                .build();
        MediaRoute2Info routeSpecial =
                new MediaRoute2Info.Builder(ROUTE_ID_SPECIAL_CATEGORY, ROUTE_NAME_SPECIAL_CATEGORY)
                        .addSupportedCategory(CATEGORY_SAMPLE)
                        .addSupportedCategory(CATEGORY_SPECIAL)
                        .build();
        MediaRoute2Info fixedVolumeRoute =
                new MediaRoute2Info.Builder(ROUTE_ID_FIXED_VOLUME, ROUTE_NAME_FIXED_VOLUME)
                        .addSupportedCategory(CATEGORY_SAMPLE)
                        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
                        .build();
        MediaRoute2Info variableVolumeRoute =
                new MediaRoute2Info.Builder(ROUTE_ID_VARIABLE_VOLUME, ROUTE_NAME_VARIABLE_VOLUME)
                        .addSupportedCategory(CATEGORY_SAMPLE)
                        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                        .setVolumeMax(VOLUME_MAX)
                        .build();

        mRoutes.put(route1.getId(), route1);
        mRoutes.put(route2.getId(), route2);
        mRoutes.put(route3.getId(), route3);
        mRoutes.put(routeSpecial.getId(), routeSpecial);
        mRoutes.put(fixedVolumeRoute.getId(), fixedVolumeRoute);
        mRoutes.put(variableVolumeRoute.getId(), variableVolumeRoute);
    }

    @Override
    public void onCreate() {
        initializeRoutes();
    }

    @Override
    public IBinder onBind(Intent intent) {
        publishRoutes();
        return super.onBind(intent);
    }

    @Override
    public void onSelectRoute(String packageName, String routeId, SelectToken token) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(packageName)
                .build());
        publishRoutes();
        notifyRouteSelected(token, Bundle.EMPTY);
    }

    @Override
    public void onUnselectRoute(String packageName, String routeId) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(null)
                .build());
        publishRoutes();
    }

    @Override
    public void onControlRequest(String routeId, Intent request) {
        String action = request.getAction();
        if (ACTION_REMOVE_ROUTE.equals(action)) {
            MediaRoute2Info route = mRoutes.get(routeId);
            if (route != null) {
                mRoutes.remove(routeId);
                publishRoutes();
                mRoutes.put(routeId, route);
            }
        }
    }

    @Override
    public void onSetVolume(String routeId, int volume) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        volume = Math.min(volume, Math.max(0, route.getVolumeMax()));
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setVolume(volume)
                .build());
        publishRoutes();
    }

    @Override
    public void onUpdateVolume(String routeId, int delta) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        int volume = route.getVolume() + delta;
        volume = Math.min(volume, Math.max(0, route.getVolumeMax()));
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setVolume(volume)
                .build());
        publishRoutes();
    }

    @Override
    public void onCreateSession(String packageName, String routeId, String controlCategory,
            int requestId) {
        if (TextUtils.equals(ROUTE_ID3_SESSION_CREATION_FAILED, routeId)) {
            // Tell the router that session cannot be created by passing null as sessionInfo.
            notifySessionCreated(/* sessionInfo= */ null, requestId);
            return;
        }

        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                SESSION_ID_1, packageName, controlCategory)
                .addSelectedRoute(routeId)
                .build();
        notifySessionCreated(sessionInfo,  requestId);
    }

    @Override
    public void onDestroySession(int sessionId, RouteSessionInfo lastSessionInfo) {}

    @Override
    public void onAddRoute(int sessionId, String routeId) {
        RouteSessionInfo sessionInfo = getSessionInfo(sessionId);
        //TODO: we may want to remove route if it belongs to another session
        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .addSelectedRoute(routeId)
                .build();
        updateSessionInfo(newSessionInfo);
        publishRoutes();
    }

    @Override
    public void onRemoveRoute(int sessionId, String routeId) {
        RouteSessionInfo sessionInfo = getSessionInfo(sessionId);
        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .removeSelectedRoute(routeId)
                .build();
        updateSessionInfo(newSessionInfo);
        publishRoutes();
    }

    @Override
    public void onTransferRoute(int sessionId, String routeId) {
        RouteSessionInfo sessionInfo = getSessionInfo(sessionId);
        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .clearSelectedRoutes()
                .addSelectedRoute(routeId)
                .build();
        updateSessionInfo(newSessionInfo);
        publishRoutes();
    }

    void publishRoutes() {
        MediaRoute2ProviderInfo info = new MediaRoute2ProviderInfo.Builder()
                .addRoutes(mRoutes.values())
                .build();
        updateProviderInfo(info);
    }
}
