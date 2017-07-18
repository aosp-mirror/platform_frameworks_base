/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.net.IpPrefix;
import android.net.lowpan.ILowpanEnergyScanCallback;
import android.net.lowpan.ILowpanInterfaceListener;
import android.net.lowpan.ILowpanNetScanCallback;
import android.net.lowpan.LowpanBeaconInfo;
import android.net.lowpan.LowpanChannelInfo;
import android.net.lowpan.LowpanCredential;
import android.net.lowpan.LowpanIdentity;
import android.net.lowpan.LowpanProvision;

/** {@hide} */
interface ILowpanInterface {

    // These are here for the sake of C++ interface implementations.

    const String PERM_ACCESS_LOWPAN_STATE    = "android.permission.ACCESS_LOWPAN_STATE";
    const String PERM_CHANGE_LOWPAN_STATE    = "android.permission.CHANGE_LOWPAN_STATE";
    const String PERM_READ_LOWPAN_CREDENTIAL = "android.permission.READ_LOWPAN_CREDENTIAL";

    /**
     * Channel mask key.
     * Used for setting a channel mask when starting a scan.
     * Type: int[]
     * */
    const String KEY_CHANNEL_MASK       = "android.net.lowpan.property.CHANNEL_MASK";

    /**
     * Max Transmit Power Key.
     * Used for setting the maximum transmit power when starting a network scan.
     * Type: Integer
     * */
    const String KEY_MAX_TX_POWER       = "android.net.lowpan.property.MAX_TX_POWER";

    // Interface States

    const String STATE_OFFLINE = "offline";
    const String STATE_COMMISSIONING = "commissioning";
    const String STATE_ATTACHING = "attaching";
    const String STATE_ATTACHED = "attached";
    const String STATE_FAULT = "fault";

    // Device Roles

    const String ROLE_END_DEVICE = "end-device";
    const String ROLE_ROUTER = "router";
    const String ROLE_SLEEPY_END_DEVICE = "sleepy-end-device";
    const String ROLE_SLEEPY_ROUTER = "sleepy-router";
    const String ROLE_LEADER = "leader";
    const String ROLE_COORDINATOR = "coordinator";
    const String ROLE_DETACHED = "detached";

    const String NETWORK_TYPE_UNKNOWN = "unknown";

    /**
     * Network type for Thread 1.x networks.
     *
     * @see android.net.lowpan.LowpanIdentity#getType
     * @see #getLowpanIdentity
     */
    const String NETWORK_TYPE_THREAD_V1 = "org.threadgroup.thread.v1";

    // Service-Specific Error Code Constants

    const int ERROR_UNSPECIFIED = 1;
    const int ERROR_INVALID_ARGUMENT = 2;
    const int ERROR_DISABLED = 3;
    const int ERROR_WRONG_STATE = 4;
    const int ERROR_TIMEOUT = 5;
    const int ERROR_IO_FAILURE = 6;
    const int ERROR_NCP_PROBLEM = 7;
    const int ERROR_BUSY = 8;
    const int ERROR_ALREADY = 9;
    const int ERROR_CANCELED = 10;
    const int ERROR_FEATURE_NOT_SUPPORTED = 11;
    const int ERROR_JOIN_FAILED_UNKNOWN = 12;
    const int ERROR_JOIN_FAILED_AT_SCAN = 13;
    const int ERROR_JOIN_FAILED_AT_AUTH = 14;
    const int ERROR_FORM_FAILED_AT_SCAN = 15;

    // Methods

    @utf8InCpp String getName();

    @utf8InCpp String getNcpVersion();
    @utf8InCpp String getDriverVersion();
    LowpanChannelInfo[] getSupportedChannels();
    @utf8InCpp String[] getSupportedNetworkTypes();
    byte[] getMacAddress();

    boolean isEnabled();
    void setEnabled(boolean enabled);

    boolean isUp();
    boolean isCommissioned();
    boolean isConnected();
    @utf8InCpp String getState();

    @utf8InCpp String getRole();
    @utf8InCpp String getPartitionId();
    byte[] getExtendedAddress();

    LowpanIdentity getLowpanIdentity();
    LowpanCredential getLowpanCredential();

    @utf8InCpp String[] getLinkAddresses();
    IpPrefix[] getLinkNetworks();

    void join(in LowpanProvision provision);
    void form(in LowpanProvision provision);
    void attach(in LowpanProvision provision);
    void leave();
    void reset();

    void startCommissioningSession(in LowpanBeaconInfo beaconInfo);
    void closeCommissioningSession();
    oneway void sendToCommissioner(in byte[] packet);

    void beginLowPower();
    oneway void pollForData();

    oneway void onHostWake();

    void addListener(ILowpanInterfaceListener listener);
    oneway void removeListener(ILowpanInterfaceListener listener);

    void startNetScan(in Map properties, ILowpanNetScanCallback listener);
    oneway void stopNetScan();

    void startEnergyScan(in Map properties, ILowpanEnergyScanCallback listener);
    oneway void stopEnergyScan();

    void addOnMeshPrefix(in IpPrefix prefix, int flags);
    oneway void removeOnMeshPrefix(in IpPrefix prefix);

    void addExternalRoute(in IpPrefix prefix, int flags);
    oneway void removeExternalRoute(in IpPrefix prefix);
}
