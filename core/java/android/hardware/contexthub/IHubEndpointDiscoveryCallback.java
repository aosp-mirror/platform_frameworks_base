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

import java.util.List;

/**
 * Interface for listening to updates about endpoint availability.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public interface IHubEndpointDiscoveryCallback {
    /**
     * Called when a list of hub endpoints have started.
     *
     * @param discoveryInfoList The list containing hub discovery information.
     */
    void onEndpointsStarted(@NonNull List<HubDiscoveryInfo> discoveryInfoList);

    /**
     * Called when a list of hub endpoints have stopped.
     *
     * @param discoveryInfoList The list containing hub discovery information.
     * @param reason The reason the endpoints stopped.
     */
    void onEndpointsStopped(
            @NonNull List<HubDiscoveryInfo> discoveryInfoList, @HubEndpoint.Reason int reason);
}
