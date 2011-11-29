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
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.StateChangeResult;
import android.os.Message;
import android.util.Log;


import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Listens for events from the wpa_supplicant server, and passes them on
 * to the {@link StateMachine} for handling. Runs in its own thread.
 *
 * @hide
 */
public class WifiMonitor {

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
    private static final int UNKNOWN      = 9;

    /** All events coming from the supplicant start with this prefix */
    private static final String EVENT_PREFIX_STR = "CTRL-EVENT-";
    private static final int EVENT_PREFIX_LEN_STR = EVENT_PREFIX_STR.length();

    /** All WPA events coming from the supplicant start with this prefix */
    private static final String WPA_EVENT_PREFIX_STR = "WPA:";
    private static final String PASSWORD_MAY_BE_INCORRECT_STR =
       "pre-shared key may be incorrect";

    /* WPS events */
    private static final String WPS_OVERLAP_STR = "WPS-OVERLAP-DETECTED";

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

    /* P2P-GO-NEG-REQUEST 42:fc:89:a8:96:09 dev_passwd_id=4 */
    private static final String P2P_GO_NEG_REQUEST_STR = "P2P-GO-NEG-REQUEST";

    private static final String P2P_GO_NEG_SUCCESS_STR = "P2P-GO-NEG-SUCCESS";

    private static final String P2P_GO_NEG_FAILURE_STR = "P2P-GO-NEG-FAILURE";

    private static final String P2P_GROUP_FORMATION_SUCCESS_STR =
            "P2P-GROUP-FORMATION-SUCCESS";

    private static final String P2P_GROUP_FORMATION_FAILURE_STR =
            "P2P-GROUP-FORMATION-FAILURE";

    /* P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
       [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|passphrase="fKG4jMe3"]
       go_dev_addr=fa:7b:7a:42:02:13 */
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

    private static final String HOST_AP_EVENT_PREFIX_STR = "AP";
    /* AP-STA-CONNECTED 42:fc:89:a8:96:09 */
    private static final String AP_STA_CONNECTED_STR = "AP-STA-CONNECTED";
    /* AP-STA-DISCONNECTED 42:fc:89:a8:96:09 */
    private static final String AP_STA_DISCONNECTED_STR = "AP-STA-DISCONNECTED";

    private final StateMachine mStateMachine;

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
    /* WPS overlap detected */
    public static final int WPS_OVERLAP_EVENT                    = BASE + 8;
    /* Driver was hung */
    public static final int DRIVER_HUNG_EVENT                    = BASE + 9;

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

    /* hostap events */
    public static final int AP_STA_DISCONNECTED_EVENT            = BASE + 41;
    public static final int AP_STA_CONNECTED_EVENT               = BASE + 42;

    /**
     * This indicates the supplicant connection for the monitor is closed
     */
    private static final String MONITOR_SOCKET_CLOSED_STR = "connection closed";

    /**
     * This indicates a read error on the monitor socket conenction
     */
    private static final String WPA_RECV_ERROR_STR = "recv error";

    /**
     * Tracks consecutive receive errors
     */
    private int mRecvErrors = 0;

    /**
     * Max errors before we close supplicant connection
     */
    private static final int MAX_RECV_ERRORS    = 10;

    public WifiMonitor(StateMachine wifiStateMachine) {
        mStateMachine = wifiStateMachine;
    }

    public void startMonitoring() {
        new MonitorThread().start();
    }

    class MonitorThread extends Thread {
        public MonitorThread() {
            super("WifiMonitor");
        }

        public void run() {

            if (connectToSupplicant()) {
                // Send a message indicating that it is now possible to send commands
                // to the supplicant
                mStateMachine.sendMessage(SUP_CONNECTION_EVENT);
            } else {
                mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
                return;
            }

            //noinspection InfiniteLoopStatement
            for (;;) {
                String eventStr = WifiNative.waitForEvent();

                // Skip logging the common but mostly uninteresting scan-results event
                if (false && eventStr.indexOf(SCAN_RESULTS_STR) == -1) {
                    Log.d(TAG, "Event [" + eventStr + "]");
                }
                if (!eventStr.startsWith(EVENT_PREFIX_STR)) {
                    if (eventStr.startsWith(WPA_EVENT_PREFIX_STR) &&
                            0 < eventStr.indexOf(PASSWORD_MAY_BE_INCORRECT_STR)) {
                        mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT);
                    } else if (eventStr.startsWith(WPS_OVERLAP_STR)) {
                        mStateMachine.sendMessage(WPS_OVERLAP_EVENT);
                    } else if (eventStr.startsWith(P2P_EVENT_PREFIX_STR)) {
                        handleP2pEvents(eventStr);
                    } else if (eventStr.startsWith(HOST_AP_EVENT_PREFIX_STR)) {
                        handleHostApEvents(eventStr);
                    }
                    continue;
                }

                String eventName = eventStr.substring(EVENT_PREFIX_LEN_STR);
                int nameEnd = eventName.indexOf(' ');
                if (nameEnd != -1)
                    eventName = eventName.substring(0, nameEnd);
                if (eventName.length() == 0) {
                    if (false) Log.i(TAG, "Received wpa_supplicant event with empty event name");
                    continue;
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
                     * If monitor socket is closed, we have already
                     * stopped the supplicant, simply exit the monitor thread
                     */
                    if (eventData.startsWith(MONITOR_SOCKET_CLOSED_STR)) {
                        if (false) {
                            Log.d(TAG, "Monitor socket is closed, exiting thread");
                        }
                        break;
                    }

                    /**
                     * Close the supplicant connection if we see
                     * too many recv errors
                     */
                    if (eventData.startsWith(WPA_RECV_ERROR_STR)) {
                        if (++mRecvErrors > MAX_RECV_ERRORS) {
                            if (false) {
                                Log.d(TAG, "too many recv errors, closing connection");
                            }
                        } else {
                            continue;
                        }
                    }

                    // notify and exit
                    mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
                    break;
                } else if (event == EAP_FAILURE) {
                    if (eventData.startsWith(EAP_AUTH_FAILURE_STR)) {
                        mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT);
                    }
                } else {
                    handleEvent(event, eventData);
                }
                mRecvErrors = 0;
            }
        }

        private boolean connectToSupplicant() {
            int connectTries = 0;

            while (true) {
                if (WifiNative.connectToSupplicant()) {
                    return true;
                }
                if (connectTries++ < 5) {
                    nap(1);
                } else {
                    break;
                }
            }
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

        /**
         * Handle p2p events
         */
        private void handleP2pEvents(String dataString) {
            if (dataString.startsWith(P2P_DEVICE_FOUND_STR)) {
                mStateMachine.sendMessage(P2P_DEVICE_FOUND_EVENT, new WifiP2pDevice(dataString));
            } else if (dataString.startsWith(P2P_DEVICE_LOST_STR)) {
                mStateMachine.sendMessage(P2P_DEVICE_LOST_EVENT, new WifiP2pDevice(dataString));
            } else if (dataString.startsWith(P2P_GO_NEG_REQUEST_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_REQUEST_EVENT,
                        new WifiP2pConfig(dataString));
            } else if (dataString.startsWith(P2P_GO_NEG_SUCCESS_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_SUCCESS_EVENT);
            } else if (dataString.startsWith(P2P_GO_NEG_FAILURE_STR)) {
                mStateMachine.sendMessage(P2P_GO_NEGOTIATION_FAILURE_EVENT);
            } else if (dataString.startsWith(P2P_GROUP_FORMATION_SUCCESS_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_FORMATION_SUCCESS_EVENT);
            } else if (dataString.startsWith(P2P_GROUP_FORMATION_FAILURE_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_FORMATION_FAILURE_EVENT);
            } else if (dataString.startsWith(P2P_GROUP_STARTED_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_STARTED_EVENT, new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_GROUP_REMOVED_STR)) {
                mStateMachine.sendMessage(P2P_GROUP_REMOVED_EVENT, new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_INVITATION_RECEIVED_STR)) {
                mStateMachine.sendMessage(P2P_INVITATION_RECEIVED_EVENT,
                        new WifiP2pGroup(dataString));
            } else if (dataString.startsWith(P2P_INVITATION_RESULT_STR)) {
                String[] tokens = dataString.split(" ");
                if (tokens.length != 2) return;
                String[] nameValue = tokens[1].split("=");
                if (nameValue.length != 2) return;
                mStateMachine.sendMessage(P2P_INVITATION_RESULT_EVENT, nameValue[1]);
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
            }
        }

        /**
         * Handle hostap events
         */
        private void handleHostApEvents(String dataString) {
            String[] tokens = dataString.split(" ");
            if (tokens[0].equals(AP_STA_CONNECTED_STR)) {
                mStateMachine.sendMessage(AP_STA_CONNECTED_EVENT, tokens[1]);
            } else if (tokens[0].equals(AP_STA_DISCONNECTED_STR)) {
                mStateMachine.sendMessage(AP_STA_DISCONNECTED_EVENT, tokens[1]);
            }
        }

        /**
         * Handle the supplicant STATE-CHANGE event
         * @param dataString New supplicant state string in the format:
         * id=network-id state=new-state
         */
        private void handleSupplicantStateChange(String dataString) {
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
                    Log.w(TAG, "STATE-CHANGE non-integer parameter: " + token);
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
            notifySupplicantStateChange(networkId, BSSID, newSupplicantState);
        }
    }

    private void handleNetworkStateChange(NetworkInfo.DetailedState newState, String data) {
        String BSSID = null;
        int networkId = -1;
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            Matcher match = mConnectedEventPattern.matcher(data);
            if (!match.find()) {
                if (false) Log.d(TAG, "Could not find BSSID in CONNECTED event string");
            } else {
                BSSID = match.group(1);
                try {
                    networkId = Integer.parseInt(match.group(2));
                } catch (NumberFormatException e) {
                    networkId = -1;
                }
            }
        }
        notifyNetworkStateChange(newState, BSSID, networkId);
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
     * @param newState the new {@code SupplicantState}
     */
    void notifySupplicantStateChange(int networkId, String BSSID, SupplicantState newState) {
        mStateMachine.sendMessage(mStateMachine.obtainMessage(SUPPLICANT_STATE_CHANGE_EVENT,
                new StateChangeResult(networkId, BSSID, newState)));
    }

    /**
     * Sleep for a period of time.
     * @param secs the number of seconds to sleep
     */
    private static void nap(int secs) {
        try {
            Thread.sleep(secs * 1000);
        } catch (InterruptedException ignore) {
        }
    }
}
