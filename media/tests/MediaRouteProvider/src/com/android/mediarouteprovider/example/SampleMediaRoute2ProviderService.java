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
    private static final String TAG = "SampleMediaRoute2Serv";

    public static final String ROUTE_ID1 = "route_id";
    public static final String ROUTE_NAME1 = "route_name";

    Map<String, MediaRoute2Info> mRoutes = new HashMap<>();

    private void initializeRoutes() {
        MediaRoute2Info route1 = new MediaRoute2Info.Builder(ROUTE_ID1, ROUTE_NAME1)
                .build();

        mRoutes.put(route1.getId(), route1);
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
    public void onSelect(int uid, String routeId) {
        updateProvider(uid, routeId);
        publishRoutes();
    }

    void publishRoutes() {
        MediaRoute2ProviderInfo info = new MediaRoute2ProviderInfo.Builder()
                .addRoutes(mRoutes.values())
                .build();
        setProviderInfo(info);
    }
}
