/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pService;
import android.net.wifi.p2p.WifiP2pService.P2pStatus;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.net.wifi.StateChangeResult;
import android.os.Message;
import android.util.Log;


import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Listens for events from the wpa_supplicant server, and passes them on
 * to the {@link StateMachine} for handling. Runs in its own thread.
 *
 * @hide
 */
public class WifiMonitor {

    private static final boolean DBG = false;
    private static final String TAG = "WifiMonitor";

    /** Events we receive from the supplicant daemon */

    private static final int CONNECTED    = 1;
    private static final int DISCONNECTED = 2;
    private static final int STATE_CHANGE = 3;
    private static final int SCAN_RESULTS = 4;
    private static final int LINK_SPEED   = 5;
    private static final int TERMINATING  = 6;
    private static final int DRIVER_STATE = 7;
    private static final int EAP_FAILURE  = 8;
    private static final int ASSOC_REJECT = 9;
    private static final int UNKNOWN      = 10;

    /** All events coming from the supplicant start with this prefix */
    private static final String EVENT_PREFIX_STR = "CTRL-EVENT-";
    private static final int EVENT_PREFIX_LEN_STR = EVENT_PREFIX_STR.length();

    /** All WPA events coming from the supplicant start with this prefix */
    private static final String WPA_EVENT_PREFIX_STR = "WPA:";
    private static final String PASSWORD_MAY_BE_INCORRECT_STR =
       "pre-shared key may be incorrect";

    /* WPS events */
    private static final String WPS_SUCCESS_STR = "WPS-SUCCESS";

    /* Format: WPS-FAIL msg=%d [config_error=%d] [reason=%d (%s)] */
    private static final String WPS_FAIL_STR    = "WPS-FAIL";
    private static final String WPS_FAIL_PATTERN =
            "WPS-FAIL msg=\\d+(?: config_error=(\\d+))?(?: reason=(\\d+))?";

    /* config error code values for config_error=%d */
    private static final int CONFIG_MULTIPLE_PBC_DETECTED = 12;
    private static final int CONFIG_AUTH_FAILURE = 18;

    /* reason code values for reason=%d */
    private static final int REASON_TKIP_ONLY_PROHIBITED = 1;
    private static final int REASON_WEP_PROHIBITED = 2;

    private static final String WPS_OVERLAP_STR = "WPS-OVERLAP-DETECTED";
    private static final String WPS_TIMEOUT_STR = "WPS-TIMEOUT";

    /**
     * Names of events from wpa_supplicant (minus the prefix). In the
     * format descriptions, * &quot;<code>x</code>&quot;
     * designates a dynamic value that needs to be parsed out from the event
     * string
     */
    /**
     * <pre>
     * CTRL-EVENT-CONNECTED - Connection to xx:xx:xx:xx:xx:xx completed
     * </pre>
     * <code>xx:xx:xx:xx:xx:xx</code> is the BSSID of the associated access point
     */
    private static final String CONNECTED_STR =    "CONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-DISCONNECTED - Disconnect event - remove keys
     * </pre>
     */
    private static final String DISCONNECTED_STR = "DISCONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-STATE-CHANGE x
     * </pre>
     * <code>x</code> is the numerical value of the new state.
     */
    private static final String STATE_CHANGE_STR =  "STATE-CHANGE";
    /**
     * <pre>
     * CTRL-EVENT-SCAN-RESULTS ready
     * </pre>
     */
    private static final String SCAN_RESULTS_STR =  "SCAN-RESULTS";

    /**
     * <pre>
     * CTRL-EVENT-LINK-SPEED x Mb/s
     * </pre>
     * {@code x} is the link speed in Mb/sec.
     */
    private static final String LINK_SPEED_STR = "LINK-SPEED";
    /**
     * <pre>
     * CTRL-EVENT-TERMINATING - signal x
     * </pre>
     * <code>x</code> is the signal that caused termination.
     */
    private static final String TERMINATING_STR =  "TERMINATING";
    /**
     * <pre>
     * CTRL-EVENT-DRIVER-STATE state
     * </pre>
     * <code>state</code> can be HANGED
     */
    private static final String DRIVER_STATE_STR = "DRIVER-STATE";
    /**
     * <pre>
     * CTRL-EVENT-EAP-FAILURE EAP authentication failed
     * </pre>
     */
    private static final String EAP_FAILURE_STR = "EAP-FAILURE";

    /**
     * This indicates an authentication failure on EAP FAILURE event
     */
    private static final String EAP_AUTH_FAILURE_STR = "EAP authentication failed";

    /**
     * This indicates an assoc reject event
     */
    private static final String ASSOC_REJECT_STR = "ASSOC-REJECT";

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-CONNECTED - Connection to 00:1e:58:ec:d5:6d completed (reauth) [id=1 id_str=]</pre>
     */
    private static Pattern mConnectedEventPattern =
        Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) .* \\[id=([0-9]+) ");

    /** P2P events */
    private static final String P2P_EVENT_PREFIX_STR = "P2P";

    /* P2P-DEVICE-FOUND fa:7b:7a:42:02:13 p2p_dev_addr=fa:7b:7a:42:02:13 pri_dev_type=1-0050F204-1
       name='p2p-TEST1' config_methods=0x188 dev_capab=0x27 group_capab=0x0 */
    private static final String P2P_DEVICE_FOUND_STR = "P2P-DEVICE-FOUND";

    /* P2P-DEVICE-LOST p2p_dev_addr=42:fc:89:e1:e2:27 */
    private static final String P2P_DEVICE_LOST_STR = "P2P-DEVICE-LOST";

    /* P2P-FIND-STOPPED */
    private static final String P2P_FIND_STOPPED_STR = "P2P-FIND-STOPPED";

    /* P2P-GO-NEG-REQUEST 42:fc:89:a8:96:09 dev_passwd_id=4 */
    private static final String P2P_GO_NEG_REQUEST_STR = "P2P-GO-NEG-REQUEST";

    private static final String P2P_GO_NEG_SUCCESS_STR = "P2P-GO-NEG-SUCCESS";

    /* P2P-GO-NEG-FAILURE status=x */
    private static final String P2P_GO_NEG_FAILURE_STR = "P2P-GO-NEG-FAILURE";

    private static final String P2P_GROUP_FORMATION_SUCCESS_STR =
            "P2P-GROUP-FORMATION-SUCCESS";

    private static final String P2P_GROUP_FORMATION_FAILURE_STR =
            "P2P-GROUP-FORMATION-FAILURE";

    /* P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
       [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|passphrase="fKG4jMe3"]
       go_dev_addr=fa:7b:7a:42:02:13 [PERSISTENT] */
    private static final String P2P_GROUP_STARTED_STR = "P2P-GROUP-STARTED";

    /* P2P-GROUP-REMOVED p2p-wlan0-0 [client|GO] reason=REQUESTED */
    private static final String P2P_GROUP_REMOVED_STR = "P2P-GROUP-REMOVED";

    /* P2P-INVITATION-RECEIVED sa=fa:7b:7a:42:02:13 go_dev_addr=f8:7b:7a:42:02:13
        bssid=fa:7b:7a:42:82:13 unknown-network */
    private static final String P2P_INVITATION_RECEIVED_STR = "P2P-INVITATION-RECEIVED";

    /* P2P-INVITATION-RESULT status=1 */
    private static final String P2P_INVITATION_RESULT_STR = "P2P-INVITATION-RESULT";

    /* P2P-PROV-DISC-PBC-REQ 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_PBC_REQ_STR = "P2P-PROV-DISC-PBC-REQ";

    /* P2P-PROV-DISC-PBC-RESP 02:12:47:f2:5a:36 */
    private static final String P2P_PROV_DISC_PBC_RSP_STR = "P2P-PROV-DISC-PBC-RESP";

    /* P2P-PROV-DISC-ENTER-PIN 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_ENTER_PIN_STR = "P2P-PROV-DISC-ENTER-PIN";
    /* P2P-PROV-DISC-SHOW-PIN 42:fc:89:e1:e2:27 44490607 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_SHOW_PIN_STR = "P2P-PROV-DISC-SHOW-PIN";
    /* P2P-PROV-DISC-FAILURE p2p_dev_addr=42:fc:89:e1:e2:27 */
    private static final String P2P_PROV_DISC_FAILURE_STR = "P2P-PROV-DISC-FAILURE";

    /*
     * Protocol format is as follows.<br>
     * See the Table.62 in the WiFi Direct specification for the detail.
     * ______________________________________________________________
     * |           Length(2byte)     | Type(1byte) | TransId(1byte)}|
     * ______________________________________________________________
     * | status(1byte)  |            vendor specific(variable)      |
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 0300000101
     * length=3, service type=0(ALL Service), transaction id=1,
     * status=1(service protocol type not available)<br>
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 0300020201
     * length=3, service type=2(UPnP), transaction id=2,
     * status=1(service protocol type not available)
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 990002030010757569643a3131323
     * 2646534652d383537342d353961622d393332322d3333333435363738393034343a3
     * a75726e3a736368656d61732d75706e702d6f72673a736572766963653a436f6e746
     * 56e744469726563746f72793a322c757569643a36383539646564652d383537342d3
     * 53961622d393333322d3132333435363738393031323a3a75706e703a726f6f74646
     * 576696365
     * length=153,type=2(UPnP),transaction id=3,status=0
     *
     * UPnP Protocol format is as follows.
     * ______________________________________________________
     * |  Version (1)  |          USN (Variable)            |
     *
     * version=0x10(UPnP1.0) data=usn:uuid:1122de4e-8574-59ab-9322-33345678
     * 9044::urn:schemas-upnp-org:service:ContentDirectory:2,usn:uuid:6859d
     * ede-8574-59ab-9332-123456789012::upnp:rootdevice
     *
     * P2P-SERV-DISC-RESP 58:17:0c:bc:dd:ca 21 1900010200045f6970
     * 70c00c000c01094d795072696e746572c027
     * length=25, type=1(Bonjour),transaction id=2,status=0
     *
     * Bonjour Protocol format is as follows.
     * __________________________________________________________
     * |DNS Name(Variable)|DNS Type(1)|Version(1)|RDATA(Variable)|
     *
     * DNS Name=_ipp._tcp.local.,DNS type=12(PTR), Version=1,
     * RDATA=MyPrinter._ipp._tcp.local.
     *
     */
    private static final String P2P_SERV_DISC_RESP_STR = "P2P-SERV-DISC-RESP";

    private static final String HOST_AP_EVENT_PREFIX_STR = "AP";
    /* AP-STA-CONNECTED 42:fc:89:a8:96:09 dev_addr=02:90:4c:a0:92:54 */
    private static final String AP_STA_CONNECTED_STR = "AP-STA-CONNECTED";
    /* AP-STA-DISCONNECTED 42:fc:89:a8:96:09 */
    private static final String AP_STA_DISCONNECTED_STR = "AP-STA-DISCONNECTED";

    /* Supplicant events reported to a state machine */
    private static final int BASE = Protocol.BASE_WIFI_MONITOR;

    /* Connection to supplicant established */
    public static final int SUP_CONNECTION_EVENT                 = BASE + 1;
    /* Connection to supplicant lost */
    public static final int SUP_DISCONNECTION_EVENT              = BASE + 2;
   /* Network connection completed */
    public static final int NETWORK_CONNECTION_EVENT             = BASE + 3;
    /* Network disconnection completed */
    public static final int NETWORK_DISCONNECTION_EVENT          = BASE + 4;
    /* Scan results are available */
    public static final int SCAN_RESULTS_EVENT                   = BASE + 5;
    /* Supplicate state changed */
    public static final int SUPPLICANT_STATE_CHANGE_EVENT        = BASE + 6;
    /* Password failure and EAP authentication failure */
    public static final int AUTHENTICATION_FAILURE_EVENT         = BASE + 7;
    /* WPS success detected */
    public static final int WPS_SUCCESS_EVENT                    = BASE + 8;
    /* WPS failure detected */
    public static final int WPS_FAIL_EVENT                       = BASE + 9;
     /* WPS overlap detected */
    public static final int WPS_OVERLAP_EVENT                    = BASE + 10;
     /* WPS timeout detected */
    public static final int WPS_TIMEOUT_EVENT                    = BASE + 11;
    /* Driver was hung */
    public static final int DRIVER_HUNG_EVENT                    = BASE + 12;

    /* P2P events */
    public static final int P2P_DEVICE_FOUND_EVENT               = BASE + 21;
    public static final int P2P_DEVICE_LOST_EVENT                = BASE + 22;
    public static final int P2P_GO_NEGOTIATION_REQUEST_EVENT     = BASE + 23;
    public static final int P2P_GO_NEGOTIATION_SUCCESS_EVENT     = BASE + 25;
    public static final int P2P_GO_NEGOTIATION_FAILURE_EVENT     = BASE + 26;
    public static final int P2P_GROUP_FORMATION_SUCCESS_EVENT    = BASE + 27;
    public static final int P2P_GROUP_FORMATION_FAILURE_EVENT    = BASE + 28;
    public static final int P2P_GROUP_STARTED_EVENT              = BASE + 29;
    public static final int P2P_GROUP_REMOVED_EVENT              = BASE + 30;
    public static final int P2P_INVITATION_RECEIVED_EVENT        = BASE + 31;
    public static final int P2P_INVITATION_RESULT_EVENT          = BASE + 32;
    public static final int P2P_PROV_DISC_PBC_REQ_EVENT          = BASE + 33;
    public static final int P2P_PROV_DISC_PBC_RSP_EVENT          = BASE + 34;
    public static final int P2P_PROV_DISC_ENTER_PIN_EVENT        = BASE + 35;
    public static final int P2P_PROV_DISC_SHOW_PIN_EVENT         = BASE + 36;
    public static final int P2P_FIND_STOPPED_EVENT               = BASE + 37;
    public static final int P2P_SERV_DISC_RESP_EVENT             = BASE + 38;
    public static final int P2P_PROV_DISC_FAILURE_EVENT          = BASE + 39;

    /* hostap events */
    public static final int AP_STA_DISCONNECTED_EVENT            = BASE + 41;
    public static final int AP_STA_CONNECTED_EVENT               = BASE + 42;

    /* Indicates assoc reject event */
    public static final int ASSOCIATION_REJECTION_EVENT          = BASE + 43;
    /**
     * This indicates the supplicant connection for the monitor is closed
     */
    private static final String MONITOR_SOCKET_CLOSED_STR = "connection closed";

    /**
     * This indicates a read error on the monitor socket conenction
     */
    private static final String WPA_RECV_ERROR_STR = "recv error";

    /**
     * Max errors before we close supplicant connection
     */
    private static final int MAX_RECV_ERRORS    = 10;

    private final String mInterfaceName;
    private final WifiNative mWifiNative;
    private final StateMachine mWifiStateMachine;
    private boolean mMonitoring;

    public WifiMonitor(StateMachine wifiStateMachine, WifiNative wifiNative) {
        if (DBG) Log.d(TAG, "Creating WifiMonitor");
        mWifiNative = wifiNative;
        mInterfaceName = wifiNative.mInterfaceName;
        mWifiStateMachine = wifiStateMachine;
        mMonitoring = false;

        WifiMonitorSingleton.getMonitor().registerInterfaceMonitor(mInterfaceName, this);
    }

    public void startMonitoring() {
        WifiMonitorSingleton.getMonitor().startMonitoring(mInterfaceName);
    }

    public void stopMonitoring() {
        WifiMonitorSingleton.getMonitor().stopMonitoring(mInterfaceName);
    }

    public void stopSupplicant() {
        WifiMonitorSingleton.getMonitor().stopSupplicant();
    }

    public void killSupplicant(boolean p2pSupported) {
        WifiMonitorSingleton.getMonitor().killSupplicant(p2pSupported);
    }

    private static class WifiMonitorSingleton {
        private static Object sSingletonLock = new Object();
        private static WifiMonitorSingleton sWifiMonitorSingleton = null;
        private HashMap<String, WifiMonitor> mIfaceMap = new HashMap<String, WifiMonitor>();
        private boolean mConnected = false;
        private WifiNative mWifiNative;

        private WifiMonitorSingleton() {
        }

        static WifiMonitorSingleton getMonitor() {
            if (DBG) Log.d(TAG, "WifiMonitorSingleton gotten");
            synchronized (sSingletonLock) {
                if (sWifiMonitorSingleton == null) {
                    if (DBG) Log.d(TAG, "WifiMonitorSingleton created");
                    sWifiMonitorSingleton = new WifiMonitorSingleton();
                }
            }
            return sWifiMonitorSingleton;
        }

        public synchronized void startMonitoring(String iface) {
            WifiMonitor m = mIfaceMap.get(iface);
            if (m == null) {
                Log.e(TAG, "startMonitor called with unknown iface=" + iface);
                return;
            }

            Log.d(TAG, "startMonitoring(" + iface + ") with mConnected = " + mConnected);

            if (mConnected) {
                m.mMonitoring = true;
                m.mWifiStateMachine.sendMessage(SUP_CONNECTION_EVENT);
            } else {
                if (DBG) Log.d(TAG, "connecting to supplicant");
                int connectTries = 0;
                while (true) {
                    if (mWifiNative.connectToSupplicant()) {
                        m.mMonitoring = true;
                        m.mWifiStateMachine.sendMessage(SUP_CONNECTION_EVENT);
                        new MonitorThread(mWifiNative, this).start();
                        mConnected = true;
                        break;
                    }
                    if (connectTries++ < 5) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignore) {
                        }
                    } else {
                        mIfaceMap.remove(iface);
                        m.mWifiStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
                        Log.e(TAG, "startMonitoring(" + iface + ") failed!");
                        break;
                    }
                }
            }
        }

        public synchronized void stopMonitoring(String iface) {
            WifiMonitor m = mIfaceMap.get(iface);
            if (DBG) Log.d(TAG, "stopMonitoring(" + iface + ") = " + m.mWifiStateMachine);
            m.mMonitoring = false;
            m.mWifiStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
        }

        public synchronized void registerInterfaceMonitor(String iface, WifiMonitor m) {
            if (DBG) Log.d(TAG, "registerInterface(" + iface + "+" + m.mWifiStateMachine + ")");
            mIfaceMap.put(iface, m);
            if (mWifiNative == null) {
                mWifiNative = m.mWifiNative;
            }
        }

        public synchronized void unregisterInterfaceMonitor(String iface) {
            // REVIEW: When should we call this? If this isn't called, then WifiMonitor
            // objects will remain in the mIfaceMap; and won't ever get deleted

            WifiMonitor m = mIfaceMap.remove(iface);
            if (DBG) Log.d(TAG, "unregisterInterface(" + iface + "+" + m.mWifiStateMachine + ")");
        }

        public synchronized void stopSupplicant() {
            mWifiNative.stopSupplicant();
        }

        public synchronized void killSupplicant(boolean p2pSupported) {
            mWifiNative.killSupplicant(p2pSupported);
            mConnected = false;
            Iterator<Map.Entry<String, WifiMonitor>> it = mIfaceMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, WifiMonitor> e = it.next();
                WifiMonitor m = e.getValue();
                m.mMonitoring = false;
            }
        }

        private synchronized WifiMonitor getMonitor(String iface) {
            return mIfaceMap.get(iface);
        }
    }

    private static class MonitorThread extends Thread {
        private final WifiNative mWifiNative;
        private final WifiMonitorSingleton mWifiMonitorSingleton;
        private int mRecvErrors = 0;
        private StateMachine mStateMachine = null;

        public MonitorThread(WifiNative wifiNative, WifiMonitorSingleton wifiMonitorSingleton) {
            super("WifiMonitor");
            mWifiNative = wifiNative;
            mWifiMonitorSingleton = wifiMonitorSingleton;
        }

        public void run() {
            //noinspection InfiniteLoopStatement
            for (;;) {
                String eventStr = mWifiNative.waitForEvent();

                // Skip logging the common but mostly uninteresting scan-results event
                if (DBG && eventStr.indexOf(SCAN_RESULTS_STR) == -1) {
                    Log.d(TAG, "Event [" + eventStr + "]");
                }

                String iface = "p2p0";
                WifiMonitor m = null;
                mStateMachine = null;

                if (eventStr.startsWith("IFNAME=")) {
                    int space = eventStr.indexOf(' ');
                    if (space != -1) {
                        iface = eventStr.substring(7,space);
                        m = mWifiMonitorSingleton.getMonitor(iface);
                        if (m == null && iface.startsWith("p2p-")) {
                            // p2p interfaces are created dynamically, but we have
                            // only one P2p state machine monitoring all of them; look
                            // for it explicitly, and send messages there ..
                            m = mWifiMonitorSingleton.getMonitor("p2p0");
                        }
                        eventStr = eventStr.substring(space + 1);
                    }
                } else {
                    // events without prefix belong to p2p0 monitor
                    m = mWifiMonitorSingleton.getMonitor("p2p0");
                }

                if (m != null) {
                    if (m.mMonitoring) {
                        mStateMachine = m.mWifiStateMachine;
                    } else {
                        if (DBG) Log.d(TAG, "Dropping event because monitor (" + iface +
                                            ") is stopped");
                        continue;
                    }
                }

                if (mStateMachine != null) {
                    if (dispatchEvent(eventStr)) {
                        break;
                    }
                } else {
                    if (DBG) Log.d(TAG, "Sending to all monitors because there's no interface id");
                    boolean done = false;
                    Iterator<Map.Entry<String, WifiMonitor>> it =
                            mWifiMonitorSingleton.mIfaceMap.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, WifiMonitor> e = it.next();
                        m = e.getValue();
                        mStateMachine = m.mWifiStateMachine;
                        if (dispatchEvent(eventStr)) {
                            done = true;
                        }
                    }

                    if (done) {
                        // After this thread terminates, we'll no longer
                        // be connected to the supplicant
                        if (DBG) Log.d(TAG, "Disconnecting from the supplicant, no more events");
                        mWifiMonitorSingleton.mConnected = false;
                        break;
                    }
                }
            }
        }

        /* @return true if the event was supplicant disconnection */
        private boolean dispatchEvent(String eventStr) {

            if (!eventStr.startsWith(EVENT_PREFIX_STR)) {
                if (eventStr.startsWith(WPA_EVENT_PREFIX_STR) &&
                        0 < eventStr.indexOf(PASSWORD_MAY_BE_INCORRECT_STR)) {
                    mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT);
                } else if (eventStr.startsWith(WPS_SUCCESS_STR)) {
                    mStateMachine.sendMessage(WPS_SUCCESS_EVENT);
                } else if (eventStr.startsWith(WPS_FAIL_STR)) {
                    handleWpsFailEvent(eventStr);
                } else if (eventStr.startsWith(WPS_OVERLAP_STR)) {
                    mStateMachine.sendMessage(WPS_OVERLAP_EVENT);
                } else if (eventStr.startsWith(WPS_TIMEOUT_STR)) {
                    mStateMachine.sendMessage(WPS_TIMEOUT_EVENT);
                } else if (eventStr.startsWith(P2P_EVENT_PREFIX_STR)) {
                    handleP2pEvents(eventStr);
                } else if (eventStr.startsWith(HOST_AP_EVENT_PREFIX_STR)) {
                    handleHostApEvents(eventStr);
                }
                else {
                    if (DBG) Log.w(TAG, "couldn't identify event type - " + eventStr);
                }
                return false;
            }

            String eventName = eventStr.substring(EVENT_PREFIX_LEN_STR);
            int nameEnd = eventName.indexOf(' ');
            if (nameEnd != -1)
                eventName = eventName.substring(0, nameEnd);
            if (eventName.length() == 0) {
                if (DBG) Log.i(TAG, "Received wpa_supplicant event with empty event name");
                return false;
            }
            /*
             * Map event name into event enum
             */
            int event;
            if (eventName.equals(CONNECTED_STR))
                event = CONNECTED;
            else if (eventName.equals(DISCONNECTED_STR))
                event = DISCONNECTED;
            else if (eventName.equals(STATE_CHANGE_STR))
                event = STATE_CHANGE;
            else if (eventName.equals(SCAN_RESULTS_STR))
                event = SCAN_RESULTS;
            else if (eventName.equals(LINK_SPEED_STR))
                event = LINK_SPEED;
            else if (eventName.equals(TERMINATING_STR))
                event = TERMINATING;
            else if (eventName.equals(DRIVER_STATE_STR))
                event = DRIVER_STATE;
            else if (eventName.equals(EAP_FAILURE_STR))
                event = EAP_FAILURE;
            else if (eventName.equals(ASSOC_REJECT_STR))
                event = ASSOC_REJECT;
            else
                event = UNKNOWN;

            String eventData = eventStr;
            if (event == DRIVER_STATE || event == LINK_SPEED)
                eventData = eventData.split(" ")[1];
            else if (event == STATE_CHANGE || event == EAP_FAILURE) {
                int ind = eventStr.indexOf(" ");
                if (ind != -1) {
                    eventData = eventStr.substring(ind + 1);
                }
            } else {
                int ind = eventStr.indexOf(" - ");
                if (ind != -1) {
                    eventData = eventStr.substring(ind + 3);
                }
            }

            if (event == STATE_CHANGE) {
                handleSupplicantStateChange(eventData);
            } else if (event == DRIVER_STATE) {
                handleDriverEvent(eventData);
            } else if (event == TERMINATING) {
                /**
                 * Close the supplicant connection if we see
                 * too many recv errors
                 */
                if (eventData.startsWith(WPA_RECV_ERROR_STR)) {
                    if (++mRecvErrors > MAX_RECV_ERRORS) {
                        if (DBG) {
                            Log.d(TAG, "too many recv errors, closing connection");
                        }
                    } else {
                        return false;
                    }
                }

                // notify and exit
                mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
                return true;
            } else if (event == EAP_FAILURE) {
                if (eventData.startsWith(EAP_AUTH_FAILURE_STR)) {
                    mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT);
                }
            } else if (event == ASSOC_REJECT) {
                mStateMachine.sendMessage(ASSOCIATION_REJECTION_EVENT);
            } else {
                handleEvent(event, eventData);
            }
            mRecvErrors = 0;
            return false;
        }

        private void handleDriverEvent(String state) {
            if (state == null) {
                return;
            }
            if (state.equals("HANGED")) {
                mStateMachine.sendMessage(DRIVER_HUNG_EVENT);
            }
        }

        /**
         * Handle all supplicant events except STATE-CHANGE
         * @param event the event type
         * @param remainder the rest of the string following the
         * event name and &quot;&#8195;&#8212;&#8195;&quot;
         */
        void handleEvent(int event, String remainder) {
            switch (event) {
                case DISCONNECTED:
                    handleNetworkStateChange(NetworkInfo.DetailedState.DISCONNECTED, remainder);
                    break;

                case CONNECTED:
                    handleNetworkStateChange(NetworkInfo.DetailedState.CONNECTED, remainder);
                    break;

                case SCAN_RESULTS:
                    mStateMachine.sendMessage(SCAN_RESULTS_EVENT);
                    break;

                case UNKNOWN:
                    break;
            }
        }

        private void handleWpsFailEvent(String dataString) {
            final Pattern p = Pattern.compile(WPS_FAIL_PATTERN);
            Matcher match = p.matcher(dataString);
            if (match.find()) {
                String cfgErr = match.group(1);
                String reason = match.group(2);

                if (reason != null) {
                    switch(Integer.parseInt(reason)) {
                        case REASON_TKIP_ONLY_PROHIBITED:
                            mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                    WifiManager.WPS_TKIP_ONLY_PROHIBITED, 0));
                            return;
                        case REASON_WEP_PROHIBITED:
                            mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                    WifiManager.WPS_WEP_PROHIBITED, 0));
                            return;
                    }
                }
                if (cfgErr != null) {
                    switch(Integer.parseInt(cfgErr)) {
                        case CONFIG_AUTH_FAILURE:
                            mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                    WifiManager.WPS_AUTH_FAILURE, 0));
                            return;
                        case CONFIG_MULTIPLE_PBC_DETECTED:
                            mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                    WifiManager.WPS_OVERLAP_ERROR, 0));
                            return;
                    }
                }
            }
            //For all other errors, return a generic internal error
            mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                    WifiManager.ERROR, 0));
        }

        /* <event> status=<err> and the special case of <event> reason=FREQ_CONFLICT */
        private P2pStatus p2pError(String dataString) {
            P2pStatus err = P2pStatus.UNKNOWN;
            String[] tokens = dataString.split(" ");
            if (tokens.length < 2) return err;
            String[] nameValue = tokens[1].split("=");
            if (nameValue.length != 2) return err;

            /* Handle the special case of reason=FREQ+CONFLICT */
            if (nameValue[1].equals("FREQ_CONFLICT")) {
                return P2pStatus.NO_COMMON_CHANNEL;
            }
            try {
                err = P2pStatus.valueOf(Integer.parseInt(nameValue[1]));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
            return err;
        }

        /**
         * Handle p2p events
         */
        private void handleP2pEvents(String dataString) {
            if (dataString.startsWith(P2P_DEVICE_FOUND_STR)) {
                mStateMachine.sendMessage(P2P_DEVICE_FOUND_EVENT, new WifiP2pDevice(dataString));
            } else if (dataString.startsWith(P2P_DEVICE_LOST_STR)) {
                mStateMachine.sendMessage(P2P_DEVICE_LOST_EVENT, new WifiP2pDevice(dataString));
            } else if (dataString.startsWith(P2P_FIND_STOPPED_STR)) {
                mStateMachine.sendMessage(P2P_FIND_STOPPED_EVENT);
            } else if (dataString.startsWith(P2P_GO_NEG_REQUEST_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_REQUEST_EVENT,
                        new WifiP2pConfig(dataString));
            } else if (dataString.startsWith(P2P_GO_NEG_SUCCESS_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_SUCCESS_EVENT);
            } else if (dataString.startsWith(P2P_GO_NEG_FAILURE_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_FAILURE_EVENT, p2pError(dataString));
            } else if (dataString.startsWith(P2P_GROUP_FORMATION_SUCCESS_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_FORMATION_SUCCESS_EVENT);
            } else if (dataString.startsWith(P2P_GROUP_FORMATION_FAILURE_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_FORMATION_FAILURE_EVENT, p2pError(dataString));
            } else if (dataString.startsWith(P2P_GROUP_STARTED_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_STARTED_EVENT, new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_GROUP_REMOVED_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_REMOVED_EVENT, new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_INVITATION_RECEIVED_STR)) {
                mStateMachine.sendMessage(P2P_INVITATION_RECEIVED_EVENT,
                        new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_INVITATION_RESULT_STR)) {
                mStateMachine.sendMessage(P2P_INVITATION_RESULT_EVENT, p2pError(dataString));
            } else if (dataString.startsWith(P2P_PROV_DISC_PBC_REQ_STR)) {
                mStateMachine.sendMessage(P2P_PROV_DISC_PBC_REQ_EVENT,
                        new WifiP2pProvDiscEvent(dataString));
            } else if (dataString.startsWith(P2P_PROV_DISC_PBC_RSP_STR)) {
                mStateMachine.sendMessage(P2P_PROV_DISC_PBC_RSP_EVENT,
                        new WifiP2pProvDiscEvent(dataString));
            } else if (dataString.startsWith(P2P_PROV_DISC_ENTER_PIN_STR)) {
                mStateMachine.sendMessage(P2P_PROV_DISC_ENTER_PIN_EVENT,
                        new WifiP2pProvDiscEvent(dataString));
            } else if (dataString.startsWith(P2P_PROV_DISC_SHOW_PIN_STR)) {
                mStateMachine.sendMessage(P2P_PROV_DISC_SHOW_PIN_EVENT,
                        new WifiP2pProvDiscEvent(dataString));
            } else if (dataString.startsWith(P2P_PROV_DISC_FAILURE_STR)) {
                mStateMachine.sendMessage(P2P_PROV_DISC_FAILURE_EVENT);
            } else if (dataString.startsWith(P2P_SERV_DISC_RESP_STR)) {
                List<WifiP2pServiceResponse> list = WifiP2pServiceResponse.newInstance(dataString);
                if (list != null) {
                    mStateMachine.sendMessage(P2P_SERV_DISC_RESP_EVENT, list);
                } else {
                    Log.e(TAG, "Null service resp " + dataString);
                }
            }
        }

        /**
         * Handle hostap events
         */
        private void handleHostApEvents(String dataString) {
            String[] tokens = dataString.split(" ");
            /* AP-STA-CONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=02:90:4c:a0:92:54 */
            if (tokens[0].equals(AP_STA_CONNECTED_STR)) {
                mStateMachine.sendMessage(AP_STA_CONNECTED_EVENT, new WifiP2pDevice(dataString));
            /* AP-STA-DISCONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=02:90:4c:a0:92:54 */
            } else if (tokens[0].equals(AP_STA_DISCONNECTED_STR)) {
                mStateMachine.sendMessage(AP_STA_DISCONNECTED_EVENT, new WifiP2pDevice(dataString));
            }
        }

        /**
         * Handle the supplicant STATE-CHANGE event
         * @param dataString New supplicant state string in the format:
         * id=network-id state=new-state
         */
        private void handleSupplicantStateChange(String dataString) {
            WifiSsid wifiSsid = null;
            int index = dataString.lastIndexOf("SSID=");
            if (index != -1) {
                wifiSsid = WifiSsid.createFromAsciiEncoded(
                        dataString.substring(index + 5));
            }
            String[] dataTokens = dataString.split(" ");

            String BSSID = null;
            int networkId = -1;
            int newState  = -1;
            for (String token : dataTokens) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) {
                    continue;
                }

                if (nameValue[0].equals("BSSID")) {
                    BSSID = nameValue[1];
                    continue;
                }

                int value;
                try {
                    value = Integer.parseInt(nameValue[1]);
                } catch (NumberFormatException e) {
                    continue;
                }

                if (nameValue[0].equals("id")) {
                    networkId = value;
                } else if (nameValue[0].equals("state")) {
                    newState = value;
                }
            }

            if (newState == -1) return;

            SupplicantState newSupplicantState = SupplicantState.INVALID;
            for (SupplicantState state : SupplicantState.values()) {
                if (state.ordinal() == newState) {
                    newSupplicantState = state;
                    break;
                }
            }
            if (newSupplicantState == SupplicantState.INVALID) {
                Log.w(TAG, "Invalid supplicant state: " + newState);
            }
            notifySupplicantStateChange(networkId, wifiSsid, BSSID, newSupplicantState);
        }

        private void handleNetworkStateChange(NetworkInfo.DetailedState newState, String data) {
            String BSSID = null;
            int networkId = -1;
            if (newState == NetworkInfo.DetailedState.CONNECTED) {
                Matcher match = mConnectedEventPattern.matcher(data);
                if (!match.find()) {
                    if (DBG) Log.d(TAG, "Could not find BSSID in CONNECTED event string");
                } else {
                    BSSID = match.group(1);
                    try {
                        networkId = Integer.parseInt(match.group(2));
                    } catch (NumberFormatException e) {
                        networkId = -1;
                    }
                }
                notifyNetworkStateChange(newState, BSSID, networkId);
            }
        }

        /**
         * Send the state machine a notification that the state of Wifi connectivity
         * has changed.
         * @param networkId the configured network on which the state change occurred
         * @param newState the new network state
         * @param BSSID when the new state is {@link DetailedState#CONNECTED
         * NetworkInfo.DetailedState.CONNECTED},
         * this is the MAC address of the access point. Otherwise, it
         * is {@code null}.
         */
        void notifyNetworkStateChange(NetworkInfo.DetailedState newState, String BSSID, int netId) {
            if (newState == NetworkInfo.DetailedState.CONNECTED) {
                Message m = mStateMachine.obtainMessage(NETWORK_CONNECTION_EVENT,
                        netId, 0, BSSID);
                mStateMachine.sendMessage(m);
            } else {
                Message m = mStateMachine.obtainMessage(NETWORK_DISCONNECTION_EVENT,
                        netId, 0, BSSID);
                mStateMachine.sendMessage(m);
            }
        }

        /**
         * Send the state machine a notification that the state of the supplicant
         * has changed.
         * @param networkId the configured network on which the state change occurred
         * @param wifiSsid network name
         * @param BSSID network address
         * @param newState the new {@code SupplicantState}
         */
        void notifySupplicantStateChange(int networkId, WifiSsid wifiSsid, String BSSID,
                SupplicantState newState) {
            mStateMachine.sendMessage(mStateMachine.obtainMessage(SUPPLICANT_STATE_CHANGE_EVENT,
                    new StateChangeResult(networkId, wifiSsid, BSSID, newState)));
        }
    }
}
