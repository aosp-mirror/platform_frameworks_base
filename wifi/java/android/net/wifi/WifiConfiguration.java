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

import android.net.IpConfiguration;
import android.net.IpConfiguration.ProxySettings;
import android.net.IpConfiguration.IpAssignment;
import android.net.ProxyInfo;
import android.net.LinkProperties;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.BitSet;

/**
 * A class representing a configured Wi-Fi network, including the
 * security configuration.
 */
public class WifiConfiguration implements Parcelable {
    private static final String TAG = "WifiConfiguration";
    /** {@hide} */
    public static final String ssidVarName = "ssid";
    /** {@hide} */
    public static final String bssidVarName = "bssid";
    /** {@hide} */
    public static final String pskVarName = "psk";
    /** {@hide} */
    public static final String[] wepKeyVarNames = { "wep_key0", "wep_key1", "wep_key2", "wep_key3" };
    /** {@hide} */
    public static final String wepTxKeyIdxVarName = "wep_tx_keyidx";
    /** {@hide} */
    public static final String priorityVarName = "priority";
    /** {@hide} */
    public static final String hiddenSSIDVarName = "scan_ssid";
    /** {@hide} */
    public static final int INVALID_NETWORK_ID = -1;
    /**
     * Recognized key management schemes.
     */
    public static class KeyMgmt {
        private KeyMgmt() { }

        /** WPA is not used; plaintext or static WEP could be used. */
        public static final int NONE = 0;
        /** WPA pre-shared key (requires {@code preSharedKey} to be specified). */
        public static final int WPA_PSK = 1;
        /** WPA using EAP authentication. Generally used with an external authentication server. */
        public static final int WPA_EAP = 2;
        /** IEEE 802.1X using EAP authentication and (optionally) dynamically
         * generated WEP keys. */
        public static final int IEEE8021X = 3;

        /** WPA2 pre-shared key for use with soft access point
          * (requires {@code preSharedKey} to be specified).
          * @hide
          */
        public static final int WPA2_PSK = 4;

        public static final String varName = "key_mgmt";

        public static final String[] strings = { "NONE", "WPA_PSK", "WPA_EAP", "IEEE8021X",
                "WPA2_PSK" };
    }

    /**
     * Recognized security protocols.
     */
    public static class Protocol {
        private Protocol() { }

        /** WPA/IEEE 802.11i/D3.0 */
        public static final int WPA = 0;
        /** WPA2/IEEE 802.11i */
        public static final int RSN = 1;

        public static final String varName = "proto";

        public static final String[] strings = { "WPA", "RSN" };
    }

    /**
     * Recognized IEEE 802.11 authentication algorithms.
     */
    public static class AuthAlgorithm {
        private AuthAlgorithm() { }

        /** Open System authentication (required for WPA/WPA2) */
        public static final int OPEN = 0;
        /** Shared Key authentication (requires static WEP keys) */
        public static final int SHARED = 1;
        /** LEAP/Network EAP (only used with LEAP) */
        public static final int LEAP = 2;

        public static final String varName = "auth_alg";

        public static final String[] strings = { "OPEN", "SHARED", "LEAP" };
    }

    /**
     * Recognized pairwise ciphers for WPA.
     */
    public static class PairwiseCipher {
        private PairwiseCipher() { }

        /** Use only Group keys (deprecated) */
        public static final int NONE = 0;
        /** Temporal Key Integrity Protocol [IEEE 802.11i/D7.0] */
        public static final int TKIP = 1;
        /** AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0] */
        public static final int CCMP = 2;

        public static final String varName = "pairwise";

        public static final String[] strings = { "NONE", "TKIP", "CCMP" };
    }

    /**
     * Recognized group ciphers.
     * <pre>
     * CCMP = AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0]
     * TKIP = Temporal Key Integrity Protocol [IEEE 802.11i/D7.0]
     * WEP104 = WEP (Wired Equivalent Privacy) with 104-bit key
     * WEP40 = WEP (Wired Equivalent Privacy) with 40-bit key (original 802.11)
     * </pre>
     */
    public static class GroupCipher {
        private GroupCipher() { }

        /** WEP40 = WEP (Wired Equivalent Privacy) with 40-bit key (original 802.11) */
        public static final int WEP40 = 0;
        /** WEP104 = WEP (Wired Equivalent Privacy) with 104-bit key */
        public static final int WEP104 = 1;
        /** Temporal Key Integrity Protocol [IEEE 802.11i/D7.0] */
        public static final int TKIP = 2;
        /** AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0] */
        public static final int CCMP = 3;

        public static final String varName = "group";

        public static final String[] strings = { "WEP40", "WEP104", "TKIP", "CCMP" };
    }

    /** Possible status of a network configuration. */
    public static class Status {
        private Status() { }

        /** this is the network we are currently connected to */
        public static final int CURRENT = 0;
        /** supplicant will not attempt to use this network */
        public static final int DISABLED = 1;
        /** supplicant will consider this network available for association */
        public static final int ENABLED = 2;

        public static final String[] strings = { "current", "disabled", "enabled" };
    }

    /** @hide */
    public static final int DISABLED_UNKNOWN_REASON                         = 0;
    /** @hide */
    public static final int DISABLED_DNS_FAILURE                            = 1;
    /** @hide */
    public static final int DISABLED_DHCP_FAILURE                           = 2;
    /** @hide */
    public static final int DISABLED_AUTH_FAILURE                           = 3;
    /** @hide */
    public static final int DISABLED_ASSOCIATION_REJECT                     = 4;

    /**
     * The ID number that the supplicant uses to identify this
     * network configuration entry. This must be passed as an argument
     * to most calls into the supplicant.
     */
    public int networkId;

    /**
     * The current status of this network configuration entry.
     * @see Status
     */
    public int status;

    /**
     * The code referring to a reason for disabling the network
     * Valid when {@link #status} == Status.DISABLED
     * @hide
     */
    public int disableReason;

    /**
     * The network's SSID. Can either be an ASCII string,
     * which must be enclosed in double quotation marks
     * (e.g., {@code "MyNetwork"}, or a string of
     * hex digits,which are not enclosed in quotes
     * (e.g., {@code 01a243f405}).
     */
    public String SSID;
    /**
     * When set, this network configuration entry should only be used when
     * associating with the AP having the specified BSSID. The value is
     * a string in the format of an Ethernet MAC address, e.g.,
     * <code>XX:XX:XX:XX:XX:XX</code> where each <code>X</code> is a hex digit.
     */
    public String BSSID;

    /**
     * Pre-shared key for use with WPA-PSK.
     * <p/>
     * When the value of this key is read, the actual key is
     * not returned, just a "*" if the key has a value, or the null
     * string otherwise.
     */
    public String preSharedKey;
    /**
     * Up to four WEP keys. Either an ASCII string enclosed in double
     * quotation marks (e.g., {@code "abcdef"} or a string
     * of hex digits (e.g., {@code 0102030405}).
     * <p/>
     * When the value of one of these keys is read, the actual key is
     * not returned, just a "*" if the key has a value, or the null
     * string otherwise.
     */
    public String[] wepKeys;

    /** Default WEP key index, ranging from 0 to 3. */
    public int wepTxKeyIndex;

    /**
     * Priority determines the preference given to a network by {@code wpa_supplicant}
     * when choosing an access point with which to associate.
     */
    public int priority;

    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    public boolean hiddenSSID;

    /**
     * The set of key management protocols supported by this configuration.
     * See {@link KeyMgmt} for descriptions of the values.
     * Defaults to WPA-PSK WPA-EAP.
     */
    public BitSet allowedKeyManagement;
    /**
     * The set of security protocols supported by this configuration.
     * See {@link Protocol} for descriptions of the values.
     * Defaults to WPA RSN.
     */
    public BitSet allowedProtocols;
    /**
     * The set of authentication protocols supported by this configuration.
     * See {@link AuthAlgorithm} for descriptions of the values.
     * Defaults to automatic selection.
     */
    public BitSet allowedAuthAlgorithms;
    /**
     * The set of pairwise ciphers for WPA supported by this configuration.
     * See {@link PairwiseCipher} for descriptions of the values.
     * Defaults to CCMP TKIP.
     */
    public BitSet allowedPairwiseCiphers;
    /**
     * The set of group ciphers supported by this configuration.
     * See {@link GroupCipher} for descriptions of the values.
     * Defaults to CCMP TKIP WEP104 WEP40.
     */
    public BitSet allowedGroupCiphers;
    /**
     * The enterprise configuration details specifying the EAP method,
     * certificates and other settings associated with the EAP.
     */
    public WifiEnterpriseConfig enterpriseConfig;

    /**
     * @hide
     */
    private IpConfiguration mIpConfiguration;

    /**
     * @hide
     * dhcp server MAC address if known
     */
    public String dhcpServer;

    /**
     * @hide
     * default Gateway MAC address if known
     */
    public String defaultGwMacAddress;

    /**
     * @hide
     * last failure
     */
    public String lastFailure;

    /**
     * @hide
     * Uid of app creating the configuration
     */
    public int creatorUid;

    /**
     * @hide
     * Uid of last app issuing a connection related command
     */
    public int lastConnectUid;

    /**
     * @hide
     * Uid of last app modifying the configuration
     */
    public int lastUpdateUid;

    /**
     * @hide
     * BSSID list on which this configuration was seen.
     * TODO: prevent this list to grow infinitely, age-out the results
     */
    public HashMap<String, ScanResult> scanResultCache;

    /** @hide **/
    public static int INVALID_RSSI = -127;

    /**
     * @hide
     * A summary of the RSSI and Band status for that configuration
     * This is used as a temporary value by the auto-join controller
     */
    public final class Visibility {
        public int rssi5;   // strongest 5GHz RSSI
        public int rssi24;  // strongest 2.4GHz RSSI
        public int num5;    // number of BSSIDs on 5GHz
        public int num24;   // number of BSSIDs on 2.4GHz
        public long age5;  // timestamp of the strongest 5GHz BSSID (last time it was seen)
        public long age24;   // timestamp of the strongest 2.4GHz BSSID (last time it was seen)

        public Visibility() {
            rssi5 = INVALID_RSSI;
            rssi24 = INVALID_RSSI;
        }

        public Visibility(Visibility source) {
            rssi5 = source.rssi5;
            rssi24 = source.rssi24;
            age24 = source.age24;
            age5 = source.age5;
            num24 = source.num24;
            num5 = source.num5;
        }

        @Override
        public String toString() {
            StringBuilder sbuf = new StringBuilder();
            sbuf.append("[");
            if (rssi24 > INVALID_RSSI) {
                sbuf.append(Integer.toString(rssi24));
                sbuf.append(",");
                sbuf.append(Integer.toString(num24));
            } else {
                sbuf.append("*");
            }
            sbuf.append(" - ");
            if (rssi5 > INVALID_RSSI) {
                sbuf.append(Integer.toString(rssi5));
                sbuf.append(",");
                sbuf.append(Integer.toString(num5));
            }
            sbuf.append("]");
            return sbuf.toString();
        }
    }

    /** @hide
     * Cache the visibility status of this configuration.
     * Visibility can change at any time depending on scan results availability.
     * Owner of the WifiConfiguration is responsible to set this field based on
     * recent scan results.
     ***/
    public Visibility visibility;

    /** @hide
     * calculate and set Visibility for that configuration.
     *
     * age in milliseconds: we will consider only ScanResults that are more recent,
     * i.e. younger.
     ***/
    public Visibility setVisibility(long age) {
        if (scanResultCache == null) {
            visibility = null;
            return null;
        }

        Visibility status = new Visibility();

        long now_ms = System.currentTimeMillis();
        for(ScanResult result : scanResultCache.values()) {
            if (result.seen == 0)
                continue;

            if ((result.frequency > 4900) && (result.frequency < 5900)) {
                //strictly speaking: [4915, 5825]
                //number of known BSSID on 5GHz band
                status.num5 = status.num5 + 1;
            } else if ((result.frequency > 2400) && (result.frequency < 2500)) {
                //strictly speaking: [2412, 2482]
                //number of known BSSID on 2.4Ghz band
                status.num24 = status.num24 + 1;
            }

            if ((now_ms - result.seen) > age) continue;

            if ((result.frequency > 4900) && (result.frequency < 5900)) {
                if (result.level > status.rssi5) {
                    status.rssi5 = result.level;
                    status.age5 = result.seen;
                }
            } else if ((result.frequency > 2400) && (result.frequency < 2500)) {
                if (result.level > status.rssi24) {
                    status.rssi24 = result.level;
                    status.age24 = result.seen;
                }
            }
        }
        visibility = status;
        return status;
    }

    /** @hide */
    public static final int AUTO_JOIN_ENABLED                   = 0;
    /** @hide
     * if this is set, the WifiConfiguration cannot use linkages so as to bump
     * it's relative priority.
     * - status between and 128 indicate various level of blacklisting depending
     * on the severity or frequency of the connection error
     * - deleted status indicates that the user is deleting the configuration, and so
     * although it may have been self added we will not re-self-add it, ignore it,
     * not return it to applications, and not connect to it
     * */
    public static final int AUTO_JOIN_TEMPORARY_DISABLED  = 1;
    /** @hide */
    public static final int AUTO_JOIN_DISABLED_ON_AUTH_FAILURE  = 128;
    /** @hide */
    public static final int AUTO_JOIN_DELETED  = 200;

    /**
     * @hide
     */
    public int autoJoinStatus;

    /**
     * Set if the configuration was self added by the framework
     * This boolean is cleared if we get a connect/save/ update or
     * any wifiManager command that indicate the user interacted with the configuration
     * since we will now consider that the configuration belong to him.
     * @hide
     */
    public boolean selfAdded;

    /**
     * Set if the configuration was self added by the framework
     * This boolean is set once and never cleared. It is used
     * so as we never loose track of who created the
     * configuration in the first place.
     * @hide
     */
    public boolean didSelfAdd;

    /**
     * @hide
     * Indicate that a WifiConfiguration is temporary and should not be saved
     * nor considered by AutoJoin.
     */
    public boolean ephemeral;

    /**
     * @hide
     * Connect choices
     *
     * remember the keys identifying the known WifiConfiguration over which this configuration
     * was preferred by user or a "WiFi Network Management app", that is,
     * a WifiManager.CONNECT_NETWORK or SELECT_NETWORK was received while this configuration
     * was visible to the user:
     * configKey is : "SSID"-WEP-WPA_PSK-WPA_EAP
     *
     * The integer represents the configuration's RSSI at that time (useful?)
     *
     * The overall auto-join algorithm make use of past connect choice so as to sort configuration,
     * the exact algorithm still fluctuating as of 5/7/2014
     *
     */
    public HashMap<String, Integer> connectChoices;

    /**
     * @hide
     * Linked Configurations: represent the set of Wificonfigurations that are equivalent
     * regarding roaming and auto-joining.
     * The linked configuration may or may not have same SSID, and may or may not have same
     * credentials.
     * For instance, linked configurations will have same defaultGwMacAddress or same dhcp server.
     */
    public HashMap<String, Integer>  linkedConfigurations;

    public WifiConfiguration() {
        networkId = INVALID_NETWORK_ID;
        SSID = null;
        BSSID = null;
        priority = 0;
        hiddenSSID = false;
        disableReason = DISABLED_UNKNOWN_REASON;
        allowedKeyManagement = new BitSet();
        allowedProtocols = new BitSet();
        allowedAuthAlgorithms = new BitSet();
        allowedPairwiseCiphers = new BitSet();
        allowedGroupCiphers = new BitSet();
        wepKeys = new String[4];
        for (int i = 0; i < wepKeys.length; i++) {
            wepKeys[i] = null;
        }
        enterpriseConfig = new WifiEnterpriseConfig();
        autoJoinStatus = AUTO_JOIN_ENABLED;
        selfAdded = false;
        didSelfAdd = false;
        ephemeral = false;
        mIpConfiguration = new IpConfiguration();
    }

    /**
     * indicates whether the configuration is valid
     * @return true if valid, false otherwise
     * @hide
     */
    public boolean isValid() {

        if (allowedKeyManagement == null)
            return false;

        if (allowedKeyManagement.cardinality() > 1) {
            if (allowedKeyManagement.cardinality() != 2) {
                return false;
            }
            if (allowedKeyManagement.get(KeyMgmt.WPA_EAP) == false) {
                return false;
            }
            if ((allowedKeyManagement.get(KeyMgmt.IEEE8021X) == false)
                    && (allowedKeyManagement.get(KeyMgmt.WPA_PSK) == false)) {
                return false;
            }
        }

        // TODO: Add more checks
        return true;

    }

    /**
     * most recent time we have seen this configuration
     * @return most recent scanResult
     * @hide
     */
    public ScanResult lastSeen() {
        ScanResult mostRecent = null;

        if (scanResultCache == null) {
            return null;
        }

        for (ScanResult result : scanResultCache.values()) {
            if (mostRecent == null) {
                if (result.seen != 0)
                   mostRecent = result;
            } else {
                if (result.seen > mostRecent.seen) {
                   mostRecent = result;
                }
            }
        }
        return mostRecent;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        if (this.status == WifiConfiguration.Status.CURRENT) {
            sbuf.append("* ");
        } else if (this.status == WifiConfiguration.Status.DISABLED) {
            sbuf.append("- DSBLE: ").append(this.disableReason).append(" ");
        }
        sbuf.append("ID: ").append(this.networkId).append(" SSID: ").append(this.SSID).
                append(" BSSID: ").append(this.BSSID).append(" PRIO: ").append(this.priority).
                append('\n');
        sbuf.append(" KeyMgmt:");
        for (int k = 0; k < this.allowedKeyManagement.size(); k++) {
            if (this.allowedKeyManagement.get(k)) {
                sbuf.append(" ");
                if (k < KeyMgmt.strings.length) {
                    sbuf.append(KeyMgmt.strings[k]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append(" Protocols:");
        for (int p = 0; p < this.allowedProtocols.size(); p++) {
            if (this.allowedProtocols.get(p)) {
                sbuf.append(" ");
                if (p < Protocol.strings.length) {
                    sbuf.append(Protocol.strings[p]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" AuthAlgorithms:");
        for (int a = 0; a < this.allowedAuthAlgorithms.size(); a++) {
            if (this.allowedAuthAlgorithms.get(a)) {
                sbuf.append(" ");
                if (a < AuthAlgorithm.strings.length) {
                    sbuf.append(AuthAlgorithm.strings[a]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" PairwiseCiphers:");
        for (int pc = 0; pc < this.allowedPairwiseCiphers.size(); pc++) {
            if (this.allowedPairwiseCiphers.get(pc)) {
                sbuf.append(" ");
                if (pc < PairwiseCipher.strings.length) {
                    sbuf.append(PairwiseCipher.strings[pc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" GroupCiphers:");
        for (int gc = 0; gc < this.allowedGroupCiphers.size(); gc++) {
            if (this.allowedGroupCiphers.get(gc)) {
                sbuf.append(" ");
                if (gc < GroupCipher.strings.length) {
                    sbuf.append(GroupCipher.strings[gc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n').append(" PSK: ");
        if (this.preSharedKey != null) {
            sbuf.append('*');
        }

        sbuf.append(enterpriseConfig);
        sbuf.append('\n');

        sbuf.append(mIpConfiguration.toString());

        if (selfAdded)  sbuf.append("selfAdded");
        if (creatorUid != 0)  sbuf.append("uid=" + Integer.toString(creatorUid));


        return sbuf.toString();
    }

    /**
     * Construct a WifiConfiguration from a scanned network
     * @param scannedAP the scan result used to construct the config entry
     * TODO: figure out whether this is a useful way to construct a new entry.
     *
    public WifiConfiguration(ScanResult scannedAP) {
        networkId = -1;
        SSID = scannedAP.SSID;
        BSSID = scannedAP.BSSID;
    }
    */

    /** {@hide} */
    public String getPrintableSsid() {
        if (SSID == null) return "";
        final int length = SSID.length();
        if (length > 2 && (SSID.charAt(0) == '"') && SSID.charAt(length - 1) == '"') {
            return SSID.substring(1, length - 1);
        }

        /** The ascii-encoded string format is P"<ascii-encoded-string>"
         * The decoding is implemented in the supplicant for a newly configured
         * network.
         */
        if (length > 3 && (SSID.charAt(0) == 'P') && (SSID.charAt(1) == '"') &&
                (SSID.charAt(length-1) == '"')) {
            WifiSsid wifiSsid = WifiSsid.createFromAsciiEncoded(
                    SSID.substring(2, length - 1));
            return wifiSsid.toString();
        }
        return SSID;
    }

    /**
     * Get an identifier for associating credentials with this config
     * @param current configuration contains values for additional fields
     *                that are not part of this configuration. Used
     *                when a config with some fields is passed by an application.
     * @throws IllegalStateException if config is invalid for key id generation
     * @hide
     */
    public String getKeyIdForCredentials(WifiConfiguration current) {
        String keyMgmt = null;

        try {
            // Get current config details for fields that are not initialized
            if (TextUtils.isEmpty(SSID)) SSID = current.SSID;
            if (allowedKeyManagement.cardinality() == 0) {
                allowedKeyManagement = current.allowedKeyManagement;
            }
            if (allowedKeyManagement.get(KeyMgmt.WPA_EAP)) {
                keyMgmt = KeyMgmt.strings[KeyMgmt.WPA_EAP];
            }
            if (allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.IEEE8021X];
            }

            if (TextUtils.isEmpty(keyMgmt)) {
                throw new IllegalStateException("Not an EAP network");
            }

            return trimStringForKeyId(SSID) + "_" + keyMgmt + "_" +
                    trimStringForKeyId(enterpriseConfig.getKeyId(current != null ?
                            current.enterpriseConfig : null));
        } catch (NullPointerException e) {
            throw new IllegalStateException("Invalid config details");
        }
    }

    private String trimStringForKeyId(String string) {
        // Remove quotes and spaces
        return string.replace("\"", "").replace(" ", "");
    }

    private static BitSet readBitSet(Parcel src) {
        int cardinality = src.readInt();

        BitSet set = new BitSet();
        for (int i = 0; i < cardinality; i++) {
            set.set(src.readInt());
        }

        return set;
    }

    private static void writeBitSet(Parcel dest, BitSet set) {
        int nextSetBit = -1;

        dest.writeInt(set.cardinality());

        while ((nextSetBit = set.nextSetBit(nextSetBit + 1)) != -1) {
            dest.writeInt(nextSetBit);
        }
    }

    /** @hide */
    public int getAuthType() {
        if (isValid() == false) {
            throw new IllegalStateException("Invalid configuration");
        }
        if (allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            return KeyMgmt.WPA_PSK;
        } else if (allowedKeyManagement.get(KeyMgmt.WPA2_PSK)) {
            return KeyMgmt.WPA2_PSK;
        } else if (allowedKeyManagement.get(KeyMgmt.WPA_EAP)) {
            return KeyMgmt.WPA_EAP;
        } else if (allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            return KeyMgmt.IEEE8021X;
        }
        return KeyMgmt.NONE;
    }

    /* @hide
     * Cache the config key, this seems useful as a speed up since a lot of
     * lookups in the config store are done and based on this key.
     */
    String mCachedConfigKey;

    /** @hide
     *  return the string used to calculate the hash in WifiConfigStore
     *  and uniquely identify this WifiConfiguration
     */
    public String configKey(boolean allowCached) {
        String key;
        if (allowCached && mCachedConfigKey != null) {
            key = mCachedConfigKey;
        } else {
            key = this.SSID;
            if (key == null)
                key = "";
            if (this.wepKeys[0] != null) {
                key = key + "-WEP";
            }
            if (this.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                key = key + "-" + KeyMgmt.strings[KeyMgmt.WPA_PSK];
            }
            if (this.allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                    this.allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
                key = key + "-" + KeyMgmt.strings[KeyMgmt.WPA_EAP];
            }
            mCachedConfigKey = key;
        }
        return key;
    }

    /** @hide
     * get configKey, force calculating the config string
     */
    public String configKey() {
        return configKey(false);
    }

    /** @hide
     * return the config key string based on a scan result
     */
    static public String configKey(ScanResult result) {
        String key = "\"" + result.SSID + "\"";

        if (result.capabilities.contains("WEP")) {
            key = key + "-WEP";
        }

        if (result.capabilities.contains("PSK")) {
            key = key + "-" + KeyMgmt.strings[KeyMgmt.WPA_PSK];
        }

        if (result.capabilities.contains("EAP")) {
            key = key + "-" + KeyMgmt.strings[KeyMgmt.WPA_EAP];
        }

        return key;
    }

    /** @hide */
    public IpConfiguration getIpConfiguration() {
        return mIpConfiguration;
    }

    /** @hide */
    public void setIpConfiguration(IpConfiguration ipConfiguration) {
        mIpConfiguration = ipConfiguration;
    }

    /** @hide */
    public LinkProperties getLinkProperties() {
        return mIpConfiguration.linkProperties;
    }

    /** @hide */
    public void setLinkProperties(LinkProperties linkProperties) {
        mIpConfiguration.linkProperties = linkProperties;
    }

    /** @hide */
    public IpConfiguration.IpAssignment getIpAssignment() {
        return mIpConfiguration.ipAssignment;
    }

    /** @hide */
    public void setIpAssignment(IpConfiguration.IpAssignment ipAssignment) {
        mIpConfiguration.ipAssignment = ipAssignment;
    }

    /** @hide */
    public IpConfiguration.ProxySettings getProxySettings() {
        return mIpConfiguration.proxySettings;
    }

    /** @hide */
    public void setProxySettings(IpConfiguration.ProxySettings proxySettings) {
        mIpConfiguration.proxySettings = proxySettings;
    }

    /** @hide */
    public void setProxy(ProxySettings settings, ProxyInfo proxy) {
        mIpConfiguration.proxySettings = settings;
        mIpConfiguration.linkProperties.setHttpProxy(proxy);
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    public WifiConfiguration(WifiConfiguration source) {
        if (source != null) {
            networkId = source.networkId;
            status = source.status;
            disableReason = source.disableReason;
            disableReason = source.disableReason;
            SSID = source.SSID;
            BSSID = source.BSSID;
            preSharedKey = source.preSharedKey;

            wepKeys = new String[4];
            for (int i = 0; i < wepKeys.length; i++) {
                wepKeys[i] = source.wepKeys[i];
            }

            wepTxKeyIndex = source.wepTxKeyIndex;
            priority = source.priority;
            hiddenSSID = source.hiddenSSID;
            allowedKeyManagement   = (BitSet) source.allowedKeyManagement.clone();
            allowedProtocols       = (BitSet) source.allowedProtocols.clone();
            allowedAuthAlgorithms  = (BitSet) source.allowedAuthAlgorithms.clone();
            allowedPairwiseCiphers = (BitSet) source.allowedPairwiseCiphers.clone();
            allowedGroupCiphers    = (BitSet) source.allowedGroupCiphers.clone();

            enterpriseConfig = new WifiEnterpriseConfig(source.enterpriseConfig);

            defaultGwMacAddress = source.defaultGwMacAddress;

            mIpConfiguration = new IpConfiguration(source.mIpConfiguration);

            if ((source.scanResultCache != null) && (source.scanResultCache.size() > 0)) {
                scanResultCache = new HashMap<String, ScanResult>();
                scanResultCache.putAll(source.scanResultCache);
            }

            if ((source.connectChoices != null) && (source.connectChoices.size() > 0)) {
                connectChoices = new HashMap<String, Integer>();
                connectChoices.putAll(source.connectChoices);
            }

            if ((source.linkedConfigurations != null)
                    && (source.linkedConfigurations.size() > 0)) {
                linkedConfigurations = new HashMap<String, Integer>();
                linkedConfigurations.putAll(source.linkedConfigurations);
            }
            mCachedConfigKey = null; //force null configKey
            autoJoinStatus = source.autoJoinStatus;
            selfAdded = source.selfAdded;

            if (source.visibility != null) {
                visibility = new Visibility(source.visibility);
            }

            lastFailure = source.lastFailure;
            didSelfAdd = source.didSelfAdd;
            lastConnectUid = source.lastConnectUid;
            lastUpdateUid = source.lastUpdateUid;
            creatorUid = source.creatorUid;
        }
    }

    /** {@hide} */
    //public static final int NOTHING_TAG = 0;
    /** {@hide} */
    //public static final int SCAN_CACHE_TAG = 1;

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(networkId);
        dest.writeInt(status);
        dest.writeInt(disableReason);
        dest.writeString(SSID);
        dest.writeString(BSSID);
        dest.writeString(preSharedKey);
        for (String wepKey : wepKeys) {
            dest.writeString(wepKey);
        }
        dest.writeInt(wepTxKeyIndex);
        dest.writeInt(priority);
        dest.writeInt(hiddenSSID ? 1 : 0);

        writeBitSet(dest, allowedKeyManagement);
        writeBitSet(dest, allowedProtocols);
        writeBitSet(dest, allowedAuthAlgorithms);
        writeBitSet(dest, allowedPairwiseCiphers);
        writeBitSet(dest, allowedGroupCiphers);

        dest.writeParcelable(enterpriseConfig, flags);

        dest.writeParcelable(mIpConfiguration, flags);
        dest.writeString(dhcpServer);
        dest.writeString(defaultGwMacAddress);
        dest.writeInt(autoJoinStatus);
        dest.writeInt(selfAdded ? 1 : 0);
        dest.writeInt(didSelfAdd ? 1 : 0);
        dest.writeInt(creatorUid);
        dest.writeInt(lastConnectUid);
        dest.writeInt(lastUpdateUid);
        /*
        TODO: should we write the cache results to the parcel?
        if (scanResultCache != null) {
            dest.writeInt(WifiConfiguration.SCAN_CACHE_TAG);
            dest.writeInt(scanResultCache.size());
            for (ScanResult result : scanResultCache.values()) {
                result.writeToParcel(dest, flags);
            }
        } else {
            dest.writeInt(WifiConfiguration.NOTHING_TAG);
        }*/
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiConfiguration> CREATOR =
        new Creator<WifiConfiguration>() {
            public WifiConfiguration createFromParcel(Parcel in) {
                WifiConfiguration config = new WifiConfiguration();
                config.networkId = in.readInt();
                config.status = in.readInt();
                config.disableReason = in.readInt();
                config.SSID = in.readString();
                config.BSSID = in.readString();
                config.preSharedKey = in.readString();
                for (int i = 0; i < config.wepKeys.length; i++) {
                    config.wepKeys[i] = in.readString();
                }
                config.wepTxKeyIndex = in.readInt();
                config.priority = in.readInt();
                config.hiddenSSID = in.readInt() != 0;
                config.allowedKeyManagement   = readBitSet(in);
                config.allowedProtocols       = readBitSet(in);
                config.allowedAuthAlgorithms  = readBitSet(in);
                config.allowedPairwiseCiphers = readBitSet(in);
                config.allowedGroupCiphers    = readBitSet(in);

                config.enterpriseConfig = in.readParcelable(null);

                config.mIpConfiguration = in.readParcelable(null);
                config.dhcpServer = in.readString();
                config.defaultGwMacAddress = in.readString();
                config.autoJoinStatus = in.readInt();
                config.selfAdded = in.readInt() != 0;
                config.didSelfAdd = in.readInt() != 0;
                config.creatorUid = in.readInt();
                config.lastConnectUid = in.readInt();
                config.lastUpdateUid = in.readInt();
                /*
                TODO: should we write the cache results to the parcel?
                boolean done = false;
                do {
                    int tag = in.readInt();
                    switch (tag) {
                        case WifiConfiguration.SCAN_CACHE_TAG:
                            int size = in.readInt();
                            config.scanResultCache = new HashMap<String, ScanResult>();
                            while (size > 0) {
                                ScanResult result = ScanResult.CREATOR.createFromParcel(in);
                                config.scanResultCache.put(result.BSSID, result);
                                size--;
                            }
                            break;
                        case WifiConfiguration.NOTHING_TAG:
                            done = true;
                            break;
                    }
                } while (!done);*/
                return config;
            }

            public WifiConfiguration[] newArray(int size) {
                return new WifiConfiguration[size];
            }
        };
}
