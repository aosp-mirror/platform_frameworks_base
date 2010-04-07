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

import android.net.DhcpInfo;

/**
 * Native calls for sending requests to the supplicant daemon, and for
 * receiving asynchronous events. All methods of the form "xxxxCommand()"
 * must be single-threaded, to avoid requests and responses initiated
 * from multiple threads from being intermingled.
 * <p/>
 * Note that methods whose names are not of the form "xxxCommand()" do
 * not talk to the supplicant daemon.
 *
 * {@hide}
 */
public class WifiNative {

    static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED = 0;
    static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED = 1;
    static final int BLUETOOTH_COEXISTENCE_MODE_SENSE = 2;
    
    public native static String getErrorString(int errorCode);

    public native static boolean loadDriver();
    
    public native static boolean unloadDriver();

    public native static boolean startSupplicant();
    
    public native static boolean stopSupplicant();

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
     * Start filtering out multicast packets, to reduce battery consumption
     * that would result from processing them, only to discard them.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean startPacketFiltering();

    /**
     * Stop filtering out multicast packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public native static boolean stopPacketFiltering();

    public native static boolean setPowerModeCommand(int mode);

    public native static int getPowerModeCommand();

    public native static boolean setNumAllowedChannelsCommand(int numChannels);

    public native static int getNumAllowedChannelsCommand();

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

    public native static boolean doDhcpRequest(DhcpInfo results);

    public native static String getDhcpError();

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    public native static String waitForEvent();
}
