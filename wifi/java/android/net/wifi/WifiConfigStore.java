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
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.ProxyProperties;
import android.net.wifi.WifiConfiguration.IpAssignment;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.ProxySettings;
import android.net.wifi.WifiConfiguration.Status;
import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * This class provides the API to manage configured
 * wifi networks. The API is not thread safe is being
 * used only from WifiStateMachine.
 *
 * It deals with the following
 * - Add/update/remove a WifiConfiguration
 *   The configuration contains two types of information.
 *     = IP and proxy configuration that is handled by WifiConfigStore and
 *       is saved to disk on any change.
 *
 *       The format of configuration file is as follows:
 *       <version>
 *       <netA_key1><netA_value1><netA_key2><netA_value2>...<EOS>
 *       <netB_key1><netB_value1><netB_key2><netB_value2>...<EOS>
 *       ..
 *
 *       (key, value) pairs for a given network are grouped together and can
 *       be in any order. A EOS at the end of a set of (key, value) pairs
 *       indicates that the next set of (key, value) pairs are for a new
 *       network. A network is identified by a unique ID_KEY. If there is no
 *       ID_KEY in the (key, value) pairs, the data is discarded.
 *
 *       An invalid version on read would result in discarding the contents of
 *       the file. On the next write, the latest version is written to file.
 *
 *       Any failures during read or write to the configuration file are ignored
 *       without reporting to the user since the likelihood of these errors are
 *       low and the impact on connectivity is low.
 *
 *     = SSID & security details that is pushed to the supplicant.
 *       supplicant saves these details to the disk on calling
 *       saveConfigCommand().
 *
 *       We have two kinds of APIs exposed:
 *        > public API calls that provide fine grained control
 *          - enableNetwork, disableNetwork, addOrUpdateNetwork(),
 *          removeNetwork(). For these calls, the config is not persisted
 *          to the disk. (TODO: deprecate these calls in WifiManager)
 *        > The new API calls - selectNetwork(), saveNetwork() & forgetNetwork().
 *          These calls persist the supplicant config to disk.
 *
 * - Maintain a list of configured networks for quick access
 *
 */
class WifiConfigStore {

    private static Context sContext;
    private static final String TAG = "WifiConfigStore";

    /* configured networks with network id as the key */
    private static HashMap<Integer, WifiConfiguration> sConfiguredNetworks =
            new HashMap<Integer, WifiConfiguration>();

    /* A network id is a unique identifier for a network configured in the
     * supplicant. Network ids are generated when the supplicant reads
     * the configuration file at start and can thus change for networks.
     * We store the IP configuration for networks along with a unique id
     * that is generated from SSID and security type of the network. A mapping
     * from the generated unique id to network id of the network is needed to
     * map supplicant config to IP configuration. */
    private static HashMap<Integer, Integer> sNetworkIds =
            new HashMap<Integer, Integer>();

    /* Tracks the highest priority of configured networks */
    private static int sLastPriority = -1;

    private static final String ipConfigFile = Environment.getDataDirectory() +
            "/misc/wifi/ipconfig.txt";

    private static final int IPCONFIG_FILE_VERSION = 1;

    /* IP and proxy configuration keys */
    private static final String ID_KEY = "id";
    private static final String IP_ASSIGNMENT_KEY = "ipAssignment";
    private static final String LINK_ADDRESS_KEY = "linkAddress";
    private static final String GATEWAY_KEY = "gateway";
    private static final String DNS_KEY = "dns";
    private static final String PROXY_SETTINGS_KEY = "proxySettings";
    private static final String PROXY_HOST_KEY = "proxyHost";
    private static final String PROXY_PORT_KEY = "proxyPort";
    private static final String EXCLUSION_LIST_KEY = "exclusionList";
    private static final String EOS = "eos";

    /**
     * Initialize context, fetch the list of configured networks
     * and enable all stored networks in supplicant.
     */
    static void initialize(Context context) {
        Log.d(TAG, "Loading config and enabling all networks");
        sContext = context;
        loadConfiguredNetworks();
        enableAllNetworks();
    }

    /**
     * Fetch the list of currently configured networks
     * @return List of networks
     */
    static List<WifiConfiguration> getConfiguredNetworks() {
        List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();
        synchronized (sConfiguredNetworks) {
            for(WifiConfiguration config : sConfiguredNetworks.values()) {
                networks.add(new WifiConfiguration(config));
            }
        }
        return networks;
    }

    /**
     * enable all networks and save config. This will be a no-op if the list
     * of configured networks indicates all networks as being enabled
     */
    static void enableAllNetworks() {
        synchronized (sConfiguredNetworks) {
            for(WifiConfiguration config : sConfiguredNetworks.values()) {
                if(config != null && config.status == Status.DISABLED) {
                    if(WifiNative.enableNetworkCommand(config.networkId, false)) {
                        config.status = Status.ENABLED;
                    } else {
                        Log.e(TAG, "Enable network failed on " + config.networkId);
                    }
                }
            }
        }

        WifiNative.saveConfigCommand();
        sendConfigChangeBroadcast();
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
            if (netId != INVALID_NETWORK_ID) {
                selectNetwork(netId);
            } else {
                Log.e(TAG, "Failed to update network " + config);
            }
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
            synchronized (sConfiguredNetworks) {
                for(WifiConfiguration config : sConfiguredNetworks.values()) {
                    if (config.networkId != INVALID_NETWORK_ID) {
                        config.priority = 0;
                        addOrUpdateNetworkNative(config);
                    }
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
        enableNetworkWithoutBroadcast(netId, true);

       /* Avoid saving the config & sending a broadcast to prevent settings
        * from displaying a disabled list of networks */
    }

    /**
     * Add/update the specified configuration and save config
     *
     * @param config WifiConfiguration to be saved
     */
    static void saveNetwork(WifiConfiguration config) {
        boolean newNetwork = (config.networkId == INVALID_NETWORK_ID);
        int netId = addOrUpdateNetworkNative(config);
        /* enable a new network */
        if (newNetwork && netId != INVALID_NETWORK_ID) {
            WifiNative.enableNetworkCommand(netId, false);
            synchronized (sConfiguredNetworks) {
                sConfiguredNetworks.get(netId).status = Status.ENABLED;
            }
        }
        WifiNative.saveConfigCommand();
        sendConfigChangeBroadcast();
    }

    /**
     * Forget the specified network and save config
     *
     * @param netId network to forget
     */
    static void forgetNetwork(int netId) {
        if (WifiNative.removeNetworkCommand(netId)) {
            WifiNative.saveConfigCommand();
            synchronized (sConfiguredNetworks) {
                sConfiguredNetworks.remove(netId);
            }
            writeIpAndProxyConfigurations();
            sendConfigChangeBroadcast();
        } else {
            Log.e(TAG, "Failed to remove network " + netId);
        }
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
        sendConfigChangeBroadcast();
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
        synchronized (sConfiguredNetworks) {
            if (ret) sConfiguredNetworks.remove(netId);
        }
        sendConfigChangeBroadcast();
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
        boolean ret = enableNetworkWithoutBroadcast(netId, disableOthers);
        sendConfigChangeBroadcast();
        return ret;
    }

    static boolean enableNetworkWithoutBroadcast(int netId, boolean disableOthers) {
        boolean ret = WifiNative.enableNetworkCommand(netId, disableOthers);

        synchronized (sConfiguredNetworks) {
            WifiConfiguration config = sConfiguredNetworks.get(netId);
            if (config != null) config.status = Status.ENABLED;
        }

        if (disableOthers) {
            markAllNetworksDisabledExcept(netId);
        }
        return ret;
    }

    /**
     * Disable a network. Note that there is no saveConfig operation.
     * @param netId network to be disabled
     */
    static boolean disableNetwork(int netId) {
        boolean ret = WifiNative.disableNetworkCommand(netId);
        synchronized (sConfiguredNetworks) {
            WifiConfiguration config = sConfiguredNetworks.get(netId);
            if (config != null) config.status = Status.DISABLED;
        }
        sendConfigChangeBroadcast();
        return ret;
    }

    /**
     * Save the configured networks in supplicant to disk
     */
    static boolean saveConfig() {
        return WifiNative.saveConfigCommand();
    }

    /**
     * Start WPS pin method configuration
     */
    static boolean startWpsPin(String bssid, int apPin) {
        if (WifiNative.startWpsPinCommand(bssid, apPin)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            return true;
        }
        Log.e(TAG, "Failed to start WPS pin method configuration");
        return false;
    }

    /**
     * Start WPS push button configuration
     */
    static boolean startWpsPbc(String bssid) {
        if (WifiNative.startWpsPbcCommand(bssid)) {
            /* WPS leaves all networks disabled */
            markAllNetworksDisabled();
            return true;
        }
        Log.e(TAG, "Failed to start WPS push button configuration");
        return false;
    }

    /**
     * Fetch the link properties for a given network id
     */
    static LinkProperties getLinkProperties(int netId) {
        synchronized (sConfiguredNetworks) {
            WifiConfiguration config = sConfiguredNetworks.get(netId);
            if (config != null) return new LinkProperties(config.linkProperties);
        }
        return null;
    }

    /**
     * get IP configuration for a given network id
     * TODO: We cannot handle IPv6 addresses for configuration
     *       right now until NetworkUtils is fixed. When we do
     *       that, we should remove handling DhcpInfo and move
     *       to using LinkProperties
     */
    static DhcpInfo getIpConfiguration(int netId) {
        DhcpInfo dhcpInfo = new DhcpInfo();
        LinkProperties linkProperties = getLinkProperties(netId);

        if (linkProperties != null) {
            Iterator<LinkAddress> iter = linkProperties.getLinkAddresses().iterator();
            if (iter.hasNext()) {
                try {
                    LinkAddress linkAddress = iter.next();
                    dhcpInfo.ipAddress = NetworkUtils.inetAddressToInt(
                            linkAddress.getAddress());
                    dhcpInfo.gateway = NetworkUtils.inetAddressToInt(
                            linkProperties.getGateway());
                    dhcpInfo.netmask = NetworkUtils.prefixLengthToNetmaskInt(
                            linkAddress.getNetworkPrefixLength());
                    Iterator<InetAddress> dnsIterator = linkProperties.getDnses().iterator();
                    dhcpInfo.dns1 = NetworkUtils.inetAddressToInt(dnsIterator.next());
                    if (dnsIterator.hasNext()) {
                        dhcpInfo.dns2 = NetworkUtils.inetAddressToInt(dnsIterator.next());
                    }
                } catch (IllegalArgumentException e1) {
                    Log.e(TAG, "IPv6 address cannot be handled " + e1);
                } catch (NullPointerException e2) {
                    /* Should not happen since a stored static config should be valid */
                    Log.e(TAG, "Invalid partial IP configuration " + e2);
                }
            }
        }
        return dhcpInfo;
    }

    /**
     * Fetch the proxy properties for a given network id
     */
    static ProxyProperties getProxyProperties(int netId) {
        LinkProperties linkProperties = getLinkProperties(netId);
        if (linkProperties != null) {
            return new ProxyProperties(linkProperties.getHttpProxy());
        }
        return null;
    }

    /**
     * Return if the specified network is using static IP
     */
    static boolean isUsingStaticIp(int netId) {
        synchronized (sConfiguredNetworks) {
            WifiConfiguration config = sConfiguredNetworks.get(netId);
            if (config != null && config.ipAssignment == IpAssignment.STATIC) {
                return true;
            }
        }
        return false;
    }

    private static void sendConfigChangeBroadcast() {
        if (!ActivityManagerNative.isSystemReady()) return;
        Intent intent = new Intent(WifiManager.SUPPLICANT_CONFIG_CHANGED_ACTION);
        sContext.sendBroadcast(intent);
    }

    static void loadConfiguredNetworks() {
        String listStr = WifiNative.listNetworksCommand();
        sLastPriority = 0;

        synchronized (sConfiguredNetworks) {
            sConfiguredNetworks.clear();
            sNetworkIds.clear();

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
                sConfiguredNetworks.put(config.networkId, config);
                sNetworkIds.put(configKey(config), config.networkId);
            }
        }
        readIpAndProxyConfigurations();
        sendConfigChangeBroadcast();
    }

    /* Mark all networks except specified netId as disabled */
    private static void markAllNetworksDisabledExcept(int netId) {
        synchronized (sConfiguredNetworks) {
            for(WifiConfiguration config : sConfiguredNetworks.values()) {
                if(config != null && config.networkId != netId) {
                    config.status = Status.DISABLED;
                }
            }
        }
    }

    private static void markAllNetworksDisabled() {
        markAllNetworksDisabledExcept(INVALID_NETWORK_ID);
    }

    private static void writeIpAndProxyConfigurations() {

        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(ipConfigFile)));

            out.writeInt(IPCONFIG_FILE_VERSION);

            synchronized (sConfiguredNetworks) {
                for(WifiConfiguration config : sConfiguredNetworks.values()) {
                    boolean writeToFile = false;

                    try {
                        LinkProperties linkProperties = config.linkProperties;
                        switch (config.ipAssignment) {
                            case STATIC:
                                out.writeUTF(IP_ASSIGNMENT_KEY);
                                out.writeUTF(config.ipAssignment.toString());
                                for (LinkAddress linkAddr : linkProperties.getLinkAddresses()) {
                                    out.writeUTF(LINK_ADDRESS_KEY);
                                    out.writeUTF(linkAddr.getAddress().getHostAddress());
                                    out.writeInt(linkAddr.getNetworkPrefixLength());
                                }
                                InetAddress gateway = linkProperties.getGateway();
                                if (gateway != null) {
                                    out.writeUTF(GATEWAY_KEY);
                                    out.writeUTF(gateway.getHostAddress());
                                }
                                for (InetAddress inetAddr : linkProperties.getDnses()) {
                                    out.writeUTF(DNS_KEY);
                                    out.writeUTF(inetAddr.getHostAddress());
                                }
                                writeToFile = true;
                                break;
                            case DHCP:
                                out.writeUTF(IP_ASSIGNMENT_KEY);
                                out.writeUTF(config.ipAssignment.toString());
                                writeToFile = true;
                                break;
                            case UNASSIGNED:
                                /* Ignore */
                                break;
                            default:
                                Log.e(TAG, "Ignore invalid ip assignment while writing");
                                break;
                        }

                        switch (config.proxySettings) {
                            case STATIC:
                                ProxyProperties proxyProperties = linkProperties.getHttpProxy();
                                String exclusionList = proxyProperties.getExclusionList();
                                out.writeUTF(PROXY_SETTINGS_KEY);
                                out.writeUTF(config.proxySettings.toString());
                                out.writeUTF(PROXY_HOST_KEY);
                                out.writeUTF(proxyProperties.getSocketAddress().getHostName());
                                out.writeUTF(PROXY_PORT_KEY);
                                out.writeInt(proxyProperties.getSocketAddress().getPort());
                                out.writeUTF(EXCLUSION_LIST_KEY);
                                out.writeUTF(exclusionList);
                                writeToFile = true;
                                break;
                            case NONE:
                                out.writeUTF(PROXY_SETTINGS_KEY);
                                out.writeUTF(config.proxySettings.toString());
                                writeToFile = true;
                                break;
                            case UNASSIGNED:
                                /* Ignore */
                                break;
                            default:
                                Log.e(TAG, "Ignore invalid proxy settings while writing");
                                break;
                        }
                        if (writeToFile) {
                            out.writeUTF(ID_KEY);
                            out.writeInt(configKey(config));
                        }
                    } catch (NullPointerException e) {
                        Log.e(TAG, "Failure in writing " + config.linkProperties + e);
                    }
                    out.writeUTF(EOS);
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing data file");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {}
            }
        }
    }

    private static void readIpAndProxyConfigurations() {

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                    ipConfigFile)));

            if (in.readInt() != IPCONFIG_FILE_VERSION) {
                Log.e(TAG, "Bad version on IP configuration file, ignore read");
                return;
            }

            while (true) {
                int id = -1;
                IpAssignment ipAssignment = IpAssignment.UNASSIGNED;
                ProxySettings proxySettings = ProxySettings.UNASSIGNED;
                LinkProperties linkProperties = new LinkProperties();
                String proxyHost = null;
                int proxyPort = -1;
                String exclusionList = null;
                String key;

                do {
                    key = in.readUTF();
                    try {
                        if (key.equals(ID_KEY)) {
                            id = in.readInt();
                        } else if (key.equals(IP_ASSIGNMENT_KEY)) {
                            ipAssignment = IpAssignment.valueOf(in.readUTF());
                        } else if (key.equals(LINK_ADDRESS_KEY)) {
                            LinkAddress linkAddr = new LinkAddress(InetAddress.getByName(
                                    in.readUTF()), in.readInt());
                            linkProperties.addLinkAddress(linkAddr);
                        } else if (key.equals(GATEWAY_KEY)) {
                            linkProperties.setGateway(InetAddress.getByName(in.readUTF()));
                        } else if (key.equals(DNS_KEY)) {
                            linkProperties.addDns(InetAddress.getByName(in.readUTF()));
                        } else if (key.equals(PROXY_SETTINGS_KEY)) {
                            proxySettings = ProxySettings.valueOf(in.readUTF());
                        } else if (key.equals(PROXY_HOST_KEY)) {
                            proxyHost = in.readUTF();
                        } else if (key.equals(PROXY_PORT_KEY)) {
                            proxyPort = in.readInt();
                        } else if (key.equals(EXCLUSION_LIST_KEY)) {
                            exclusionList = in.readUTF();
                        } else if (key.equals(EOS)) {
                            break;
                        } else {
                            Log.e(TAG, "Ignore unknown key " + key + "while reading");
                        }
                    } catch (UnknownHostException e) {
                        Log.e(TAG, "Ignore invalid address while reading" + e);
                    }
                } while (true);

                if (id != -1) {
                    synchronized (sConfiguredNetworks) {
                        WifiConfiguration config = sConfiguredNetworks.get(
                                sNetworkIds.get(id));

                        if (config == null) {
                            Log.e(TAG, "configuration found for missing network, ignored");
                        } else {
                            config.linkProperties = linkProperties;
                            switch (ipAssignment) {
                                case STATIC:
                                case DHCP:
                                    config.ipAssignment = ipAssignment;
                                    break;
                                case UNASSIGNED:
                                    //Ignore
                                    break;
                                default:
                                    Log.e(TAG, "Ignore invalid ip assignment while reading");
                                    break;
                            }

                            switch (proxySettings) {
                                case STATIC:
                                    config.proxySettings = proxySettings;
                                    ProxyProperties proxyProperties = new ProxyProperties();
                                    proxyProperties.setSocketAddress(
                                            new InetSocketAddress(proxyHost, proxyPort));
                                    proxyProperties.setExclusionList(exclusionList);
                                    linkProperties.setHttpProxy(proxyProperties);
                                    break;
                                case NONE:
                                    config.proxySettings = proxySettings;
                                    break;
                                case UNASSIGNED:
                                    //Ignore
                                    break;
                                default:
                                    Log.e(TAG, "Ignore invalid proxy settings while reading");
                                    break;
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Missing id while parsing configuration");
                }
            }
        } catch (EOFException ignore) {
        } catch (IOException e) {
            Log.e(TAG, "Error parsing configuration" + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {}
            }
        }
    }

    private static int addOrUpdateNetworkNative(WifiConfiguration config) {
        /*
         * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
         * network configuration. Otherwise, the networkId should
         * refer to an existing configuration.
         */
        int netId = config.networkId;
        boolean updateFailed = true;
        boolean newNetwork = (netId == INVALID_NETWORK_ID);
        // networkId of INVALID_NETWORK_ID means we want to create a new network

        if (newNetwork) {
            netId = WifiNative.addNetworkCommand();
            if (netId < 0) {
                Log.e(TAG, "Failed to add a network!");
                return INVALID_NETWORK_ID;
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
            updateFailed = false;
        }

        if (updateFailed) {
            if (newNetwork) {
                WifiNative.removeNetworkCommand(netId);
                Log.d(TAG,
                        "Failed to set a network variable, removed network: "
                        + netId);
            }
            return INVALID_NETWORK_ID;
        }

        /* An update of the network variables requires reading them
         * back from the supplicant to update sConfiguredNetworks.
         * This is because some of the variables (SSID, wep keys &
         * passphrases) reflect different values when read back than
         * when written. For example, wep key is stored as * irrespective
         * of the value sent to the supplicant
         */
        WifiConfiguration sConfig;
        synchronized (sConfiguredNetworks) {
            sConfig = sConfiguredNetworks.get(netId);
        }
        if (sConfig == null) {
            sConfig = new WifiConfiguration();
            sConfig.networkId = netId;
            synchronized (sConfiguredNetworks) {
                sConfiguredNetworks.put(netId, sConfig);
            }
        }
        readNetworkVariables(sConfig);
        writeIpAndProxyConfigurationsOnChange(sConfig, config);
        return netId;
    }

    /* Compare current and new configuration and write to file on change */
    private static void writeIpAndProxyConfigurationsOnChange(WifiConfiguration currentConfig,
            WifiConfiguration newConfig) {
        boolean ipChanged = false;
        boolean proxyChanged = false;
        LinkProperties linkProperties = new LinkProperties();

        switch (newConfig.ipAssignment) {
            case STATIC:
                Collection<LinkAddress> currentLinkAddresses = currentConfig.linkProperties
                        .getLinkAddresses();
                Collection<LinkAddress> newLinkAddresses = newConfig.linkProperties
                        .getLinkAddresses();
                Collection<InetAddress> currentDnses = currentConfig.linkProperties.getDnses();
                Collection<InetAddress> newDnses = newConfig.linkProperties.getDnses();
                InetAddress currentGateway = currentConfig.linkProperties.getGateway();
                InetAddress newGateway = newConfig.linkProperties.getGateway();

                boolean linkAddressesDiffer = !currentLinkAddresses.containsAll(newLinkAddresses) ||
                        (currentLinkAddresses.size() != newLinkAddresses.size());
                boolean dnsesDiffer = !currentDnses.containsAll(newDnses) ||
                        (currentDnses.size() != newDnses.size());
                boolean gatewaysDiffer = (currentGateway == null) ||
                        !currentGateway.equals(newGateway);

                if ((currentConfig.ipAssignment != newConfig.ipAssignment) ||
                        linkAddressesDiffer ||
                        dnsesDiffer ||
                        gatewaysDiffer) {
                    ipChanged = true;
                }
                break;
            case DHCP:
                if (currentConfig.ipAssignment != newConfig.ipAssignment) {
                    ipChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                Log.e(TAG, "Ignore invalid ip assignment during write");
                break;
        }

        switch (newConfig.proxySettings) {
            case STATIC:
                InetSocketAddress newSockAddr = null;
                String newExclusionList = null;
                InetSocketAddress currentSockAddr = null;
                String currentExclusionList = null;

                ProxyProperties newHttpProxy = newConfig.linkProperties.getHttpProxy();
                if (newHttpProxy != null) {
                    newSockAddr = newHttpProxy.getSocketAddress();
                    newExclusionList = newHttpProxy.getExclusionList();
                }

                ProxyProperties currentHttpProxy = currentConfig.linkProperties.getHttpProxy();
                if (currentHttpProxy != null) {
                    currentSockAddr =  currentHttpProxy.getSocketAddress();
                    currentExclusionList = currentHttpProxy.getExclusionList();
                }

                boolean socketAddressDiffers = false;
                boolean exclusionListDiffers = false;

                if (newSockAddr != null && currentSockAddr != null ) {
                    socketAddressDiffers = !currentSockAddr.equals(newSockAddr);
                } else if (newSockAddr != null || currentSockAddr != null) {
                    socketAddressDiffers = true;
                }

                if (newExclusionList != null && currentExclusionList != null) {
                    exclusionListDiffers = !currentExclusionList.equals(newExclusionList);
                } else if (newExclusionList != null || currentExclusionList != null) {
                    exclusionListDiffers = true;
                }

                if ((currentConfig.proxySettings != newConfig.proxySettings) ||
                        socketAddressDiffers ||
                        exclusionListDiffers) {
                    proxyChanged = true;
                }
                break;
            case NONE:
                if (currentConfig.proxySettings != newConfig.proxySettings) {
                    proxyChanged = true;
                }
                break;
            case UNASSIGNED:
                /* Ignore */
                break;
            default:
                Log.e(TAG, "Ignore invalid proxy configuration during write");
                break;
        }

        if (!ipChanged) {
            addIpSettingsFromConfig(linkProperties, currentConfig);
        } else {
            currentConfig.ipAssignment = newConfig.ipAssignment;
            addIpSettingsFromConfig(linkProperties, newConfig);
            Log.d(TAG, "IP config changed SSID = " + currentConfig.SSID + " linkProperties: " +
                    linkProperties.toString());
        }


        if (!proxyChanged) {
            linkProperties.setHttpProxy(currentConfig.linkProperties.getHttpProxy());
        } else {
            currentConfig.proxySettings = newConfig.proxySettings;
            linkProperties.setHttpProxy(newConfig.linkProperties.getHttpProxy());
            Log.d(TAG, "proxy changed SSID = " + currentConfig.SSID);
            if (linkProperties.getHttpProxy() != null) {
                Log.d(TAG, " proxyProperties: " + linkProperties.getHttpProxy().toString());
            }
        }

        if (ipChanged || proxyChanged) {
            currentConfig.linkProperties = linkProperties;
            writeIpAndProxyConfigurations();
            sendConfigChangeBroadcast();
        }
    }

    private static void addIpSettingsFromConfig(LinkProperties linkProperties,
            WifiConfiguration config) {
        for (LinkAddress linkAddr : config.linkProperties.getLinkAddresses()) {
            linkProperties.addLinkAddress(linkAddr);
        }
        linkProperties.setGateway(config.linkProperties.getGateway());
        for (InetAddress dns : config.linkProperties.getDnses()) {
            linkProperties.addDns(dns);
        }
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

    /* Returns a unique for a given configuration */
    private static int configKey(WifiConfiguration config) {
        String key;

        if (config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            key = config.SSID + KeyMgmt.strings[KeyMgmt.WPA_PSK];
        } else if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                config.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            key = config.SSID + KeyMgmt.strings[KeyMgmt.WPA_EAP];
        } else if (config.wepKeys[0] != null) {
            key = config.SSID + "WEP";
        } else {
            key = config.SSID + KeyMgmt.strings[KeyMgmt.NONE];
        }

        return key.hashCode();
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
