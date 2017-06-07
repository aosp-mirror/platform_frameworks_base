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

import android.net.lowpan.ILowpanInterfaceListener;
import android.net.lowpan.ILowpanNetScanCallback;
import android.net.lowpan.ILowpanEnergyScanCallback;
import android.os.PersistableBundle;
import android.net.IpPrefix;

/** {@hide} */
interface ILowpanInterface {

    //////////////////////////////////////////////////////////////////////////
    // Permission String Constants

    /* These are here for the sake of C++ interface implementations. */

    const String PERM_ACCESS_LOWPAN_STATE    = "android.permission.ACCESS_LOWPAN_STATE";
    const String PERM_CHANGE_LOWPAN_STATE    = "android.permission.CHANGE_LOWPAN_STATE";
    const String PERM_READ_LOWPAN_CREDENTIAL = "android.permission.READ_LOWPAN_CREDENTIAL";

    //////////////////////////////////////////////////////////////////////////
    // Property Key Constants

    const String KEY_INTERFACE_ENABLED      = "android.net.lowpan.property.INTERFACE_ENABLED";
    const String KEY_INTERFACE_UP           = "android.net.lowpan.property.INTERFACE_UP";
    const String KEY_INTERFACE_COMMISSIONED = "android.net.lowpan.property.INTERFACE_COMMISSIONED";
    const String KEY_INTERFACE_CONNECTED    = "android.net.lowpan.property.INTERFACE_CONNECTED";
    const String KEY_INTERFACE_STATE        = "android.net.lowpan.property.INTERFACE_STATE";

    const String KEY_NETWORK_NAME             = "android.net.lowpan.property.NETWORK_NAME";
    const String KEY_NETWORK_TYPE             = "android.net.lowpan.property.NETWORK_TYPE";
    const String KEY_NETWORK_PANID            = "android.net.lowpan.property.NETWORK_PANID";
    const String KEY_NETWORK_XPANID           = "android.net.lowpan.property.NETWORK_XPANID";
    const String KEY_NETWORK_ROLE             = "android.net.lowpan.property.NETWORK_ROLE";
    const String KEY_NETWORK_MASTER_KEY       = "android.net.lowpan.property.NETWORK_MASTER_KEY";
    const String KEY_NETWORK_MASTER_KEY_INDEX
        = "android.net.lowpan.property.NETWORK_MASTER_KEY_INDEX";

    const String KEY_SUPPORTED_CHANNELS = "android.net.lowpan.property.SUPPORTED_CHANNELS";
    const String KEY_CHANNEL            = "android.net.lowpan.property.CHANNEL";
    const String KEY_CHANNEL_MASK       = "android.net.lowpan.property.CHANNEL_MASK";
    const String KEY_MAX_TX_POWER       = "android.net.lowpan.property.MAX_TX_POWER";
    const String KEY_RSSI               = "android.net.lowpan.property.RSSI";
    const String KEY_LQI                = "android.net.lowpan.property.LQI";

    const String KEY_LINK_ADDRESS_ARRAY = "android.net.lowpan.property.LINK_ADDRESS_ARRAY";
    const String KEY_ROUTE_INFO_ARRAY   = "android.net.lowpan.property.ROUTE_INFO_ARRAY";

    const String KEY_BEACON_ADDRESS     = "android.net.lowpan.property.BEACON_ORIGIN_ADDRESS";
    const String KEY_BEACON_CAN_ASSIST  = "android.net.lowpan.property.BEACON_CAN_ASSIST";

    const String DRIVER_VERSION         = "android.net.lowpan.property.DRIVER_VERSION";
    const String NCP_VERSION            = "android.net.lowpan.property.NCP_VERSION";

    /** @hide */
    const String KEY_EXTENDED_ADDRESS = "android.net.lowpan.property.EXTENDED_ADDRESS";

    /** @hide */
    const String KEY_MAC_ADDRESS      = "android.net.lowpan.property.MAC_ADDRESS";

    //////////////////////////////////////////////////////////////////////////
    // Interface States

    const String STATE_OFFLINE = "offline";
    const String STATE_COMMISSIONING = "commissioning";
    const String STATE_ATTACHING = "attaching";
    const String STATE_ATTACHED = "attached";
    const String STATE_FAULT = "fault";

    //////////////////////////////////////////////////////////////////////////
    // Device Roles

    const String ROLE_END_DEVICE = "end-device";
    const String ROLE_ROUTER = "router";
    const String ROLE_SLEEPY_END_DEVICE = "sleepy-end-device";
    const String ROLE_SLEEPY_ROUTER = "sleepy-router";
    const String ROLE_UNKNOWN = "unknown";

    //////////////////////////////////////////////////////////////////////////
    // Service-Specific Error Code Constants

    const int ERROR_UNSPECIFIED = 1;
    const int ERROR_INVALID_ARGUMENT = 2;
    const int ERROR_DISABLED = 3;
    const int ERROR_WRONG_STATE = 4;
    const int ERROR_INVALID_TYPE = 5;
    const int ERROR_INVALID_VALUE = 6;
    const int ERROR_TIMEOUT = 7;
    const int ERROR_IO_FAILURE = 8;
    const int ERROR_BUSY = 9;
    const int ERROR_ALREADY = 10;
    const int ERROR_CANCELED = 11;
    const int ERROR_CREDENTIAL_NEEDED = 12;
    const int ERROR_FEATURE_NOT_SUPPORTED = 14;
    const int ERROR_PROPERTY_NOT_FOUND = 16;
    const int ERROR_JOIN_FAILED_UNKNOWN = 18;
    const int ERROR_JOIN_FAILED_AT_SCAN = 19;
    const int ERROR_JOIN_FAILED_AT_AUTH = 20;
    const int ERROR_FORM_FAILED_AT_SCAN = 21;
    const int ERROR_NCP_PROBLEM = 27;
    const int ERROR_PERMISSION_DENIED = 28;

    //////////////////////////////////////////////////////////////////////////
    // Methods

    @utf8InCpp String getName();

    void join(in Map parameters);
    void form(in Map parameters);
    void leave();
    void reset();

    void beginLowPower();
    void pollForData();

    oneway void onHostWake();

    @utf8InCpp String[] getPropertyKeys();
    Map getProperties(in @utf8InCpp String[] keys);
    void setProperties(in Map properties);

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

    @utf8InCpp String getPropertyAsString(@utf8InCpp String key);
}
