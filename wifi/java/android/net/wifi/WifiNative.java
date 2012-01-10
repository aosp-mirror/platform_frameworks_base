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

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.InputStream;
import java.lang.Process;
import java.util.ArrayList;
import java.util.List;

/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 *
 * {@hide}
 */
public class WifiNative {

    private static final boolean DBG = false;
    private final String mTAG;
    private static final int DEFAULT_GROUP_OWNER_INTENT = 7;

    static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED = 0;
    static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED = 1;
    static final int BLUETOOTH_COEXISTENCE_MODE_SENSE = 2;

    String mInterface = "";

    public native static boolean loadDriver();

    public native static boolean isDriverLoaded();

    public native static boolean unloadDriver();

    public native static boolean startSupplicant(boolean p2pSupported);

    /* Sends a kill signal to supplicant. To be used when we have lost connection
       or when the supplicant is hung */
    public native static boolean killSupplicant();

    private native boolean connectToSupplicant(String iface);

    private native void closeSupplicantConnection(String iface);

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    private native String waitForEvent(String iface);

    private native boolean doBooleanCommand(String iface, String command);

    private native int doIntCommand(String iface, String command);

    private native String doStringCommand(String iface, String command);

    public WifiNative(String iface) {
        mInterface = iface;
        mTAG = "WifiNative-" + iface;
    }

    public boolean connectToSupplicant() {
        return connectToSupplicant(mInterface);
    }

    public void closeSupplicantConnection() {
        closeSupplicantConnection(mInterface);
    }

    public String waitForEvent() {
        return waitForEvent(mInterface);
    }

    private boolean doBooleanCommand(String command) {
        if (DBG) Log.d(mTAG, "doBoolean: " + command);
        return doBooleanCommand(mInterface, command);
    }

    private int doIntCommand(String command) {
        if (DBG) Log.d(mTAG, "doInt: " + command);
        return doIntCommand(mInterface, command);
    }

    private String doStringCommand(String command) {
        if (DBG) Log.d(mTAG, "doString: " + command);
        return doStringCommand(mInterface, command);
    }

    public boolean ping() {
        String pong = doStringCommand("PING");
        return (pong != null && pong.equals("PONG"));
    }

    public boolean scan() {
       return doBooleanCommand("SCAN");
    }

    public boolean setScanMode(boolean setActive) {
        if (setActive) {
            return doBooleanCommand("DRIVER SCAN-ACTIVE");
        } else {
            return doBooleanCommand("DRIVER SCAN-PASSIVE");
        }
    }

    /* Does a graceful shutdown of supplicant. Is a common stop function for both p2p and sta.
     *
     * Note that underneath we use a harsh-sounding "terminate" supplicant command
     * for a graceful stop and a mild-sounding "stop" interface
     * to kill the process
     */
    public boolean stopSupplicant() {
        return doBooleanCommand("TERMINATE");
    }

    public String listNetworks() {
        return doStringCommand("LIST_NETWORKS");
    }

    public int addNetwork() {
        return doIntCommand("ADD_NETWORK");
    }

    public boolean setNetworkVariable(int netId, String name, String value) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return false;
        return doBooleanCommand("SET_NETWORK " + netId + " " + name + " " + value);
    }

    public String getNetworkVariable(int netId, String name) {
        if (TextUtils.isEmpty(name)) return null;
        return doStringCommand("GET_NETWORK " + netId + " " + name);
    }

    public boolean removeNetwork(int netId) {
        return doBooleanCommand("REMOVE_NETWORK " + netId);
    }

    public boolean enableNetwork(int netId, boolean disableOthers) {
        if (disableOthers) {
            return doBooleanCommand("SELECT_NETWORK " + netId);
        } else {
            return doBooleanCommand("ENABLE_NETWORK " + netId);
        }
    }

    public boolean disableNetwork(int netId) {
        return doBooleanCommand("DISABLE_NETWORK " + netId);
    }

    public boolean reconnect() {
        return doBooleanCommand("RECONNECT");
    }

    public boolean reassociate() {
        return doBooleanCommand("REASSOCIATE");
    }

    public boolean disconnect() {
        return doBooleanCommand("DISCONNECT");
    }

    public String status() {
        return doStringCommand("STATUS");
    }

    public String getMacAddress() {
        //Macaddr = XX.XX.XX.XX.XX.XX
        String ret = doStringCommand("DRIVER MACADDR");
        if (!TextUtils.isEmpty(ret)) {
            String[] tokens = ret.split(" = ");
            if (tokens.length == 2) return tokens[1];
        }
        return null;
    }

    public String scanResults() {
        return doStringCommand("SCAN_RESULTS");
    }

    public boolean startDriver() {
        return doBooleanCommand("DRIVER START");
    }

    public boolean stopDriver() {
        return doBooleanCommand("DRIVER STOP");
    }


    /**
     * Start filtering out Multicast V4 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * Multicast filtering rules work as follows:
     *
     * The driver can filter multicast (v4 and/or v6) and broadcast packets when in
     * a power optimized mode (typically when screen goes off).
     *
     * In order to prevent the driver from filtering the multicast/broadcast packets, we have to
     * add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective
     *
     * DRIVER RXFILTER-ADD Num
     *   where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6
     *
     * and DRIVER RXFILTER-START
     * In order to stop the usage of these rules, we do
     *
     * DRIVER RXFILTER-STOP
     * DRIVER RXFILTER-REMOVE Num
     *   where Num is as described for RXFILTER-ADD
     *
     * The  SETSUSPENDOPT driver command overrides the filtering rules
     */
    public boolean startFilteringMulticastV4Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    public int getPowerMode() {
        String ret = doStringCommand("DRIVER GETPOWER");
        if (!TextUtils.isEmpty(ret)) {
            // reply comes back in the form "powermode = XX" where XX is the
            // number we're interested in.
            String[] tokens = ret.split(" = ");
            try {
                if (tokens.length == 2) return Integer.parseInt(tokens[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public boolean setPowerMode(int mode) {
        return doBooleanCommand("DRIVER POWERMODE " + mode);
    }

    public int getBand() {
       String ret = doStringCommand("DRIVER GETBAND");
        if (!TextUtils.isEmpty(ret)) {
            //reply is "BAND X" where X is the band
            String[] tokens = ret.split(" ");
            try {
                if (tokens.length == 2) return Integer.parseInt(tokens[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public boolean setBand(int band) {
        return doBooleanCommand("DRIVER SETBAND " + band);
    }

   /**
     * Sets the bluetooth coexistence mode.
     *
     * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
     * @return Whether the mode was successfully set.
     */
    public boolean setBluetoothCoexistenceMode(int mode) {
        return doBooleanCommand("DRIVER BTCOEXMODE " + mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isSet whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(boolean setCoexScanMode) {
        if (setCoexScanMode) {
            return doBooleanCommand("DRIVER BTCOEXSCAN-START");
        } else {
            return doBooleanCommand("DRIVER BTCOEXSCAN-STOP");
        }
    }

    public boolean saveConfig() {
        // Make sure we never write out a value for AP_SCAN other than 1
        return doBooleanCommand("AP_SCAN 1") && doBooleanCommand("SAVE_CONFIG");
    }

    public boolean setScanResultHandling(int mode) {
        return doBooleanCommand("AP_SCAN " + mode);
    }

    public boolean addToBlacklist(String bssid) {
        if (TextUtils.isEmpty(bssid)) return false;
        return doBooleanCommand("BLACKLIST " + bssid);
    }

    public boolean clearBlacklist() {
        return doBooleanCommand("BLACKLIST clear");
    }

    public boolean setSuspendOptimizations(boolean enabled) {
        if (enabled) {
            return doBooleanCommand("DRIVER SETSUSPENDOPT 0");
        } else {
            return doBooleanCommand("DRIVER SETSUSPENDOPT 1");
        }
    }

    public boolean setCountryCode(String countryCode) {
        return doBooleanCommand("DRIVER COUNTRY " + countryCode);
    }

    public void enableBackgroundScan(boolean enable) {
        //Note: BGSCAN-START and BGSCAN-STOP are documented in core/res/res/values/config.xml
        //and will need an update if the names are changed
        if (enable) {
            doBooleanCommand("DRIVER BGSCAN-START");
        } else {
            doBooleanCommand("DRIVER BGSCAN-STOP");
        }
    }

    public void setScanInterval(int scanInterval) {
        doBooleanCommand("SCAN_INTERVAL " + scanInterval);
    }

    /** Example output:
     * RSSI=-65
     * LINKSPEED=48
     * NOISE=9999
     * FREQUENCY=0
     */
    public String signalPoll() {
        return doStringCommand("SIGNAL_POLL");
    }

    public boolean startWpsPbc() {
        return doBooleanCommand("WPS_PBC");
    }

    public boolean startWpsPbc(String bssid) {
        return doBooleanCommand("WPS_PBC " + bssid);
    }

    public boolean startWpsPinKeypad(String pin) {
        return doBooleanCommand("WPS_PIN any " + pin);
    }

    public String startWpsPinDisplay(String bssid) {
        return doStringCommand("WPS_PIN " + bssid);
    }

    /* Configures an access point connection */
    public boolean startWpsRegistrar(String bssid, String pin) {
        return doBooleanCommand("WPS_REG " + bssid + " " + pin);
    }

    public boolean setPersistentReconnect(boolean enabled) {
        int value = (enabled == true) ? 1 : 0;
        return doBooleanCommand("SET persistent_reconnect " + value);
    }

    public boolean setDeviceName(String name) {
        return doBooleanCommand("SET device_name " + name);
    }

    public boolean setDeviceType(String type) {
        return doBooleanCommand("SET device_type " + type);
    }

    public boolean setConfigMethods(String cfg) {
        return doBooleanCommand("SET config_methods " + cfg);
    }

    public boolean setP2pSsidPostfix(String postfix) {
        return doBooleanCommand("SET p2p_ssid_postfix " + postfix);
    }

    public boolean p2pFind() {
        return doBooleanCommand("P2P_FIND");
    }

    public boolean p2pFind(int timeout) {
        if (timeout <= 0) {
            return p2pFind();
        }
        return doBooleanCommand("P2P_FIND " + timeout);
    }

    public boolean p2pStopFind() {
       return doBooleanCommand("P2P_STOP_FIND");
    }

    public boolean p2pListen() {
        return doBooleanCommand("P2P_LISTEN");
    }

    public boolean p2pListen(int timeout) {
        if (timeout <= 0) {
            return p2pListen();
        }
        return doBooleanCommand("P2P_LISTEN " + timeout);
    }

    public boolean p2pFlush() {
        return doBooleanCommand("P2P_FLUSH");
    }

    /* p2p_connect <peer device address> <pbc|pin|PIN#> [label|display|keypad]
        [persistent] [join|auth] [go_intent=<0..15>] [freq=<in MHz>] */
    public String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        if (config == null) return null;
        List<String> args = new ArrayList<String>();
        WpsInfo wps = config.wps;
        args.add(config.deviceAddress);

        switch (wps.setup) {
            case WpsInfo.PBC:
                args.add("pbc");
                break;
            case WpsInfo.DISPLAY:
                if (TextUtils.isEmpty(wps.pin)) {
                    args.add("pin");
                } else {
                    args.add(wps.pin);
                }
                args.add("display");
                break;
            case WpsInfo.KEYPAD:
                args.add(wps.pin);
                args.add("keypad");
                break;
            case WpsInfo.LABEL:
                args.add(wps.pin);
                args.add("label");
            default:
                break;
        }

        //TODO: Add persist behavior once the supplicant interaction is fixed for both
        // group and client scenarios
        /* Persist unless there is an explicit request to not do so*/
        //if (config.persist != WifiP2pConfig.Persist.NO) args.add("persistent");

        if (joinExistingGroup) args.add("join");

        //TODO: This can be adapted based on device plugged in state and
        //device battery state
        int groupOwnerIntent = config.groupOwnerIntent;
        if (groupOwnerIntent < 0 || groupOwnerIntent > 15) {
            groupOwnerIntent = DEFAULT_GROUP_OWNER_INTENT;
        }
        args.add("go_intent=" + groupOwnerIntent);

        String command = "P2P_CONNECT ";
        for (String s : args) command += s + " ";

        return doStringCommand(command);
    }

    public boolean p2pCancelConnect() {
        return doBooleanCommand("P2P_CANCEL");
    }

    public boolean p2pProvisionDiscovery(WifiP2pConfig config) {
        if (config == null) return false;

        switch (config.wps.setup) {
            case WpsInfo.PBC:
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " pbc");
            case WpsInfo.DISPLAY:
                //We are doing display, so provision discovery is keypad
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " keypad");
            case WpsInfo.KEYPAD:
                //We are doing keypad, so provision discovery is display
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " display");
            default:
                break;
        }
        return false;
    }

    public boolean p2pGroupAdd() {
        return doBooleanCommand("P2P_GROUP_ADD");
    }

    public boolean p2pGroupRemove(String iface) {
        if (iface == null) return false;
        return doBooleanCommand("P2P_GROUP_REMOVE " + iface);
    }

    public boolean p2pReject(String deviceAddress) {
        return doBooleanCommand("P2P_REJECT " + deviceAddress);
    }

    /* Invite a peer to a group */
    public boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        if (deviceAddress == null) return false;

        if (group == null) {
            return doBooleanCommand("P2P_INVITE peer=" + deviceAddress);
        } else {
            return doBooleanCommand("P2P_INVITE group=" + group.getInterface()
                    + " peer=" + deviceAddress + " go_dev_addr=" + group.getOwner().deviceAddress);
        }
    }

    /* Reinvoke a persistent connection */
    public boolean p2pReinvoke(int netId, String deviceAddress) {
        if (deviceAddress == null || netId < 0) return false;

        return doBooleanCommand("P2P_INVITE persistent=" + netId + " peer=" + deviceAddress);
    }


    public String p2pGetInterfaceAddress(String deviceAddress) {
        if (deviceAddress == null) return null;

        //  "p2p_peer deviceAddress" returns a multi-line result containing
        //      intended_addr=fa:7b:7a:42:82:13
        String peerInfo = p2pPeer(deviceAddress);
        if (peerInfo == null) return null;
        String[] tokens= peerInfo.split("\n");

        for (String token : tokens) {
            //TODO: update from interface_addr when wpa_supplicant implementation is fixed
            if (token.startsWith("intended_addr=")) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                return nameValue[1];
            }
        }
        return null;
    }

    public String p2pGetDeviceAddress() {
        String status = status();
        if (status == null) return "";

        String[] tokens = status.split("\n");
        for (String token : tokens) {
            if (token.startsWith("p2p_device_address=")) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                return nameValue[1];
            }
        }
        return "";
    }

    public String p2pPeer(String deviceAddress) {
        return doStringCommand("P2P_PEER " + deviceAddress);
    }
}
