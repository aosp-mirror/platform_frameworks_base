/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration.Status;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * This class provides the API to manage configured
 * wifi networks. The API is not thread safe is being
 * used only from WifiStateMachine.
 *
 * It deals with the following
 * - Add/update/remove a WifiConfiguration
 * - Maintain a list of configured networks for quick access
 * TODO:
 * - handle static IP per configuration
 * - handle proxy per configuration
 */
class WifiConfigStore {

    private static Context sContext;
    private static final String TAG = "WifiConfigStore";

    private static List<WifiConfiguration> sConfiguredNetworks = new ArrayList<WifiConfiguration>();
    /* Tracks the highest priority of configured networks */
    private static int sLastPriority = -1;

    /**
     * Initialize context, fetch the list of configured networks
     * and enable all stored networks in supplicant.
     */
    static void initialize(Context context) {
        Log.d(TAG, "Updating config and enabling all networks");
        sContext = context;
        updateConfiguredNetworks();
        enableAllNetworks();
    }

    /**
     * Fetch the list of currently configured networks
     * @return List of networks
     */
    static List<WifiConfiguration> getConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        synchronized (sConfiguredNetworks) {
            for (WifiConfiguration config : sConfiguredNetworks) {
                networks.add(config.clone());
            }
        }
        return networks;
    }

    /**
     * enable all networks and save config. This will be a no-op if the list
     * of configured networks indicates all networks as being enabled
     */
    static void enableAllNetworks() {
        for (WifiConfiguration config : sConfiguredNetworks) {
            if(config != null && config.status == Status.DISABLED) {
                WifiNative.enableNetworkCommand(config.networkId, false);
            }
        }

        WifiNative.saveConfigCommand();
        updateConfigAndSendChangeBroadcast();
    }

    /**
     * Selects the specified network config for connection. This involves
     * addition/update of the specified config, updating the priority of
     * all the networks and enabling the given network while disabling others.
     *
     * Selecting a network will leave the other networks disabled and
     * a call to enableAllNetworks() needs to be issued upon a connection
     * or a failure event from supplicant
     *
     * @param config The configuration details in WifiConfiguration
     */
    static void selectNetwork(WifiConfiguration config) {
        if (config != null) {
            int netId = addOrUpdateNetworkNative(config);
            selectNetwork(netId);
        }
    }

    /**
     * Selects the specified network for connection. This involves
     * updating the priority of all the networks and enabling the given
     * network while disabling others.
     *
     * Selecting a network will leave the other networks disabled and
     * a call to enableAllNetworks() needs to be issued upon a connection
     * or a failure event from supplicant
     *
     * @param netId network to select for connection
     */
    static void selectNetwork(int netId) {
        // Reset the priority of each network at start or if it goes too high.
        if (sLastPriority == -1 || sLastPriority > 1000000) {
            for (WifiConfiguration conf : sConfiguredNetworks) {
                if (conf.networkId != -1) {
                    conf.priority = 0;
                    addOrUpdateNetworkNative(conf);
                }
            }
            sLastPriority = 0;
        }

        // Set to the highest priority and save the configuration.
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = netId;
        config.priority = ++sLastPriority;

        addOrUpdateNetworkNative(config);
        WifiNative.saveConfigCommand();

        /* Enable the given network while disabling all other networks */
        WifiNative.enableNetworkCommand(netId, true);

        /* update the configured networks list but not send a
         * broadcast to avoid a fetch from settings
         * during this temporary disabling of networks
         */
        updateConfiguredNetworks();
    }

    /**
     * Add/update the specified configuration and save config
     *
     * @param config WifiConfiguration to be saved
     */
    static void saveNetwork(WifiConfiguration config) {
        int netId = addOrUpdateNetworkNative(config);
        /* enable a new network */
        if (config.networkId < 0) {
            WifiNative.enableNetworkCommand(netId, false);
        }
        WifiNative.saveConfigCommand();
        updateConfigAndSendChangeBroadcast();
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     */
    static void forgetNetwork(int netId) {
        WifiNative.removeNetworkCommand(netId);
        WifiNative.saveConfigCommand();
        updateConfigAndSendChangeBroadcast();
    }

    /**
     * Add/update a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful saveNetwork() is used by the
     * state machine
     *
     * @param config wifi configuration to add/update
     */
    static int addOrUpdateNetwork(WifiConfiguration config) {
        int ret = addOrUpdateNetworkNative(config);
        updateConfigAndSendChangeBroadcast();
        return ret;
    }

    /**
     * Remove a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful forgetNetwork() is used by the
     * state machine for network removal
     *
     * @param netId network to be removed
     */
    static boolean removeNetwork(int netId) {
        boolean ret = WifiNative.removeNetworkCommand(netId);
        updateConfigAndSendChangeBroadcast();
        return ret;
    }

    /**
     * Enable a network. Note that there is no saveConfig operation.
     * This function is retained for compatibility with the public
     * API. The more powerful selectNetwork()/saveNetwork() is used by the
     * state machine for connecting to a network
     *
     * @param netId network to be removed
     */
    static boolean enableNetwork(int netId, boolean disableOthers) {
        boolean ret = WifiNative.enableNetworkCommand(netId, disableOthers);
        updateConfigAndSendChangeBroadcast();
        return ret;
    }

    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     */
    static boolean disableNetwork(int netId) {
        boolean ret = WifiNative.disableNetworkCommand(netId);
        updateConfigAndSendChangeBroadcast();
        return ret;
    }

    /**
     * Save the configured networks in supplicant to disk
     */
    static boolean saveConfig() {
        return WifiNative.saveConfigCommand();
    }

    private static void updateConfigAndSendChangeBroadcast() {
        updateConfiguredNetworks();
        if (!ActivityManagerNative.isSystemReady()) return;
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONFIG_CHANGED_ACTION);
        sContext.sendBroadcast(intent);
    }

    private static void updateConfiguredNetworks() {
        String listStr = WifiNative.listNetworksCommand();
        sLastPriority = 0;

        synchronized (sConfiguredNetworks) {
            sConfiguredNetworks.clear();

            if (listStr == null)
                return;

            String[] lines = listStr.split("\n");
            // Skip the first line, which is a header
            for (int i = 1; i < lines.length; i++) {
                String[] result = lines[i].split("\t");
                // network-id | ssid | bssid | flags
                WifiConfiguration config = new WifiConfiguration();
                try {
                    config.networkId = Integer.parseInt(result[0]);
                } catch(NumberFormatException e) {
                    continue;
                }
                if (result.length > 3) {
                    if (result[3].indexOf("[CURRENT]") != -1)
                        config.status = WifiConfiguration.Status.CURRENT;
                    else if (result[3].indexOf("[DISABLED]") != -1)
                        config.status = WifiConfiguration.Status.DISABLED;
                    else
                        config.status = WifiConfiguration.Status.ENABLED;
                } else {
                    config.status = WifiConfiguration.Status.ENABLED;
                }
                readNetworkVariables(config);
                if (config.priority > sLastPriority) {
                    sLastPriority = config.priority;
                }
                sConfiguredNetworks.add(config);
            }
        }
    }

    private static int addOrUpdateNetworkNative(WifiConfiguration config) {
        /*
         * If the supplied networkId is -1, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        int netId = config.networkId;
        boolean newNetwork = netId == -1;
        // networkId of -1 means we want to create a new network

        if (newNetwork) {
            netId = WifiNative.addNetworkCommand();
            if (netId < 0) {
                Log.e(TAG, "Failed to add a network!");
                return -1;
          }
        }

        setVariables: {

            if (config.SSID != null &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.ssidVarName,
                        config.SSID)) {
                Log.d(TAG, "failed to set SSID: "+config.SSID);
                break setVariables;
            }

            if (config.BSSID != null &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.bssidVarName,
                        config.BSSID)) {
                Log.d(TAG, "failed to set BSSID: "+config.BSSID);
                break setVariables;
            }

            String allowedKeyManagementString =
                makeString(config.allowedKeyManagement, WifiConfiguration.KeyMgmt.strings);
            if (config.allowedKeyManagement.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.KeyMgmt.varName,
                        allowedKeyManagementString)) {
                Log.d(TAG, "failed to set key_mgmt: "+
                        allowedKeyManagementString);
                break setVariables;
            }

            String allowedProtocolsString =
                makeString(config.allowedProtocols, WifiConfiguration.Protocol.strings);
            if (config.allowedProtocols.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.Protocol.varName,
                        allowedProtocolsString)) {
                Log.d(TAG, "failed to set proto: "+
                        allowedProtocolsString);
                break setVariables;
            }

            String allowedAuthAlgorithmsString =
                makeString(config.allowedAuthAlgorithms, WifiConfiguration.AuthAlgorithm.strings);
            if (config.allowedAuthAlgorithms.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.AuthAlgorithm.varName,
                        allowedAuthAlgorithmsString)) {
                Log.d(TAG, "failed to set auth_alg: "+
                        allowedAuthAlgorithmsString);
                break setVariables;
            }

            String allowedPairwiseCiphersString =
                    makeString(config.allowedPairwiseCiphers,
                    WifiConfiguration.PairwiseCipher.strings);
            if (config.allowedPairwiseCiphers.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.PairwiseCipher.varName,
                        allowedPairwiseCiphersString)) {
                Log.d(TAG, "failed to set pairwise: "+
                        allowedPairwiseCiphersString);
                break setVariables;
            }

            String allowedGroupCiphersString =
                makeString(config.allowedGroupCiphers, WifiConfiguration.GroupCipher.strings);
            if (config.allowedGroupCiphers.cardinality() != 0 &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.GroupCipher.varName,
                        allowedGroupCiphersString)) {
                Log.d(TAG, "failed to set group: "+
                        allowedGroupCiphersString);
                break setVariables;
            }

            // Prevent client screw-up by passing in a WifiConfiguration we gave it
            // by preventing "*" as a key.
            if (config.preSharedKey != null && !config.preSharedKey.equals("*") &&
                    !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.pskVarName,
                        config.preSharedKey)) {
                Log.d(TAG, "failed to set psk: "+config.preSharedKey);
                break setVariables;
            }

            boolean hasSetKey = false;
            if (config.wepKeys != null) {
                for (int i = 0; i < config.wepKeys.length; i++) {
                    // Prevent client screw-up by passing in a WifiConfiguration we gave it
                    // by preventing "*" as a key.
                    if (config.wepKeys[i] != null && !config.wepKeys[i].equals("*")) {
                        if (!WifiNative.setNetworkVariableCommand(
                                    netId,
                                    WifiConfiguration.wepKeyVarNames[i],
                                    config.wepKeys[i])) {
                            Log.d(TAG,
                                    "failed to set wep_key"+i+": " +
                                    config.wepKeys[i]);
                            break setVariables;
                        }
                        hasSetKey = true;
                    }
                }
            }

            if (hasSetKey) {
                if (!WifiNative.setNetworkVariableCommand(
                            netId,
                            WifiConfiguration.wepTxKeyIdxVarName,
                            Integer.toString(config.wepTxKeyIndex))) {
                    Log.d(TAG,
                            "failed to set wep_tx_keyidx: "+
                            config.wepTxKeyIndex);
                    break setVariables;
                }
            }

            if (!WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.priorityVarName,
                        Integer.toString(config.priority))) {
                Log.d(TAG, config.SSID + ": failed to set priority: "
                        +config.priority);
                break setVariables;
            }

            if (config.hiddenSSID && !WifiNative.setNetworkVariableCommand(
                        netId,
                        WifiConfiguration.hiddenSSIDVarName,
                        Integer.toString(config.hiddenSSID ? 1 : 0))) {
                Log.d(TAG, config.SSID + ": failed to set hiddenSSID: "+
                        config.hiddenSSID);
                break setVariables;
            }

            for (WifiConfiguration.EnterpriseField field
                    : config.enterpriseFields) {
                String varName = field.varName();
                String value = field.value();
                if (value != null) {
                    if (field != config.eap) {
                        value = (value.length() == 0) ? "NULL" : convertToQuotedString(value);
                    }
                    if (!WifiNative.setNetworkVariableCommand(
                                netId,
                                varName,
                                value)) {
                        Log.d(TAG, config.SSID + ": failed to set " + varName +
                                ": " + value);
                        break setVariables;
                    }
                }
            }
            return netId;
        }

        if (newNetwork) {
            WifiNative.removeNetworkCommand(netId);
            Log.d(TAG,
                    "Failed to set a network variable, removed network: "
                    + netId);
        }

        return -1;
    }

    /**
     * Read the variables from the supplicant daemon that are needed to
     * fill in the WifiConfiguration object.
     *
     * @param config the {@link WifiConfiguration} object to be filled in.
     */
    private static void readNetworkVariables(WifiConfiguration config) {

        int netId = config.networkId;
        if (netId < 0)
            return;

        /*
         * TODO: maybe should have a native method that takes an array of
         * variable names and returns an array of values. But we'd still
         * be doing a round trip to the supplicant daemon for each variable.
         */
        String value;

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.ssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.SSID = removeDoubleQuotes(value);
        } else {
            config.SSID = null;
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.bssidVarName);
        if (!TextUtils.isEmpty(value)) {
            config.BSSID = value;
        } else {
            config.BSSID = null;
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.priorityVarName);
        config.priority = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.priority = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.hiddenSSIDVarName);
        config.hiddenSSID = false;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.hiddenSSID = Integer.parseInt(value) != 0;
            } catch (NumberFormatException ignore) {
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.wepTxKeyIdxVarName);
        config.wepTxKeyIndex = -1;
        if (!TextUtils.isEmpty(value)) {
            try {
                config.wepTxKeyIndex = Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }

        for (int i = 0; i < 4; i++) {
            value = WifiNative.getNetworkVariableCommand(netId,
                    WifiConfiguration.wepKeyVarNames[i]);
            if (!TextUtils.isEmpty(value)) {
                config.wepKeys[i] = value;
            } else {
                config.wepKeys[i] = null;
            }
        }

        value = WifiNative.getNetworkVariableCommand(netId, WifiConfiguration.pskVarName);
        if (!TextUtils.isEmpty(value)) {
            config.preSharedKey = value;
        } else {
            config.preSharedKey = null;
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.Protocol.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.Protocol.strings);
                if (0 <= index) {
                    config.allowedProtocols.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.KeyMgmt.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.KeyMgmt.strings);
                if (0 <= index) {
                    config.allowedKeyManagement.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.AuthAlgorithm.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.AuthAlgorithm.strings);
                if (0 <= index) {
                    config.allowedAuthAlgorithms.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.PairwiseCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.PairwiseCipher.strings);
                if (0 <= index) {
                    config.allowedPairwiseCiphers.set(index);
                }
            }
        }

        value = WifiNative.getNetworkVariableCommand(config.networkId,
                WifiConfiguration.GroupCipher.varName);
        if (!TextUtils.isEmpty(value)) {
            String vals[] = value.split(" ");
            for (String val : vals) {
                int index =
                    lookupString(val, WifiConfiguration.GroupCipher.strings);
                if (0 <= index) {
                    config.allowedGroupCiphers.set(index);
                }
            }
        }

        for (WifiConfiguration.EnterpriseField field :
                config.enterpriseFields) {
            value = WifiNative.getNetworkVariableCommand(netId,
                    field.varName());
            if (!TextUtils.isEmpty(value)) {
                if (field != config.eap) value = removeDoubleQuotes(value);
                field.setValue(value);
            }
        }
    }

    private static String removeDoubleQuotes(String string) {
        if (string.length() <= 2) return "";
        return string.substring(1, string.length() - 1);
    }

    private static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static String makeString(BitSet set, String[] strings) {
        StringBuffer buf = new StringBuffer();
        int nextSetBit = -1;

        /* Make sure all set bits are in [0, strings.length) to avoid
         * going out of bounds on strings.  (Shouldn't happen, but...) */
        set = set.get(0, strings.length);

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            buf.append(strings[nextSetBit].replace('_', '-')).append(' ');
        }

        // remove trailing space
        if (set.cardinality() > 0) {
            buf.setLength(buf.length() - 1);
        }

        return buf.toString();
    }

    private static int lookupString(String string, String[] strings) {
        int size = strings.length;

        string = string.replace('-', '_');

        for (int i = 0; i < size; i++)
            if (string.equals(strings[i]))
                return i;

        // if we ever get here, we should probably add the
        // value to WifiConfiguration to reflect that it's
        // supported by the WPA supplicant
        Log.w(TAG, "Failed to look-up a string: " + string);

        return -1;
    }

    static String dump() {
        StringBuffer sb = new StringBuffer();
        String LS = System.getProperty("line.separator");
        sb.append("sLastPriority ").append(sLastPriority).append(LS);
        sb.append("Configured networks ").append(LS);
        for (WifiConfiguration conf : getConfiguredNetworks()) {
            sb.append(conf).append(LS);
        }
        return sb.toString();
    }
}
