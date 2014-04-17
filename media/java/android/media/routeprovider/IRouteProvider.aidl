/* Copyright (C) 2014 The Android Open Source Project
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

package android.media.routeprovider;

import android.media.routeprovider.IRouteConnection;
import android.media.routeprovider.IRouteProviderCallback;
import android.media.routeprovider.RouteRequest;
import android.media.session.RouteInfo;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Interface to an app's RouteProviderService.
 * @hide
 */
oneway interface IRouteProvider {
    void registerCallback(in IRouteProviderCallback cb);
    void unregisterCallback(in IRouteProviderCallback cb);
    void updateDiscoveryRequests(in List<RouteRequest> requests);

    void getAvailableRoutes(in List<RouteRequest> requests, in ResultReceiver cb);
    void connect(in RouteInfo route, in RouteRequest request, in ResultReceiver cb);
}