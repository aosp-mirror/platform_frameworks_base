/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.providers.settings;

import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.BitSet;


/**
 * Backup/Restore Serializer Class for com.android.net.wifi.WifiConfiguration
 */
public class WiFiConfigurationSerializer {
    private static final boolean DEBUG = false;
    private static final String TAG = "WiFiConfigSerializer";

    private static final int NULL = 0;
    private static final int NOT_NULL = 1;
    /**
     * Current Version of the Serializer.
     */
    private static int STATE_VERSION = 1;

    /**
     * write the Network selecton status to Byte Array
     */
    private static void writeNetworkSelectionStatus(WifiConfiguration config, DataOutputStream dest)
            throws IOException {
        WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();

        dest.writeInt(status.getNetworkSelectionStatus());
        dest.writeInt(status.getNetworkSelectionDisableReason());
        for (int index = WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                index < WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX;
                index++) {
            dest.writeInt(status.getDisableReasonCounter(index));
        }
        dest.writeLong(status.getDisableTime());
        writeString(dest, status.getNetworkSelectionBSSID());
    }

    /**
     * Marshals a WifiConfig object into a byte-array.
     *
     * @param wifiConfig - WifiConfiguration to be Marshalled
     * @return byte array
     */

    public static byte[] marshalWifiConfig(WifiConfiguration wifiConfig) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if(wifiConfig != null) {
            DataOutputStream out = new DataOutputStream(baos);
            try {
                out.writeInt(STATE_VERSION);
                out.writeInt(wifiConfig.networkId);
                out.writeInt(wifiConfig.status);
                writeNetworkSelectionStatus(wifiConfig, out);
                writeString(out, wifiConfig.SSID);
                writeString(out, wifiConfig.BSSID);
                out.writeInt(wifiConfig.apBand);
                out.writeInt(wifiConfig.apChannel);
                writeString(out, wifiConfig.FQDN);
                writeString(out, wifiConfig.providerFriendlyName);
                out.writeInt(wifiConfig.roamingConsortiumIds.length);
                for (long id : wifiConfig.roamingConsortiumIds) {
                    out.writeLong(id);
                }
                writeString(out, wifiConfig.preSharedKey);
                for (String wepKey : wifiConfig.wepKeys) {
                    writeString(out, wepKey);
                }
                out.writeInt(wifiConfig.wepTxKeyIndex);
                out.writeInt(wifiConfig.priority);
                out.writeInt(wifiConfig.hiddenSSID ? 1 : 0);
                out.writeInt(wifiConfig.requirePMF ? 1 : 0);
                writeString(out, wifiConfig.updateIdentifier);

                writeBitSet(out, wifiConfig.allowedKeyManagement);
                writeBitSet(out, wifiConfig.allowedProtocols);
                writeBitSet(out, wifiConfig.allowedAuthAlgorithms);
                writeBitSet(out, wifiConfig.allowedPairwiseCiphers);
                writeBitSet(out, wifiConfig.allowedGroupCiphers);


                //IpConfiguration
                writeIpConfiguration(out, wifiConfig.getIpConfiguration());

                writeString(out, wifiConfig.dhcpServer);
                writeString(out, wifiConfig.defaultGwMacAddress);
                out.writeInt(wifiConfig.selfAdded ? 1 : 0);
                out.writeInt(wifiConfig.didSelfAdd ? 1 : 0);
                out.writeInt(wifiConfig.validatedInternetAccess ? 1 : 0);
                out.writeInt(wifiConfig.ephemeral ? 1 : 0);
                out.writeInt(wifiConfig.creatorUid);
                out.writeInt(wifiConfig.lastConnectUid);
                out.writeInt(wifiConfig.lastUpdateUid);
                writeString(out, wifiConfig.creatorName);
                writeString(out, wifiConfig.lastUpdateName);
                out.writeLong(wifiConfig.lastConnectionFailure);
                out.writeLong(wifiConfig.lastRoamingFailure);
                out.writeInt(wifiConfig.lastRoamingFailureReason);
                out.writeInt(wifiConfig.numScorerOverride);
                out.writeInt(wifiConfig.numScorerOverrideAndSwitchedNetwork);
                out.writeInt(wifiConfig.numAssociation);
                out.writeInt(wifiConfig.numUserTriggeredWifiDisableLowRSSI);
                out.writeInt(wifiConfig.numUserTriggeredWifiDisableBadRSSI);
                out.writeInt(wifiConfig.numUserTriggeredWifiDisableNotHighRSSI);
                out.writeInt(wifiConfig.numTicksAtLowRSSI);
                out.writeInt(wifiConfig.numTicksAtBadRSSI);
                out.writeInt(wifiConfig.numTicksAtNotHighRSSI);
                out.writeInt(wifiConfig.numUserTriggeredJoinAttempts);
                out.writeInt(wifiConfig.userApproved);
                out.writeInt(wifiConfig.numNoInternetAccessReports);
                out.writeInt(wifiConfig.noInternetAccessExpected ? 1 : 0);
            } catch (IOException ioe) {
                Log.e(TAG, "Failed to Convert WifiConfiguration to byte array", ioe);
                baos.reset();
            }
        }
        return baos.toByteArray();
    }

    /**
     *
     */
    private static void readNetworkSelectionStatusFromByteArray(DataInputStream in,
            WifiConfiguration config, int version) throws IOException {
        WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();
        status.setNetworkSelectionStatus(in.readInt());
        status.setNetworkSelectionDisableReason(in.readInt());
        for (int index = WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                index < WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX;
                index++) {
            status.setDisableReasonCounter(index, in.readInt());
        }
        status.setDisableTime(in.readLong());
        status.setNetworkSelectionBSSID(readString(in, version));
    }

    /**
     * Unmarshals a byte array into a WifiConfig Object
     *
     * @param data - marshalled WifiConfig Object
     * @return WifiConfiguration Object
     */

    public static WifiConfiguration unmarshalWifiConfig(byte[] data) {
        if (data == null ||  data.length == 0) {
            return null;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        WifiConfiguration config = new WifiConfiguration();
        try {
            int version = in.readInt();

            config.networkId = in.readInt();
            config.status = in.readInt();
            readNetworkSelectionStatusFromByteArray(in, config, version);
            config.SSID = readString(in, version);
            config.BSSID = readString(in, version);
            config.apBand = in.readInt();
            config.apChannel = in.readInt();
            config.FQDN = readString(in, version);
            config.providerFriendlyName = readString(in, version);
            int numRoamingConsortiumIds = in.readInt();
            config.roamingConsortiumIds = new long[numRoamingConsortiumIds];
            for (int i = 0; i < numRoamingConsortiumIds; i++) {
                config.roamingConsortiumIds[i] = in.readLong();
            }
            config.preSharedKey = readString(in, version);
            for (int i = 0; i < config.wepKeys.length; i++) {
                config.wepKeys[i] = readString(in, version);
            }
            config.wepTxKeyIndex = in.readInt();
            config.priority = in.readInt();
            config.hiddenSSID = in.readInt() != 0;
            config.requirePMF = in.readInt() != 0;
            config.updateIdentifier = readString(in, version);

            config.allowedKeyManagement = readBitSet(in, version);
            config.allowedProtocols = readBitSet(in, version);
            config.allowedAuthAlgorithms = readBitSet(in, version);
            config.allowedPairwiseCiphers = readBitSet(in, version);
            config.allowedGroupCiphers = readBitSet(in, version);

            //Not backed-up because EnterpriseConfig involves
            //Certificates which are device specific.
            //config.enterpriseConfig = new WifiEnterpriseConfig();

            config.setIpConfiguration(readIpConfiguration(in, version));


            config.dhcpServer = readString(in, version);
            config.defaultGwMacAddress = readString(in, version);
            config.selfAdded = in.readInt() != 0;
            config.didSelfAdd = in.readInt() != 0;
            config.validatedInternetAccess = in.readInt() != 0;
            config.ephemeral = in.readInt() != 0;
            config.creatorUid = in.readInt();
            config.lastConnectUid = in.readInt();
            config.lastUpdateUid = in.readInt();
            config.creatorName = readString(in, version);
            config.lastUpdateName = readString(in, version);
            config.lastConnectionFailure = in.readLong();
            config.lastRoamingFailure = in.readLong();
            config.lastRoamingFailureReason = in.readInt();
            config.roamingFailureBlackListTimeMilli = in.readLong();
            config.numScorerOverride = in.readInt();
            config.numScorerOverrideAndSwitchedNetwork = in.readInt();
            config.numAssociation = in.readInt();
            config.numUserTriggeredWifiDisableLowRSSI = in.readInt();
            config.numUserTriggeredWifiDisableBadRSSI = in.readInt();
            config.numUserTriggeredWifiDisableNotHighRSSI = in.readInt();
            config.numTicksAtLowRSSI = in.readInt();
            config.numTicksAtBadRSSI = in.readInt();
            config.numTicksAtNotHighRSSI = in.readInt();
            config.numUserTriggeredJoinAttempts = in.readInt();
            config.userApproved = in.readInt();
            config.numNoInternetAccessReports = in.readInt();
            config.noInternetAccessExpected = in.readInt() != 0;
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to convert byte array to WifiConfiguration object", ioe);
            return null;
        }
        return config;
    }

    private static ProxyInfo readProxyInfo(DataInputStream in, int version) throws IOException {
        int isNull = in.readByte();
        if (isNull == NULL) return null;
        String host = readString(in, version);
        int port = in.readInt();
        String exclusionList = readString(in, version);
        return new ProxyInfo(host, port, exclusionList);
    }

    private static void writeProxyInfo(DataOutputStream out, ProxyInfo proxyInfo) throws IOException {
        if (proxyInfo != null) {
            out.writeByte(NOT_NULL);
            writeString(out, proxyInfo.getHost());
            out.writeInt(proxyInfo.getPort());
            writeString(out, proxyInfo.getExclusionListAsString());
        } else {
            out.writeByte(NULL);
        }
    }

    private static InetAddress readInetAddress(DataInputStream in, int version) throws IOException {
        int isNull = in.readByte();
        if (isNull == NULL) return null;
        InetAddress address = null;
        int addressLength = in.readInt();
        if (addressLength < 1) return address;
        byte[] addressBytes = new byte[addressLength];
        in.read(addressBytes, 0, addressLength);
        try {
            address = InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException unknownHostException) {
            return null;
        }
        return address;
    }

    private static void writeInetAddress(DataOutputStream out, InetAddress address) throws IOException {
        if (address.getAddress() != null) {
            out.writeByte(NOT_NULL);
            out.writeInt(address.getAddress().length);
            out.write(address.getAddress(), 0, address.getAddress().length);
        } else {
            out.writeByte(NULL);
        }
    }

    private static LinkAddress readLinkAddress(DataInputStream in, int version) throws IOException {
        int isNull = in.readByte();
        if (isNull == NULL) return null;
        InetAddress address = readInetAddress(in, version);
        int prefixLength = in.readInt();
        int flags = in.readInt();
        int scope = in.readInt();
        return new LinkAddress(address, prefixLength, flags, scope);
    }

    private static void writeLinkAddress(DataOutputStream out, LinkAddress address) throws IOException {
        if (address != null) {
            out.writeByte(NOT_NULL);
            writeInetAddress(out, address.getAddress());
            out.writeInt(address.getPrefixLength());
            out.writeInt(address.getFlags());
            out.writeInt(address.getScope());
        } else {
            out.writeByte(NULL);
        }
    }

    private static StaticIpConfiguration readStaticIpConfiguration(DataInputStream in, int version) throws IOException {
        int isNull = in.readByte();
        if (isNull == NULL) return null;
        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        staticIpConfiguration.ipAddress = readLinkAddress(in, version);
        staticIpConfiguration.gateway = readInetAddress(in, version);
        int dnsServersLength = in.readInt();
        for (int i = 0; i < dnsServersLength; i++) {
            staticIpConfiguration.dnsServers.add(readInetAddress(in, version));
        }
        staticIpConfiguration.domains = readString(in, version);
        return staticIpConfiguration;
    }

    private static void writeStaticIpConfiguration(DataOutputStream out, StaticIpConfiguration staticIpConfiguration) throws IOException {
        if (staticIpConfiguration != null) {
            out.writeByte(NOT_NULL);
            writeLinkAddress(out, staticIpConfiguration.ipAddress);
            writeInetAddress(out, staticIpConfiguration.gateway);
            out.writeInt(staticIpConfiguration.dnsServers.size());
            for (InetAddress inetAddress : staticIpConfiguration.dnsServers) {
                writeInetAddress(out, inetAddress);
            }
            writeString(out, staticIpConfiguration.domains);
        } else {
            out.writeByte(NULL);
        }
    }

    private static IpConfiguration readIpConfiguration(DataInputStream in, int version) throws IOException {
        int isNull = in.readByte();
        if (isNull == NULL) return null;
        IpConfiguration ipConfiguration = new IpConfiguration();
        String tmp = readString(in, version);
        ipConfiguration.ipAssignment = tmp == null ? null : IpConfiguration.IpAssignment.valueOf(tmp);
        tmp = readString(in, version);
        ipConfiguration.proxySettings = tmp == null ? null : IpConfiguration.ProxySettings.valueOf(tmp);
        ipConfiguration.staticIpConfiguration = readStaticIpConfiguration(in, version);
        ipConfiguration.httpProxy = readProxyInfo(in, version);
        return ipConfiguration;
    }


    private static void writeIpConfiguration(DataOutputStream out, IpConfiguration ipConfiguration) throws IOException {
        if (ipConfiguration != null) {
            out.writeByte(NOT_NULL);
            writeString(out, ipConfiguration.ipAssignment != null ? ipConfiguration.ipAssignment.name() : null);
            writeString(out, ipConfiguration.proxySettings != null ? ipConfiguration.proxySettings.name() : null);
            writeStaticIpConfiguration(out, ipConfiguration.staticIpConfiguration);
            writeProxyInfo(out, ipConfiguration.httpProxy);
        } else {
            out.writeByte(NULL);
        }

    }

    private static String readString(DataInputStream in, int version) throws IOException {
        byte isNull = in.readByte();
        if (isNull == NOT_NULL) {
            return in.readUTF();
        }
        return null;
    }

    private static void writeString(DataOutputStream out, String val) throws IOException {
        if (val != null) {
            out.writeByte(NOT_NULL);
            out.writeUTF(val);
        } else {
            out.writeByte(NULL);
        }
    }

    private static BitSet readBitSet(DataInputStream in, int version) throws IOException {
        byte isNull = in.readByte();
        if (isNull == NOT_NULL) {
            int length = in.readInt();
            byte[] bytes = new byte[length];
            in.read(bytes, 0, length);
            return BitSet.valueOf(bytes);
        }
        return new BitSet();
    }

    private static void writeBitSet(DataOutputStream out, BitSet val) throws IOException {
        if (val != null) {
            out.writeByte(NOT_NULL);
            byte[] byteArray = val.toByteArray();
            out.writeInt(byteArray.length);
            out.write(byteArray);
        } else {
            out.writeByte(NULL);
        }
    }
}
