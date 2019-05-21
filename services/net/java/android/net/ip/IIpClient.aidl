/**
 * Copyright (c) 2019, The Android Open Source Project
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
 * See the License for the specific language governing perNmissions and
 * limitations under the License.
 */
package android.net.ip;

import android.net.ProxyInfo;
import android.net.ProvisioningConfigurationParcelable;
import android.net.NattKeepalivePacketDataParcelable;
import android.net.TcpKeepalivePacketDataParcelable;

/** @hide */
oneway interface IIpClient {
    void completedPreDhcpAction();
    void confirmConfiguration();
    void readPacketFilterComplete(in byte[] data);
    void shutdown();
    void startProvisioning(in ProvisioningConfigurationParcelable req);
    void stop();
    void setTcpBufferSizes(in String tcpBufferSizes);
    void setHttpProxy(in ProxyInfo proxyInfo);
    void setMulticastFilter(boolean enabled);
    void addKeepalivePacketFilter(int slot, in TcpKeepalivePacketDataParcelable pkt);
    void removeKeepalivePacketFilter(int slot);
    void setL2KeyAndGroupHint(in String l2Key, in String groupHint);
    void addNattKeepalivePacketFilter(int slot, in NattKeepalivePacketDataParcelable pkt);
}
