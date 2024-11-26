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

package android.hardware.contexthub;

import android.hardware.contexthub.HubEndpointInfo;

/**
 * @hide
 */
oneway interface IContextHubEndpointDiscoveryCallback {
    /**
     * Called when endpoint(s) start.
     * @param hubEndpointInfoList The list of endpoints that started.
     */
    void onEndpointsStarted(in HubEndpointInfo[] hubEndpointInfoList);

    /**
     * Called when endpoint(s) stopped.
     * @param hubEndpointInfoList The list of endpoints that started.
     * @param reason The reason why the endpoints stopped.
     */
    void onEndpointsStopped(in HubEndpointInfo[] hubEndpointInfoList, int reason);
}
