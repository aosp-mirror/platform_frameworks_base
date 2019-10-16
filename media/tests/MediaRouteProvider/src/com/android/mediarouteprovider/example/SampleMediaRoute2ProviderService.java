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

import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

public class SampleMediaRoute2ProviderService extends MediaRoute2ProviderService {
    private static final String TAG = "SampleMR2ProviderSvc";

    public static final String ROUTE_ID1 = "route_id1";
    public static final String ROUTE_NAME1 = "Sample Route 1";
    public static final String ROUTE_ID2 = "route_id2";
    public static final String ROUTE_NAME2 = "Sample Route 2";

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

    Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    private void initializeRoutes() {
        MediaRoute2Info route1 = new MediaRoute2Info.Builder(ROUTE_ID1, ROUTE_NAME1)
                .addSupportedCategory(CATEGORY_SAMPLE)
                .build();
        MediaRoute2Info route2 = new MediaRoute2Info.Builder(ROUTE_ID2, ROUTE_NAME2)
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
    public void onSelectRoute(String packageName, String routeId) {
        MediaRoute2Info route = mRoutes.get(routeId);
        if (route == null) {
            return;
        }
        mRoutes.put(routeId, new MediaRoute2Info.Builder(route)
                .setClientPackageName(packageName)
                .build());
        publishRoutes();
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
        if (ACTION_REMOVE_ROUTE.equals(request.getAction())) {
            MediaRoute2Info route = mRoutes.get(routeId);
            if (route != null) {
                mRoutes.remove(routeId);
                publishRoutes();
                mRoutes.put(routeId, route);
            }
        }
    }

    void publishRoutes() {
        MediaRoute2ProviderInfo info = new MediaRoute2ProviderInfo.Builder()
                .addRoutes(mRoutes.values())
                .build();
        setProviderInfo(info);
    }
}
