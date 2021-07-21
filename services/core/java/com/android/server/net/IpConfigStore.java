/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.net;

import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.ProxyUtils;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class IpConfigStore {
    private static final String TAG = "IpConfigStore";
    private static final boolean DBG = false;

    protected final DelayedDiskWrite mWriter;

    /* IP and proxy configuration keys */
    protected static final String ID_KEY = "id";
    protected static final String IP_ASSIGNMENT_KEY = "ipAssignment";
    protected static final String LINK_ADDRESS_KEY = "linkAddress";
    protected static final String GATEWAY_KEY = "gateway";
    protected static final String DNS_KEY = "dns";
    protected static final String PROXY_SETTINGS_KEY = "proxySettings";
    protected static final String PROXY_HOST_KEY = "proxyHost";
    protected static final String PROXY_PORT_KEY = "proxyPort";
    protected static final String PROXY_PAC_FILE = "proxyPac";
    protected static final String EXCLUSION_LIST_KEY = "exclusionList";
    protected static final String EOS = "eos";

    protected static final int IPCONFIG_FILE_VERSION = 3;

    public IpConfigStore(DelayedDiskWrite writer) {
        mWriter = writer;
    }

    public IpConfigStore() {
        this(new DelayedDiskWrite());
    }

    private static boolean writeConfig(DataOutputStream out, String configKey,
            IpConfiguration config) throws IOException {
        return writeConfig(out, configKey, config, IPCONFIG_FILE_VERSION);
    }

    @VisibleForTesting
    public static boolean writeConfig(DataOutputStream out, String configKey,
                                IpConfiguration config, int version) throws IOException {
        boolean written = false;

        try {
            switch (config.getIpAssignment()) {
                case STATIC:
                    out.writeUTF(IP_ASSIGNMENT_KEY);
                    out.writeUTF(config.getIpAssignment().toString());
                    StaticIpConfiguration staticIpConfiguration = config.getStaticIpConfiguration();
                    if (staticIpConfiguration != null) {
                        if (staticIpConfiguration.getIpAddress() != null) {
                            LinkAddress ipAddress = staticIpConfiguration.getIpAddress();
                            out.writeUTF(LINK_ADDRESS_KEY);
                            out.writeUTF(ipAddress.getAddress().getHostAddress());
                            out.writeInt(ipAddress.getPrefixLength());
                        }
                        if (staticIpConfiguration.getGateway() != null) {
                            out.writeUTF(GATEWAY_KEY);
                            out.writeInt(0);  // Default route.
                            out.writeInt(1);  // Have a gateway.
                            out.writeUTF(staticIpConfiguration.getGateway().getHostAddress());
                        }
                        for (InetAddress inetAddr : staticIpConfiguration.getDnsServers()) {
                            out.writeUTF(DNS_KEY);
                            out.writeUTF(inetAddr.getHostAddress());
                        }
                    }
                    written = true;
                    break;
                case DHCP:
                    out.writeUTF(IP_ASSIGNMENT_KEY);
                    out.writeUTF(config.getIpAssignment().toString());
                    written = true;
                    break;
                case UNASSIGNED:
                /* Ignore */
                    break;
                default:
                    loge("Ignore invalid ip assignment while writing");
                    break;
            }

            switch (config.getProxySettings()) {
                case STATIC:
                    ProxyInfo proxyProperties = config.getHttpProxy();
                    String exclusionList = ProxyUtils.exclusionListAsString(
                            proxyProperties.getExclusionList());
                    out.writeUTF(PROXY_SETTINGS_KEY);
                    out.writeUTF(config.getProxySettings().toString());
                    out.writeUTF(PROXY_HOST_KEY);
                    out.writeUTF(proxyProperties.getHost());
                    out.writeUTF(PROXY_PORT_KEY);
                    out.writeInt(proxyProperties.getPort());
                    if (exclusionList != null) {
                        out.writeUTF(EXCLUSION_LIST_KEY);
                        out.writeUTF(exclusionList);
                    }
                    written = true;
                    break;
                case PAC:
                    ProxyInfo proxyPacProperties = config.getHttpProxy();
                    out.writeUTF(PROXY_SETTINGS_KEY);
                    out.writeUTF(config.getProxySettings().toString());
                    out.writeUTF(PROXY_PAC_FILE);
                    out.writeUTF(proxyPacProperties.getPacFileUrl().toString());
                    written = true;
                    break;
                case NONE:
                    out.writeUTF(PROXY_SETTINGS_KEY);
                    out.writeUTF(config.getProxySettings().toString());
                    written = true;
                    break;
                case UNASSIGNED:
                    /* Ignore */
                        break;
                    default:
                        loge("Ignore invalid proxy settings while writing");
                        break;
            }

            if (written) {
                out.writeUTF(ID_KEY);
                if (version < 3) {
                    out.writeInt(Integer.valueOf(configKey));
                } else {
                    out.writeUTF(configKey);
                }
            }
        } catch (NullPointerException e) {
            loge("Failure in writing " + config + e);
        }
        out.writeUTF(EOS);

        return written;
    }

    /**
     * @Deprecated use {@link #writeIpConfigurations(String, ArrayMap)} instead.
     * New method uses string as network identifier which could be interface name or MAC address or
     * other token.
     */
    @Deprecated
    public void writeIpAndProxyConfigurationsToFile(String filePath,
                                              final SparseArray<IpConfiguration> networks) {
        mWriter.write(filePath, out -> {
            out.writeInt(IPCONFIG_FILE_VERSION);
            for(int i = 0; i < networks.size(); i++) {
                writeConfig(out, String.valueOf(networks.keyAt(i)), networks.valueAt(i));
            }
        });
    }

    public void writeIpConfigurations(String filePath,
                                      ArrayMap<String, IpConfiguration> networks) {
        mWriter.write(filePath, out -> {
            out.writeInt(IPCONFIG_FILE_VERSION);
            for(int i = 0; i < networks.size(); i++) {
                writeConfig(out, networks.keyAt(i), networks.valueAt(i));
            }
        });
    }

    public static ArrayMap<String, IpConfiguration> readIpConfigurations(String filePath) {
        BufferedInputStream bufferedInputStream;
        try {
            bufferedInputStream = new BufferedInputStream(new FileInputStream(filePath));
        } catch (FileNotFoundException e) {
            // Return an empty array here because callers expect an empty array when the file is
            // not present.
            loge("Error opening configuration file: " + e);
            return new ArrayMap<>(0);
        }
        return readIpConfigurations(bufferedInputStream);
    }

    /** @Deprecated use {@link #readIpConfigurations(String)} */
    @Deprecated
    public static SparseArray<IpConfiguration> readIpAndProxyConfigurations(String filePath) {
        BufferedInputStream bufferedInputStream;
        try {
            bufferedInputStream = new BufferedInputStream(new FileInputStream(filePath));
        } catch (FileNotFoundException e) {
            // Return an empty array here because callers expect an empty array when the file is
            // not present.
            loge("Error opening configuration file: " + e);
            return new SparseArray<>();
        }
        return readIpAndProxyConfigurations(bufferedInputStream);
    }

    /** @Deprecated use {@link #readIpConfigurations(InputStream)} */
    @Deprecated
    public static SparseArray<IpConfiguration> readIpAndProxyConfigurations(
            InputStream inputStream) {
        ArrayMap<String, IpConfiguration> networks = readIpConfigurations(inputStream);
        if (networks == null) {
            return null;
        }

        SparseArray<IpConfiguration> networksById = new SparseArray<>();
        for (int i = 0; i < networks.size(); i++) {
            int id = Integer.valueOf(networks.keyAt(i));
            networksById.put(id, networks.valueAt(i));
        }

        return networksById;
    }

    /** Returns a map of network identity token and {@link IpConfiguration}. */
    public static ArrayMap<String, IpConfiguration> readIpConfigurations(
            InputStream inputStream) {
        ArrayMap<String, IpConfiguration> networks = new ArrayMap<>();
        DataInputStream in = null;
        try {
            in = new DataInputStream(inputStream);

            int version = in.readInt();
            if (version != 3 && version != 2 && version != 1) {
                loge("Bad version on IP configuration file, ignore read");
                return null;
            }

            while (true) {
                String uniqueToken = null;
                // Default is DHCP with no proxy
                IpAssignment ipAssignment = IpAssignment.DHCP;
                ProxySettings proxySettings = ProxySettings.NONE;
                StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
                LinkAddress linkAddress = null;
                InetAddress gatewayAddress = null;
                String proxyHost = null;
                String pacFileUrl = null;
                int proxyPort = -1;
                String exclusionList = null;
                String key;
                final List<InetAddress> dnsServers = new ArrayList<>();

                do {
                    key = in.readUTF();
                    try {
                        if (key.equals(ID_KEY)) {
                            if (version < 3) {
                                int id = in.readInt();
                                uniqueToken = String.valueOf(id);
                            } else {
                                uniqueToken = in.readUTF();
                            }
                        } else if (key.equals(IP_ASSIGNMENT_KEY)) {
                            ipAssignment = IpAssignment.valueOf(in.readUTF());
                        } else if (key.equals(LINK_ADDRESS_KEY)) {
                            LinkAddress parsedLinkAddress =
                                    new LinkAddress(
                                            InetAddresses.parseNumericAddress(in.readUTF()),
                                            in.readInt());
                            if (parsedLinkAddress.getAddress() instanceof Inet4Address
                                    && linkAddress == null) {
                                linkAddress = parsedLinkAddress;
                            } else {
                                loge("Non-IPv4 or duplicate address: " + parsedLinkAddress);
                            }
                        } else if (key.equals(GATEWAY_KEY)) {
                            LinkAddress dest = null;
                            InetAddress gateway = null;
                            if (version == 1) {
                                // only supported default gateways - leave the dest/prefix empty
                                gateway = InetAddresses.parseNumericAddress(in.readUTF());
                                if (gatewayAddress == null) {
                                    gatewayAddress = gateway;
                                } else {
                                    loge("Duplicate gateway: " + gateway.getHostAddress());
                                }
                            } else {
                                if (in.readInt() == 1) {
                                    dest =
                                            new LinkAddress(
                                                    InetAddresses.parseNumericAddress(in.readUTF()),
                                                    in.readInt());
                                }
                                if (in.readInt() == 1) {
                                    gateway = InetAddresses.parseNumericAddress(in.readUTF());
                                }
                                // If the destination is a default IPv4 route, use the gateway
                                // address unless already set.
                                if (dest.getAddress() instanceof Inet4Address
                                        && dest.getPrefixLength() == 0 && gatewayAddress == null) {
                                    gatewayAddress = gateway;
                                } else {
                                    loge("Non-IPv4 default or duplicate route: "
                                            + dest.getAddress());
                                }
                            }
                        } else if (key.equals(DNS_KEY)) {
                            dnsServers.add(InetAddresses.parseNumericAddress(in.readUTF()));
                        } else if (key.equals(PROXY_SETTINGS_KEY)) {
                            proxySettings = ProxySettings.valueOf(in.readUTF());
                        } else if (key.equals(PROXY_HOST_KEY)) {
                            proxyHost = in.readUTF();
                        } else if (key.equals(PROXY_PORT_KEY)) {
                            proxyPort = in.readInt();
                        } else if (key.equals(PROXY_PAC_FILE)) {
                            pacFileUrl = in.readUTF();
                        } else if (key.equals(EXCLUSION_LIST_KEY)) {
                            exclusionList = in.readUTF();
                        } else if (key.equals(EOS)) {
                            break;
                        } else {
                            loge("Ignore unknown key " + key + "while reading");
                        }
                    } catch (IllegalArgumentException e) {
                        loge("Ignore invalid address while reading" + e);
                    }
                } while (true);

                staticIpConfiguration = new StaticIpConfiguration.Builder()
                    .setIpAddress(linkAddress)
                    .setGateway(gatewayAddress)
                    .setDnsServers(dnsServers)
                    .build();

                if (uniqueToken != null) {
                    IpConfiguration config = new IpConfiguration();
                    networks.put(uniqueToken, config);

                    switch (ipAssignment) {
                        case STATIC:
                            config.setStaticIpConfiguration(staticIpConfiguration);
                            config.setIpAssignment(ipAssignment);
                            break;
                        case DHCP:
                            config.setIpAssignment(ipAssignment);
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED IP on file, use DHCP");
                            config.setIpAssignment(IpAssignment.DHCP);
                            break;
                        default:
                            loge("Ignore invalid ip assignment while reading.");
                            config.setIpAssignment(IpAssignment.UNASSIGNED);
                            break;
                    }

                    switch (proxySettings) {
                        case STATIC:
                            ProxyInfo proxyInfo = ProxyInfo.buildDirectProxy(proxyHost, proxyPort,
                                    ProxyUtils.exclusionStringAsList(exclusionList));
                            config.setProxySettings(proxySettings);
                            config.setHttpProxy(proxyInfo);
                            break;
                        case PAC:
                            ProxyInfo proxyPacProperties =
                                    ProxyInfo.buildPacProxy(Uri.parse(pacFileUrl));
                            config.setProxySettings(proxySettings);
                            config.setHttpProxy(proxyPacProperties);
                            break;
                        case NONE:
                            config.setProxySettings(proxySettings);
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED proxy on file, use NONE");
                            config.setProxySettings(ProxySettings.NONE);
                            break;
                        default:
                            loge("Ignore invalid proxy settings while reading");
                            config.setProxySettings(ProxySettings.UNASSIGNED);
                            break;
                    }
                } else {
                    if (DBG) log("Missing id while parsing configuration");
                }
            }
        } catch (EOFException ignore) {
        } catch (IOException e) {
            loge("Error parsing configuration: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {}
            }
        }

        return networks;
    }

    protected static void loge(String s) {
        Log.e(TAG, s);
    }

    protected static void log(String s) {
        Log.d(TAG, s);
    }
}
