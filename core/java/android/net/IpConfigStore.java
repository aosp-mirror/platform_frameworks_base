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

package android.net;

import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.os.Handler;
import android.os.HandlerThread;
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
import java.util.HashMap;
import java.util.Map;

public class IpConfigStore {
    private static final String TAG = "IpConfigStore";
    private static final boolean DBG = true;

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

    private static final int IPCONFIG_FILE_VERSION = 2;

    public IpConfigStore() {
    }

    public void writeIpAndProxyConfigurations(
            String configFilePath, Map<Integer, IpConfiguration> networks) {
        DelayedDiskWrite.write(configFilePath, networks);
    }

    private static class DelayedDiskWrite {
        private static HandlerThread sDiskWriteHandlerThread;
        private static Handler sDiskWriteHandler;
        /* Tracks multiple writes on the same thread */
        private static int sWriteSequence = 0;
        private static final String TAG = "DelayedDiskWrite";

        static void write (final String configFilePath,
                final Map<Integer, IpConfiguration> networks) {
            if (TextUtils.isEmpty(configFilePath)) {
                return;
            }

            /* Do a delayed write to disk on a separate handler thread */
            synchronized (DelayedDiskWrite.class) {
                if (++sWriteSequence == 1) {
                    sDiskWriteHandlerThread = new HandlerThread("IpConfigStoreThread");
                    sDiskWriteHandlerThread.start();
                    sDiskWriteHandler = new Handler(sDiskWriteHandlerThread.getLooper());
                }
            }

            sDiskWriteHandler.post(new Runnable() {
                @Override
                public void run() {
                    onWriteCalled(configFilePath, networks);
                }
            });
        }

        private static void onWriteCalled(
                final String configFilePath, Map<Integer, IpConfiguration> networks) {

            DataOutputStream out = null;
            try {
                out = new DataOutputStream(new BufferedOutputStream(
                            new FileOutputStream(configFilePath)));

                out.writeInt(IPCONFIG_FILE_VERSION);

                for (Integer id : networks.keySet()) {
                    IpConfiguration config = networks.get(id);
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
                                for (RouteInfo route : linkProperties.getRoutes()) {
                                    out.writeUTF(GATEWAY_KEY);
                                    LinkAddress dest = route.getDestination();
                                    if (dest != null) {
                                        out.writeInt(1);
                                        out.writeUTF(dest.getAddress().getHostAddress());
                                        out.writeInt(dest.getNetworkPrefixLength());
                                    } else {
                                        out.writeInt(0);
                                    }
                                    if (route.getGateway() != null) {
                                        out.writeInt(1);
                                        out.writeUTF(route.getGateway().getHostAddress());
                                    } else {
                                        out.writeInt(0);
                                    }
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
                                loge("Ignore invalid ip assignment while writing");
                                break;
                        }

                        switch (config.proxySettings) {
                            case STATIC:
                                ProxyProperties proxyProperties = linkProperties.getHttpProxy();
                                String exclusionList = proxyProperties.getExclusionList();
                                out.writeUTF(PROXY_SETTINGS_KEY);
                                out.writeUTF(config.proxySettings.toString());
                                out.writeUTF(PROXY_HOST_KEY);
                                out.writeUTF(proxyProperties.getHost());
                                out.writeUTF(PROXY_PORT_KEY);
                                out.writeInt(proxyProperties.getPort());
                                out.writeUTF(EXCLUSION_LIST_KEY);
                                out.writeUTF(exclusionList);
                                writeToFile = true;
                                break;
                            case PAC:
                                ProxyProperties proxyPacProperties = linkProperties.getHttpProxy();
                                out.writeUTF(PROXY_SETTINGS_KEY);
                                out.writeUTF(config.proxySettings.toString());
                                out.writeUTF(PROXY_PAC_FILE);
                                out.writeUTF(proxyPacProperties.getPacFileUrl());
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
                                loge("Ignore invalid proxy settings while writing");
                                break;
                        }
                        if (writeToFile) {
                            out.writeUTF(ID_KEY);
                            out.writeInt(id);
                        }
                    } catch (NullPointerException e) {
                        loge("Failure in writing " + config.linkProperties + e);
                    }
                    out.writeUTF(EOS);
                }

            } catch (IOException e) {
                loge("Error writing data file " + configFilePath);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception e) {}
                }

                //Quit if no more writes sent
                synchronized (DelayedDiskWrite.class) {
                    if (--sWriteSequence == 0) {
                        sDiskWriteHandler.getLooper().quit();
                        sDiskWriteHandler = null;
                        sDiskWriteHandlerThread = null;
                    }
                }
            }
        }

        private static void loge(String s) {
            Log.e(TAG, s);
        }
    }

    public Map<Integer, IpConfiguration> readIpAndProxyConfigurations(
            final String configFilePath) {
        Map<Integer, IpConfiguration> networks = new HashMap<Integer, IpConfiguration>();

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                    configFilePath)));

            int version = in.readInt();
            if (version != 2 && version != 1) {
                loge("Bad version on IP configuration file, ignore read");
                return null;
            }

            while (true) {
                int id = -1;
                // Default is DHCP with no proxy
                IpAssignment ipAssignment = IpAssignment.DHCP;
                ProxySettings proxySettings = ProxySettings.NONE;
                LinkProperties linkProperties = new LinkProperties();
                String proxyHost = null;
                String pacFileUrl = null;
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
                            LinkAddress linkAddr = new LinkAddress(
                                    NetworkUtils.numericToInetAddress(in.readUTF()), in.readInt());
                            linkProperties.addLinkAddress(linkAddr);
                        } else if (key.equals(GATEWAY_KEY)) {
                            LinkAddress dest = null;
                            InetAddress gateway = null;
                            if (version == 1) {
                                // only supported default gateways - leave the dest/prefix empty
                                gateway = NetworkUtils.numericToInetAddress(in.readUTF());
                            } else {
                                if (in.readInt() == 1) {
                                    dest = new LinkAddress(
                                            NetworkUtils.numericToInetAddress(in.readUTF()),
                                            in.readInt());
                                }
                                if (in.readInt() == 1) {
                                    gateway = NetworkUtils.numericToInetAddress(in.readUTF());
                                }
                            }
                            linkProperties.addRoute(new RouteInfo(dest, gateway));
                        } else if (key.equals(DNS_KEY)) {
                            linkProperties.addDns(
                                    NetworkUtils.numericToInetAddress(in.readUTF()));
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

                if (id != -1) {
                    IpConfiguration config = new IpConfiguration();
                    networks.put(id, config);

                    config.linkProperties = linkProperties;
                    switch (ipAssignment) {
                        case STATIC:
                        case DHCP:
                            config.ipAssignment = ipAssignment;
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED IP on file, use DHCP");
                            config.ipAssignment = IpAssignment.DHCP;
                            break;
                        default:
                            loge("Ignore invalid ip assignment while reading.");
                            config.ipAssignment = IpAssignment.UNASSIGNED;
                            break;
                    }

                    switch (proxySettings) {
                        case STATIC:
                            config.proxySettings = proxySettings;
                            ProxyProperties proxyProperties =
                                    new ProxyProperties(proxyHost, proxyPort, exclusionList);
                            linkProperties.setHttpProxy(proxyProperties);
                            break;
                        case PAC:
                            config.proxySettings = proxySettings;
                            ProxyProperties proxyPacProperties =
                                    new ProxyProperties(pacFileUrl);
                            linkProperties.setHttpProxy(proxyPacProperties);
                            break;
                        case NONE:
                            config.proxySettings = proxySettings;
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED proxy on file, use NONE");
                            config.proxySettings = ProxySettings.NONE;
                            break;
                        default:
                            loge("Ignore invalid proxy settings while reading");
                            config.proxySettings = ProxySettings.UNASSIGNED;
                            break;
                    }
                } else {
                    if (DBG) log("Missing id while parsing configuration");
                }
            }
        } catch (EOFException ignore) {
        } catch (IOException e) {
            loge("Error parsing configuration" + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {}
            }
        }

        return networks;
    }

    protected void loge(String s) {
        Log.e(TAG, s);
    }

    protected void log(String s) {
        Log.d(TAG, s);
    }
}
