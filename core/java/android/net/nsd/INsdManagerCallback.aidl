/**
 * Copyright (c) 2021, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.nsd;

import android.os.Messenger;
import android.net.nsd.NsdServiceInfo;

/**
 * Callbacks from NsdService to NsdManager
 * @hide
 */
oneway interface INsdManagerCallback {
    void onDiscoverServicesStarted(int listenerKey, in NsdServiceInfo info);
    void onDiscoverServicesFailed(int listenerKey, int error);
    void onServiceFound(int listenerKey, in NsdServiceInfo info);
    void onServiceLost(int listenerKey, in NsdServiceInfo info);
    void onStopDiscoveryFailed(int listenerKey, int error);
    void onStopDiscoverySucceeded(int listenerKey);
    void onRegisterServiceFailed(int listenerKey, int error);
    void onRegisterServiceSucceeded(int listenerKey, in NsdServiceInfo info);
    void onUnregisterServiceFailed(int listenerKey, int error);
    void onUnregisterServiceSucceeded(int listenerKey);
    void onResolveServiceFailed(int listenerKey, int error);
    void onResolveServiceSucceeded(int listenerKey, in NsdServiceInfo info);
}
