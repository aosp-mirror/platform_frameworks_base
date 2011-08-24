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
import android.util.Log;

import java.io.InputStream;
import java.lang.Process;
import java.util.ArrayList;
import java.util.List;

/**
 * Native calls for sending requests to the supplicant daemon, and for
 * receiving asynchronous events. All methods of the form "xxxxCommand()"
 * must be single-threaded, to avoid requests and responses initiated
 * from multiple threads from being intermingled.
 * <p/>
 * Note that methods whose names are not of the form "xxxCommand()" do
 * not talk to the supplicant daemon.
 * Also, note that all WifiNative calls should happen in the
 * WifiStateTracker class except for waitForEvent() call which is
 * on a separate monitor channel for WifiMonitor
 *
 * TODO: clean up the API and move the functionality from JNI to here. We should
 * be able to get everything done with doBooleanCommand, doIntCommand and
 * doStringCommand native commands
 *
 * {@hide}
 */
public class WifiNative {

    static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED = 0;
    static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED = 1;
    static final int BLUETOOTH_COEXISTENCE_MODE_SENSE = 2;

    public native static String getErrorString(int errorCode);

    public native static boolean loadDriver();

    public native static boolean isDriverLoaded();

    public native static boolean unloadDriver();

    public native static boolean startSupplicant();

    /* Does a graceful shutdown of supplicant.
     *
     * Note that underneath we use a harsh-sounding "terminate" supplicant command
     * for a graceful stop and a mild-sounding "stop" interface
     * to kill the process
     */
    public native static boolean stopSupplicant();

    /* Sends a kill signal to supplicant. To be used when we have lost connection
       or when the supplicant is hung */
    public native static boolean killSupplicant();

    public native static boolean connectToSupplicant();

    public native static void closeSupplicantConnection();

    public native static boolean pingCommand();

    public native static boolean scanCommand(boolean forceActive);

    public native static boolean setScanModeCommand(boolean setActive);

    public native static String listNetworksCommand();

    public native static int addNetworkCommand();

    public native static boolean setNetworkVariableCommand(int netId, String name, String value);

    public native static String getNetworkVariableCommand(int netId, String name);

    public native static boolean removeNetworkCommand(int netId);

    public native static boolean enableNetworkCommand(int netId, boolean disableOthers);

    public native static boolean disableNetworkCommand(int netId);

    public native static boolean reconnectCommand();

    public native static boolean reassociateCommand();

    public native static boolean disconnectCommand();

    public native static String statusCommand();

    public native static int getRssiCommand();

    public native static int getRssiApproxCommand();

    public native static int getLinkSpeedCommand();

    public native static String getMacAddressCommand();

    public native static String scanResultsCommand();

    public native static boolean startDriverCommand();

    public native static boolean stopDriverCommand();


    /**
     * Start filtering out Multicast V4 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean startFilteringMulticastV4Packets();

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean stopFilteringMulticastV4Packets();

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean startFilteringMulticastV6Packets();

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean stopFilteringMulticastV6Packets();

    public native static boolean setPowerModeCommand(int mode);

    public native static int getBandCommand();

    public native static boolean setBandCommand(int band);

    public native static int getPowerModeCommand();

    /**
     * Sets the bluetooth coexistence mode.
     *
     * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
     * @return Whether the mode was successfully set.
     */
    public native static boolean setBluetoothCoexistenceModeCommand(int mode);

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isSet whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public native static boolean setBluetoothCoexistenceScanModeCommand(boolean setCoexScanMode);

    public native static boolean saveConfigCommand();

    public native static boolean reloadConfigCommand();

    public native static boolean setScanResultHandlingCommand(int mode);

    public native static boolean addToBlacklistCommand(String bssid);

    public native static boolean clearBlacklistCommand();

    public native static boolean startWpsPbcCommand(String bssid);

    public native static boolean startWpsWithPinFromAccessPointCommand(String bssid, String apPin);

    public native static String startWpsWithPinFromDeviceCommand(String bssid);

    public native static boolean setSuspendOptimizationsCommand(boolean enabled);

    public native static boolean setCountryCodeCommand(String countryCode);

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    public native static String waitForEvent();

    public native static void enableBackgroundScanCommand(boolean enable);

    public native static void setScanIntervalCommand(int scanInterval);

    private native static boolean doBooleanCommand(String command);

    private native static int doIntCommand(String command);

    private native static String doStringCommand(String command);

    public static boolean wpsPbc() {
        return doBooleanCommand("WPS_PBC");
    }

    public static boolean wpsPin(String pin) {
        return doBooleanCommand("WPS_PIN any " + pin);
    }

    public static boolean p2pFind() {
        return doBooleanCommand("P2P_FIND");
    }

    public static boolean p2pFind(int timeout) {
        if (timeout <= 0) {
            return p2pFind();
        }
        return doBooleanCommand("P2P_FIND " + timeout);
    }

    public static boolean p2pListen() {
        return doBooleanCommand("P2P_LISTEN");
    }

    public static boolean p2pListen(int timeout) {
        if (timeout <= 0) {
            return p2pListen();
        }
        return doBooleanCommand("P2P_LISTEN " + timeout);
    }

    public static boolean p2pFlush() {
        return doBooleanCommand("P2P_FLUSH");
    }

    /* p2p_connect <peer device address> <pbc|pin|PIN#> [label|display|keypad]
        [persistent] [join|auth] [go_intent=<0..15>] [freq=<in MHz>] */
    public static String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        if (config == null) return null;
        List<String> args = new ArrayList<String>();
        WpsConfiguration wpsConfig = config.wpsConfig;
        args.add(config.deviceAddress);

        switch (wpsConfig.setup) {
            case PBC:
                args.add("pbc");
                break;
            case DISPLAY:
                //TODO: pass the pin back for display
                args.add("pin");
                args.add("display");
                break;
            case KEYPAD:
                args.add(wpsConfig.pin);
                args.add("keypad");
                break;
            case LABEL:
                args.add(wpsConfig.pin);
                args.add("label");
            default:
                break;
        }

        /* Persist unless there is an explicit request to not do so*/
        if (config.persist != WifiP2pConfig.Persist.NO) args.add("persistent");
        if (joinExistingGroup) args.add("join");

        int groupOwnerIntent = config.groupOwnerIntent;
        if (groupOwnerIntent < 0 || groupOwnerIntent > 15) {
            groupOwnerIntent = 3; //default value
        }
        args.add("go_intent=" + groupOwnerIntent);

        String command = "P2P_CONNECT ";
        for (String s : args) command += s + " ";

        return doStringCommand(command);
    }

    public static boolean p2pGroupAdd() {
        return doBooleanCommand("P2P_GROUP_ADD");
    }

    public static boolean p2pGroupRemove(String iface) {
        if (iface == null) return false;
        return doBooleanCommand("P2P_GROUP_REMOVE " + iface);
    }

    public static boolean p2pReject(String deviceAddress) {
        return doBooleanCommand("P2P_REJECT " + deviceAddress);
    }

    /* Invite a peer to a group */
    public static boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        if (deviceAddress == null) return false;

        if (group == null) {
            return doBooleanCommand("P2P_INVITE peer=" + deviceAddress);
        } else {
            return doBooleanCommand("P2P_INVITE group=" + group.getInterface()
                    + " peer=" + deviceAddress + " go_dev_addr=" + group.getOwner().deviceAddress);
        }
    }

    /* Reinvoke a persistent connection */
    public static boolean p2pReinvoke(int netId, String deviceAddress) {
        if (deviceAddress == null || netId < 0) return false;

        return doBooleanCommand("P2P_INVITE persistent=" + netId + " peer=" + deviceAddress);
    }


    public static String p2pGetInterfaceAddress(String deviceAddress) {
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

    public static String p2pPeer(String deviceAddress) {
        return doStringCommand("P2P_PEER " + deviceAddress);
    }
}
