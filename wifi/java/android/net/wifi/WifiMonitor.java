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

import android.util.Log;
import android.util.Config;
import android.net.NetworkInfo;
import android.net.NetworkStateTracker;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Listens for events from the wpa_supplicant server, and passes them on
 * to the {@link WifiStateTracker} for handling. Runs in its own thread.
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
    private static final int UNKNOWN      = 8;

    /** All events coming from the supplicant start with this prefix */
    private static final String eventPrefix = "CTRL-EVENT-";
    private static final int eventPrefixLen = eventPrefix.length();

    /** All WPA events coming from the supplicant start with this prefix */
    private static final String wpaEventPrefix = "WPA:";
    private static final String passwordKeyMayBeIncorrectEvent =
       "pre-shared key may be incorrect";

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
    private static final String connectedEvent =    "CONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-DISCONNECTED - Disconnect event - remove keys
     * </pre>
     */
    private static final String disconnectedEvent = "DISCONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-STATE-CHANGE x
     * </pre>
     * <code>x</code> is the numerical value of the new state.
     */
    private static final String stateChangeEvent =  "STATE-CHANGE";
    /**
     * <pre>
     * CTRL-EVENT-SCAN-RESULTS ready
     * </pre>
     */
    private static final String scanResultsEvent =  "SCAN-RESULTS";

    /**
     * <pre>
     * CTRL-EVENT-LINK-SPEED x Mb/s
     * </pre>
     * {@code x} is the link speed in Mb/sec.
     */
    private static final String linkSpeedEvent = "LINK-SPEED";
    /**
     * <pre>
     * CTRL-EVENT-TERMINATING - signal x
     * </pre>
     * <code>x</code> is the signal that caused termination.
     */
    private static final String terminatingEvent =  "TERMINATING";
    /**
     * <pre>
     * CTRL-EVENT-DRIVER-STATE state
     * </pre>
     * <code>state</code> is either STARTED or STOPPED
     */
    private static final String driverStateEvent = "DRIVER-STATE";

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-CONNECTED - Connection to 00:1e:58:ec:d5:6d completed (reauth) [id=1 id_str=]</pre>
     */
    private static Pattern mConnectedEventPattern =
        Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) .* \\[id=([0-9]+) ");

    private final WifiStateTracker mWifiStateTracker;

    /**
     * This indicates the supplicant connection for the monitor is closed
     */
    private static final String monitorSocketClosed = "connection closed";

    /**
     * This indicates a read error on the monitor socket conenction
     */
    private static final String wpaRecvError = "recv error";

    /**
     * Tracks consecutive receive errors
     */
    private int mRecvErrors = 0;

    /**
     * Max errors before we close supplicant connection
     */
    private static final int MAX_RECV_ERRORS    = 10;

    public WifiMonitor(WifiStateTracker tracker) {
        mWifiStateTracker = tracker;
    }

    public void startMonitoring() {
        new MonitorThread().start();
    }

    public NetworkStateTracker getNetworkStateTracker() {
        return mWifiStateTracker;
    }

    class MonitorThread extends Thread {
        public MonitorThread() {
            super("WifiMonitor");
        }
        
        public void run() {

            if (connectToSupplicant()) {
                // Send a message indicating that it is now possible to send commands
                // to the supplicant
                mWifiStateTracker.notifySupplicantConnection();
            } else {
                mWifiStateTracker.notifySupplicantLost();
                return;
            }

            //noinspection InfiniteLoopStatement
            for (;;) {
                String eventStr = WifiNative.waitForEvent();

                // Skip logging the common but mostly uninteresting scan-results event
                if (Config.LOGD && eventStr.indexOf(scanResultsEvent) == -1) {
                    Log.v(TAG, "Event [" + eventStr + "]");
                }
                if (!eventStr.startsWith(eventPrefix)) {
                    if (eventStr.startsWith(wpaEventPrefix) &&
                            0 < eventStr.indexOf(passwordKeyMayBeIncorrectEvent)) {
                        handlePasswordKeyMayBeIncorrect();
                    }
                    continue;
                }

                String eventName = eventStr.substring(eventPrefixLen);
                int nameEnd = eventName.indexOf(' ');
                if (nameEnd != -1)
                    eventName = eventName.substring(0, nameEnd);
                if (eventName.length() == 0) {
                    if (Config.LOGD) Log.i(TAG, "Received wpa_supplicant event with empty event name");
                    continue;
                }
                /*
                 * Map event name into event enum
                 */
                int event;
                if (eventName.equals(connectedEvent))
                    event = CONNECTED;
                else if (eventName.equals(disconnectedEvent))
                    event = DISCONNECTED;
                else if (eventName.equals(stateChangeEvent))
                    event = STATE_CHANGE;
                else if (eventName.equals(scanResultsEvent))
                    event = SCAN_RESULTS;
                else if (eventName.equals(linkSpeedEvent))
                    event = LINK_SPEED;
                else if (eventName.equals(terminatingEvent))
                    event = TERMINATING;
                else if (eventName.equals(driverStateEvent)) {
                    event = DRIVER_STATE;
                }
                else
                    event = UNKNOWN;

                String eventData = eventStr;
                if (event == DRIVER_STATE || event == LINK_SPEED)
                    eventData = eventData.split(" ")[1];
                else if (event == STATE_CHANGE) {
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
                    if (eventData.startsWith(monitorSocketClosed)) {
                        if (Config.LOGD) {
                            Log.d(TAG, "Monitor socket is closed, exiting thread");
                        }
                        break;
                    }

                    /**
                     * Close the supplicant connection if we see
                     * too many recv errors
                     */
                    if (eventData.startsWith(wpaRecvError)) {
                        if (++mRecvErrors > MAX_RECV_ERRORS) {
                            if (Config.LOGD) {
                                Log.d(TAG, "too many recv errors, closing connection");
                            }
                        } else {
                            continue;
                        }
                    }

                    // notify and exit
                    mWifiStateTracker.notifySupplicantLost();
                    break;
                } else {
                    handleEvent(event, eventData);
                }
                mRecvErrors = 0;
            }
        }

        private boolean connectToSupplicant() {
            int connectTries = 0;

            while (true) {
                if (mWifiStateTracker.connectToSupplicant()) {
                    return true;
                }
                if (connectTries++ < 3) {
                    nap(5);
                } else {
                    break;
                }
            }
            return false;
        }

        private void handlePasswordKeyMayBeIncorrect() {
            mWifiStateTracker.notifyPasswordKeyMayBeIncorrect();
        }

        private void handleDriverEvent(String state) {
            if (state == null) {
                return;
            }
            if (state.equals("STOPPED")) {
                mWifiStateTracker.notifyDriverStopped();
            } else if (state.equals("STARTED")) {
                mWifiStateTracker.notifyDriverStarted();
            } else if (state.equals("HANGED")) {
                mWifiStateTracker.notifyDriverHung();
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
                    mWifiStateTracker.notifyScanResultsAvailable();
                    break;

                case UNKNOWN:
                    break;
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
            mWifiStateTracker.notifyStateChange(networkId, BSSID, newSupplicantState);
        }
    }

    private void handleNetworkStateChange(NetworkInfo.DetailedState newState, String data) {
        String BSSID = null;
        int networkId = -1;
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            Matcher match = mConnectedEventPattern.matcher(data);
            if (!match.find()) {
                if (Config.LOGD) Log.d(TAG, "Could not find BSSID in CONNECTED event string");
            } else {
                BSSID = match.group(1);
                try {
                    networkId = Integer.parseInt(match.group(2));
                } catch (NumberFormatException e) {
                    networkId = -1;
                }
            }
        }
        mWifiStateTracker.notifyStateChange(newState, BSSID, networkId);
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
