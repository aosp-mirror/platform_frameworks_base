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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.chre.flags.Flags;
import android.hardware.location.ContextHubManager;

/**
 * Class that represents the result of from an hub endpoint discovery.
 *
 * <p>The type is returned from an endpoint discovery query via {@link
 * ContextHubManager#findEndpoints}.
 *
 * <p>Application may use the values {@link #getHubEndpointInfo} to retrieve the {@link
 * HubEndpointInfo} that describes the endpoint that matches the query.
 *
 * <p>Application may use the values {@link #getHubServiceInfo()} to retrieve the {@link
 * HubServiceInfo} that describes the service that matches the query.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public class HubDiscoveryInfo {
    @NonNull private final HubEndpointInfo mEndpointInfo;
    @Nullable private final HubServiceInfo mServiceInfo;

    /** @hide */
    public HubDiscoveryInfo(@NonNull HubEndpointInfo endpointInfo) {
        mEndpointInfo = endpointInfo;
        mServiceInfo = null;
    }

    /** @hide */
    public HubDiscoveryInfo(
            @NonNull HubEndpointInfo endpointInfo, @NonNull HubServiceInfo serviceInfo) {
        mEndpointInfo = endpointInfo;
        mServiceInfo = serviceInfo;
    }

    /** Get the {@link HubEndpointInfo} for the endpoint found. */
    @NonNull
    public HubEndpointInfo getHubEndpointInfo() {
        return mEndpointInfo;
    }

    /**
     * Get the {@link HubServiceInfo} for the endpoint found. The value will be null if there is no
     * service info specified in the query.
     */
    @Nullable
    public HubServiceInfo getHubServiceInfo() {
        return mServiceInfo;
    }
}
