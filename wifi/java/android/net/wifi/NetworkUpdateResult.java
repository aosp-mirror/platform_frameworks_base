/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.wifi;

import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;

class NetworkUpdateResult {
    int netId;
    boolean ipChanged;
    boolean proxyChanged;
    boolean isNewNetwork = false;

    public NetworkUpdateResult(int id) {
        netId = id;
        ipChanged = false;
        proxyChanged = false;
    }

    public NetworkUpdateResult(boolean ip, boolean proxy) {
        netId = INVALID_NETWORK_ID;
        ipChanged = ip;
        proxyChanged = proxy;
    }

    public void setNetworkId(int id) {
        netId = id;
    }

    public int getNetworkId() {
        return netId;
    }

    public void setIpChanged(boolean ip) {
        ipChanged = ip;
    }

    public boolean hasIpChanged() {
        return ipChanged;
    }

    public void setProxyChanged(boolean proxy) {
        proxyChanged = proxy;
    }

    public boolean hasProxyChanged() {
        return proxyChanged;
    }

    public boolean isNewNetwork() {
        return isNewNetwork;
    }

    public void setIsNewNetwork(boolean isNew) {
        isNewNetwork = isNew;
    }
}
