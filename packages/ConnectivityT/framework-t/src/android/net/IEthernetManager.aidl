/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.net.IpConfiguration;
import android.net.IEthernetServiceListener;
import android.net.EthernetNetworkManagementException;
import android.net.EthernetNetworkUpdateRequest;
import android.net.INetworkInterfaceOutcomeReceiver;
import android.net.ITetheredInterfaceCallback;

/**
 * Interface that answers queries about, and allows changing
 * ethernet configuration.
 */
/** {@hide} */
interface IEthernetManager
{
    String[] getAvailableInterfaces();
    IpConfiguration getConfiguration(String iface);
    void setConfiguration(String iface, in IpConfiguration config);
    boolean isAvailable(String iface);
    void addListener(in IEthernetServiceListener listener);
    void removeListener(in IEthernetServiceListener listener);
    void setIncludeTestInterfaces(boolean include);
    void requestTetheredInterface(in ITetheredInterfaceCallback callback);
    void releaseTetheredInterface(in ITetheredInterfaceCallback callback);
    void updateConfiguration(String iface, in EthernetNetworkUpdateRequest request,
        in INetworkInterfaceOutcomeReceiver listener);
    void connectNetwork(String iface, in INetworkInterfaceOutcomeReceiver listener);
    void disconnectNetwork(String iface, in INetworkInterfaceOutcomeReceiver listener);
    void setEthernetEnabled(boolean enabled);
}
