/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.service;

import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetworkConnectionStatus;

/*
 * @hide
 */
interface ISharedConnectivityCallback {
    oneway void onTetherNetworksUpdated(in List<TetherNetwork> networks);
    oneway void onTetherNetworkConnectionStatusChanged(in TetherNetworkConnectionStatus status);
    oneway void onKnownNetworksUpdated(in List<KnownNetwork> networks);
    oneway void onKnownNetworkConnectionStatusChanged(in KnownNetworkConnectionStatus status);
    oneway void onSharedConnectivitySettingsChanged(in SharedConnectivitySettingsState state);
}
