/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.metrics;

import android.annotation.SystemApi;
import android.net.ConnectivityMetricsLogger;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * {@hide}
 */
@SystemApi
public abstract class IpConnectivityEvent {
    // IPRM   = IpReachabilityMonitor
    // DHCP   = DhcpClient
    // NETMON = NetworkMonitorEvent
    // CONSRV = ConnectivityServiceEvent
    // IPMGR  = IpManager
    // DNS    = DnsEvent
    public static final int IPCE_IPRM_BASE                 = 0 * 1024;
    public static final int IPCE_DHCP_BASE                 = 1 * 1024;
    public static final int IPCE_NETMON_BASE               = 2 * 1024;
    public static final int IPCE_CONSRV_BASE               = 3 * 1024;
    public static final int IPCE_IPMGR_BASE                = 4 * 1024;
    public static final int IPCE_DNS_BASE                  = 5 * 1024;

    public static final int IPCE_IPRM_PROBE_RESULT         = IPCE_IPRM_BASE + 0;
    public static final int IPCE_IPRM_MESSAGE_RECEIVED     = IPCE_IPRM_BASE + 1;
    public static final int IPCE_IPRM_REACHABILITY_LOST    = IPCE_IPRM_BASE + 2;

    public static final int IPCE_DHCP_RECV_ERROR           = IPCE_DHCP_BASE + 0;
    public static final int IPCE_DHCP_PARSE_ERROR          = IPCE_DHCP_BASE + 1;
    public static final int IPCE_DHCP_STATE_CHANGE         = IPCE_DHCP_BASE + 2;

    public static final int IPCE_NETMON_STATE_CHANGE       = IPCE_NETMON_BASE + 0;
    public static final int IPCE_NETMON_CHECK_RESULT       = IPCE_NETMON_BASE + 1;
    public static final int IPCE_NETMON_VALIDATED          = IPCE_NETMON_BASE + 2;
    public static final int IPCE_NETMON_PORTAL_PROBE       = IPCE_NETMON_BASE + 3;
    public static final int IPCE_NETMON_CAPPORT_FOUND      = IPCE_NETMON_BASE + 4;

    public static final int IPCE_CONSRV_DEFAULT_NET_CHANGE = IPCE_CONSRV_BASE + 0;

    public static final int IPCE_IPMGR_PROVISIONING_OK     = IPCE_IPMGR_BASE + 0;
    public static final int IPCE_IPMGR_PROVISIONING_FAIL   = IPCE_IPMGR_BASE + 1;
    public static final int IPCE_IPMGR_COMPLETE_LIFECYCLE  = IPCE_IPMGR_BASE + 2;

    public static final int IPCE_DNS_LOOKUPS               = IPCE_DNS_BASE + 0;

    private static ConnectivityMetricsLogger mMetricsLogger = new ConnectivityMetricsLogger();

    public static <T extends IpConnectivityEvent & Parcelable> void logEvent(int tag, T event) {
        final long timestamp = System.currentTimeMillis();
        final int componentTag = ConnectivityMetricsLogger.COMPONENT_TAG_CONNECTIVITY;
        // TODO: consider using different component for DNS event.
        mMetricsLogger.logEvent(timestamp, componentTag, tag, event);
    }
};
