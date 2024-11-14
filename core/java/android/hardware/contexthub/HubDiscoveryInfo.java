/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.hardware.location.ContextHubManager;

/**
 * Class that represents the result of from an hub endpoint discovery.
 *
 * <p>The type is returned from an endpoint discovery query via {@link
 * ContextHubManager#findEndpoints}. Application may use the values {@link #getHubEndpointInfo} to
 * retrieve the {@link HubEndpointInfo} that describes the endpoint that matches the query. The
 * class provides flexibility in returning more information (e.g. service provided by the endpoint)
 * in addition to the information about the endpoint.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubDiscoveryInfo {
    // TODO(b/375487784): Add ServiceInfo to the result.
    android.hardware.contexthub.HubEndpointInfo mEndpointInfo;

    /**
     * Constructor for internal use.
     *
     * @hide
     */
    public HubDiscoveryInfo(android.hardware.contexthub.HubEndpointInfo endpointInfo) {
        mEndpointInfo = endpointInfo;
    }

    /** Get the {@link android.hardware.contexthub.HubEndpointInfo} for the endpoint found. */
    @NonNull
    public HubEndpointInfo getHubEndpointInfo() {
        return mEndpointInfo;
    }
}
