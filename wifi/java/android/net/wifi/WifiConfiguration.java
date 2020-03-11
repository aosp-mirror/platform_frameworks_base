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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.IpConfiguration.ProxySettings;
import android.net.MacAddress;
import android.net.NetworkSpecifier;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.net.util.MacAddressUtils;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.HashMap;

/**
 * A class representing a configured Wi-Fi network, including the
 * security configuration.
 *
 * @deprecated Use {@link WifiNetworkSpecifier.Builder} to create {@link NetworkSpecifier} and
 * {@link WifiNetworkSuggestion.Builder} to create {@link WifiNetworkSuggestion}. This will become a
 * system use only object in the future.
 */
@Deprecated
public class WifiConfiguration implements Parcelable {
    private static final String TAG = "WifiConfiguration";
    /**
     * Current Version of the Backup Serializer.
    */
    private static final int BACKUP_VERSION = 3;
    /** {@hide} */
    public static final String ssidVarName = "ssid";
    /** {@hide} */
    public static final String bssidVarName = "bssid";
    /** {@hide} */
    public static final String pskVarName = "psk";
    /** {@hide} */
    @Deprecated
    @UnsupportedAppUsage
    public static final String[] wepKeyVarNames = { "wep_key0", "wep_key1", "wep_key2", "wep_key3" };
    /** {@hide} */
    @Deprecated
    public static final String wepTxKeyIdxVarName = "wep_tx_keyidx";
    /** {@hide} */
    public static final String priorityVarName = "priority";
    /** {@hide} */
    public static final String hiddenSSIDVarName = "scan_ssid";
    /** {@hide} */
    public static final String pmfVarName = "ieee80211w";
    /** {@hide} */
    public static final String updateIdentiferVarName = "update_identifier";
    /**
     * The network ID for an invalid network.
     *
     * @hide
     */
    @SystemApi
    public static final int INVALID_NETWORK_ID = -1;
    /** {@hide} */
    public static final int LOCAL_ONLY_NETWORK_ID = -2;

    /** {@hide} */
    private String mPasspointManagementObjectTree;
    /** {@hide} */
    private static final int MAXIMUM_RANDOM_MAC_GENERATION_RETRY = 3;

    /**
     * Recognized key management schemes.
     */
    public static class KeyMgmt {
        private KeyMgmt() { }

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                NONE,
                WPA_PSK,
                WPA_EAP,
                IEEE8021X,
                WPA2_PSK,
                OSEN,
                FT_PSK,
                FT_EAP,
                SAE,
                OWE,
                SUITE_B_192,
                WPA_PSK_SHA256,
                WPA_EAP_SHA256,
                WAPI_PSK,
                WAPI_CERT,
                FILS_SHA256,
                FILS_SHA384})
        public @interface KeyMgmtScheme {}

        /** WPA is not used; plaintext or static WEP could be used. */
        public static final int NONE = 0;
        /** WPA pre-shared key (requires {@code preSharedKey} to be specified). */
        public static final int WPA_PSK = 1;
        /** WPA using EAP authentication. Generally used with an external authentication server. */
        public static final int WPA_EAP = 2;
        /**
         * IEEE 802.1X using EAP authentication and (optionally) dynamically
         * generated WEP keys.
         */
        public static final int IEEE8021X = 3;

        /**
         * WPA2 pre-shared key for use with soft access point
         * (requires {@code preSharedKey} to be specified).
         * @hide
         */
        @SystemApi
        public static final int WPA2_PSK = 4;
        /**
         * Hotspot 2.0 r2 OSEN:
         * @hide
         */
        public static final int OSEN = 5;

        /**
         * IEEE 802.11r Fast BSS Transition with PSK authentication.
         * @hide
         */
        public static final int FT_PSK = 6;

        /**
         * IEEE 802.11r Fast BSS Transition with EAP authentication.
         * @hide
         */
        public static final int FT_EAP = 7;

        /**
         * Simultaneous Authentication of Equals
         */
        public static final int SAE = 8;

        /**
         * Opportunististic Wireless Encryption
         */
        public static final int OWE = 9;

        /**
         * SUITE_B_192 192 bit level
         */
        public static final int SUITE_B_192 = 10;

        /**
         * WPA pre-shared key with stronger SHA256-based algorithms.
         * @hide
         */
        public static final int WPA_PSK_SHA256 = 11;

        /**
         * WPA using EAP authentication with stronger SHA256-based algorithms.
         * @hide
         */
        public static final int WPA_EAP_SHA256 = 12;

        /**
         * WAPI pre-shared key (requires {@code preSharedKey} to be specified).
         * @hide
         */
        @SystemApi
        public static final int WAPI_PSK = 13;

        /**
         * WAPI certificate to be specified.
         * @hide
         */
        @SystemApi
        public static final int WAPI_CERT = 14;

        /**
        * IEEE 802.11ai FILS SK with SHA256
         * @hide
        */
        public static final int FILS_SHA256 = 15;
        /**
         * IEEE 802.11ai FILS SK with SHA384:
         * @hide
         */
        public static final int FILS_SHA384 = 16;

        public static final String varName = "key_mgmt";

        public static final String[] strings = { "NONE", "WPA_PSK", "WPA_EAP",
                "IEEE8021X", "WPA2_PSK", "OSEN", "FT_PSK", "FT_EAP",
                "SAE", "OWE", "SUITE_B_192", "WPA_PSK_SHA256", "WPA_EAP_SHA256",
                "WAPI_PSK", "WAPI_CERT", "FILS_SHA256", "FILS_SHA384" };
    }

    /**
     * Recognized security protocols.
     */
    public static class Protocol {
        private Protocol() { }

        /** WPA/IEEE 802.11i/D3.0
         * @deprecated Due to security and performance limitations, use of WPA-1 networks
         * is discouraged. WPA-2 (RSN) should be used instead. */
        @Deprecated
        public static final int WPA = 0;
        /** RSN WPA2/WPA3/IEEE 802.11i */
        public static final int RSN = 1;
        /** HS2.0 r2 OSEN
         * @hide
         */
        public static final int OSEN = 2;

        /**
         * WAPI Protocol
         */
        public static final int WAPI = 3;

        public static final String varName = "proto";

        public static final String[] strings = { "WPA", "RSN", "OSEN", "WAPI" };
    }

    /**
     * Recognized IEEE 802.11 authentication algorithms.
     */
    public static class AuthAlgorithm {
        private AuthAlgorithm() { }

        /** Open System authentication (required for WPA/WPA2) */
        public static final int OPEN = 0;
        /** Shared Key authentication (requires static WEP keys)
         * @deprecated Due to security and performance limitations, use of WEP networks
         * is discouraged. */
        @Deprecated
        public static final int SHARED = 1;
        /** LEAP/Network EAP (only used with LEAP) */
        public static final int LEAP = 2;

        /** SAE (Used only for WPA3-Personal) */
        public static final int SAE = 3;

        public static final String varName = "auth_alg";

        public static final String[] strings = { "OPEN", "SHARED", "LEAP", "SAE" };
    }

    /**
     * Recognized pairwise ciphers for WPA.
     */
    public static class PairwiseCipher {
        private PairwiseCipher() { }

        /** Use only Group keys (deprecated) */
        public static final int NONE = 0;
        /** Temporal Key Integrity Protocol [IEEE 802.11i/D7.0]
         * @deprecated Due to security and performance limitations, use of WPA-1 networks
         * is discouraged. WPA-2 (RSN) should be used instead. */
        @Deprecated
        public static final int TKIP = 1;
        /** AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0] */
        public static final int CCMP = 2;
        /**
         * AES in Galois/Counter Mode
         */
        public static final int GCMP_256 = 3;
        /**
         * SMS4 cipher for WAPI
         */
        public static final int SMS4 = 4;

        public static final String varName = "pairwise";

        public static final String[] strings = { "NONE", "TKIP", "CCMP", "GCMP_256", "SMS4" };
    }

    /**
     * Recognized group ciphers.
     * <pre>
     * CCMP = AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0]
     * TKIP = Temporal Key Integrity Protocol [IEEE 802.11i/D7.0]
     * WEP104 = WEP (Wired Equivalent Privacy) with 104-bit key
     * WEP40 = WEP (Wired Equivalent Privacy) with 40-bit key (original 802.11)
     * GCMP_256 = AES in Galois/Counter Mode
     * </pre>
     */
    public static class GroupCipher {
        private GroupCipher() { }

        /** WEP40 = WEP (Wired Equivalent Privacy) with 40-bit key (original 802.11)
         * @deprecated Due to security and performance limitations, use of WEP networks
         * is discouraged. */
        @Deprecated
        public static final int WEP40 = 0;
        /** WEP104 = WEP (Wired Equivalent Privacy) with 104-bit key
         * @deprecated Due to security and performance limitations, use of WEP networks
         * is discouraged. */
        @Deprecated
        public static final int WEP104 = 1;
        /** Temporal Key Integrity Protocol [IEEE 802.11i/D7.0] */
        public static final int TKIP = 2;
        /** AES in Counter mode with CBC-MAC [RFC 3610, IEEE 802.11i/D7.0] */
        public static final int CCMP = 3;
        /** Hotspot 2.0 r2 OSEN
         * @hide
         */
        public static final int GTK_NOT_USED = 4;
        /**
         * AES in Galois/Counter Mode
         */
        public static final int GCMP_256 = 5;
        /**
         * SMS4 cipher for WAPI
         */
        public static final int SMS4 = 6;

        public static final String varName = "group";

        public static final String[] strings =
                { /* deprecated */ "WEP40", /* deprecated */ "WEP104",
                        "TKIP", "CCMP", "GTK_NOT_USED", "GCMP_256",
                        "SMS4" };
    }

    /**
     * Recognized group management ciphers.
     * <pre>
     * BIP_CMAC_256 = Cipher-based Message Authentication Code 256 bits
     * BIP_GMAC_128 = Galois Message Authentication Code 128 bits
     * BIP_GMAC_256 = Galois Message Authentication Code 256 bits
     * </pre>
     */
    public static class GroupMgmtCipher {
        private GroupMgmtCipher() { }

        /** CMAC-256 = Cipher-based Message Authentication Code */
        public static final int BIP_CMAC_256 = 0;

        /** GMAC-128 = Galois Message Authentication Code */
        public static final int BIP_GMAC_128 = 1;

        /** GMAC-256 = Galois Message Authentication Code */
        public static final int BIP_GMAC_256 = 2;

        private static final String varName = "groupMgmt";

        private static final String[] strings = { "BIP_CMAC_256",
                "BIP_GMAC_128", "BIP_GMAC_256"};
    }

    /**
     * Recognized suiteB ciphers.
     * <pre>
     * ECDHE_ECDSA
     * ECDHE_RSA
     * </pre>
     * @hide
     */
    public static class SuiteBCipher {
        private SuiteBCipher() { }

        /** Diffie-Hellman with Elliptic Curve_ECDSA signature */
        public static final int ECDHE_ECDSA = 0;

        /** Diffie-Hellman with_RSA signature */
        public static final int ECDHE_RSA = 1;

        private static final String varName = "SuiteB";

        private static final String[] strings = { "ECDHE_ECDSA", "ECDHE_RSA" };
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

    /** Security type for an open network. */
    public static final int SECURITY_TYPE_OPEN = 0;
    /** Security type for a WEP network. */
    public static final int SECURITY_TYPE_WEP = 1;
    /** Security type for a PSK network. */
    public static final int SECURITY_TYPE_PSK = 2;
    /** Security type for an EAP network. */
    public static final int SECURITY_TYPE_EAP = 3;
    /** Security type for an SAE network. */
    public static final int SECURITY_TYPE_SAE = 4;
    /** Security type for an EAP Suite B network. */
    public static final int SECURITY_TYPE_EAP_SUITE_B = 5;
    /** Security type for an OWE network. */
    public static final int SECURITY_TYPE_OWE = 6;
    /** Security type for a WAPI PSK network. */
    public static final int SECURITY_TYPE_WAPI_PSK = 7;
    /** Security type for a WAPI Certificate network. */
    public static final int SECURITY_TYPE_WAPI_CERT = 8;

    /**
     * Security types we support.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SECURITY_TYPE_" }, value = {
            SECURITY_TYPE_OPEN,
            SECURITY_TYPE_WEP,
            SECURITY_TYPE_PSK,
            SECURITY_TYPE_EAP,
            SECURITY_TYPE_SAE,
            SECURITY_TYPE_EAP_SUITE_B,
            SECURITY_TYPE_OWE,
            SECURITY_TYPE_WAPI_PSK,
            SECURITY_TYPE_WAPI_CERT
    })
    public @interface SecurityType {}

    /**
     * Set the various security params to correspond to the provided security type.
     * This is accomplished by setting the various BitSets exposed in WifiConfiguration.
     *
     * @param securityType One of the following security types:
     * {@link #SECURITY_TYPE_OPEN},
     * {@link #SECURITY_TYPE_WEP},
     * {@link #SECURITY_TYPE_PSK},
     * {@link #SECURITY_TYPE_EAP},
     * {@link #SECURITY_TYPE_SAE},
     * {@link #SECURITY_TYPE_EAP_SUITE_B},
     * {@link #SECURITY_TYPE_OWE},
     * {@link #SECURITY_TYPE_WAPI_PSK}, or
     * {@link #SECURITY_TYPE_WAPI_CERT}
     */
    public void setSecurityParams(@SecurityType int securityType) {
        // Clear all the bitsets.
        allowedKeyManagement.clear();
        allowedProtocols.clear();
        allowedAuthAlgorithms.clear();
        allowedPairwiseCiphers.clear();
        allowedGroupCiphers.clear();
        allowedGroupManagementCiphers.clear();
        allowedSuiteBCiphers.clear();

        switch (securityType) {
            case SECURITY_TYPE_OPEN:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                break;
            case SECURITY_TYPE_WEP:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                break;
            case SECURITY_TYPE_PSK:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                break;
            case SECURITY_TYPE_EAP:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
                break;
            case SECURITY_TYPE_SAE:
                allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                requirePmf = true;
                break;
            case SECURITY_TYPE_EAP_SUITE_B:
                allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SUITE_B_192);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                allowedGroupManagementCiphers.set(WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256);
                // Note: allowedSuiteBCiphers bitset will be set by the service once the
                // certificates are attached to this profile
                requirePmf = true;
                break;
            case SECURITY_TYPE_OWE:
                allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                requirePmf = true;
                break;
            case SECURITY_TYPE_WAPI_PSK:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WAPI_PSK);
                allowedProtocols.set(WifiConfiguration.Protocol.WAPI);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.SMS4);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.SMS4);
                break;
            case SECURITY_TYPE_WAPI_CERT:
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WAPI_CERT);
                allowedProtocols.set(WifiConfiguration.Protocol.WAPI);
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.SMS4);
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.SMS4);
                break;
            default:
                throw new IllegalArgumentException("unknown security type " + securityType);
        }
    }

    /** @hide */
    public static final int UNKNOWN_UID = -1;

    /**
     * The ID number that the supplicant uses to identify this
     * network configuration entry. This must be passed as an argument
     * to most calls into the supplicant.
     */
    public int networkId;

    // Fixme We need remove this field to use only Quality network selection status only
    /**
     * The current status of this network configuration entry.
     * @see Status
     */
    public int status;

    /**
     * The network's SSID. Can either be a UTF-8 string,
     * which must be enclosed in double quotation marks
     * (e.g., {@code "MyNetwork"}), or a string of
     * hex digits, which are not enclosed in quotes
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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"AP_BAND_"}, value = {
            AP_BAND_2GHZ,
            AP_BAND_5GHZ,
            AP_BAND_ANY})
    public @interface ApBand {}

    /**
     * 2GHz band.
     * @hide
     */
    public static final int AP_BAND_2GHZ = 0;

    /**
     * 5GHz band.
     * @hide
     */
    public static final int AP_BAND_5GHZ = 1;

    /**
     * Device is allowed to choose the optimal band (2Ghz or 5Ghz) based on device capability,
     * operating country code and current radio conditions.
     * @hide
     */
    public static final int AP_BAND_ANY = -1;

    /**
     * The band which the AP resides on.
     * One of {@link #AP_BAND_2GHZ}, {@link #AP_BAND_5GHZ}, or {@link #AP_BAND_ANY}.
     * By default, {@link #AP_BAND_2GHZ} is chosen.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @ApBand
    public int apBand = AP_BAND_2GHZ;

    /**
     * The channel which AP resides on,currently, US only
     * 2G  1-11
     * 5G  36,40,44,48,149,153,157,161,165
     * 0 - find a random available channel according to the apBand
     * @hide
     */
    @UnsupportedAppUsage
    public int apChannel = 0;

    /**
     * Pre-shared key for use with WPA-PSK. Either an ASCII string enclosed in
     * double quotation marks (e.g., {@code "abcdefghij"} for PSK passphrase or
     * a string of 64 hex digits for raw PSK.
     * <p/>
     * When the value of this key is read, the actual key is
     * not returned, just a "*" if the key has a value, or the null
     * string otherwise.
     */
    public String preSharedKey;

    /**
     * Optional SAE Password Id for use with WPA3-SAE. It is an ASCII string.
     * @hide
     */
    @SystemApi
    public @Nullable String saePasswordId;

    /**
     * Four WEP keys. For each of the four values, provide either an ASCII
     * string enclosed in double quotation marks (e.g., {@code "abcdef"}),
     * a string of hex digits (e.g., {@code 0102030405}), or an empty string
     * (e.g., {@code ""}).
     * <p/>
     * When the value of one of these keys is read, the actual key is
     * not returned, just a "*" if the key has a value, or the null
     * string otherwise.
     * @deprecated Due to security and performance limitations, use of WEP networks
     * is discouraged.
     */
    @Deprecated
    public String[] wepKeys;

    /** Default WEP key index, ranging from 0 to 3.
     * @deprecated Due to security and performance limitations, use of WEP networks
     * is discouraged. */
    @Deprecated
    public int wepTxKeyIndex;

    /**
     * Priority determines the preference given to a network by {@code wpa_supplicant}
     * when choosing an access point with which to associate.
     * @deprecated This field does not exist anymore.
     */
    @Deprecated
    public int priority;

    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    public boolean hiddenSSID;

    /**
     * True if the network requires Protected Management Frames (PMF), false otherwise.
     * @hide
     */
    @SystemApi
    public boolean requirePmf;

    /**
     * Update identifier, for Passpoint network.
     * @hide
     */
    public String updateIdentifier;

    /**
     * The set of key management protocols supported by this configuration.
     * See {@link KeyMgmt} for descriptions of the values.
     * Defaults to WPA-PSK WPA-EAP.
     */
    @NonNull
    public BitSet allowedKeyManagement;
    /**
     * The set of security protocols supported by this configuration.
     * See {@link Protocol} for descriptions of the values.
     * Defaults to WPA RSN.
     */
    @NonNull
    public BitSet allowedProtocols;
    /**
     * The set of authentication protocols supported by this configuration.
     * See {@link AuthAlgorithm} for descriptions of the values.
     * Defaults to automatic selection.
     */
    @NonNull
    public BitSet allowedAuthAlgorithms;
    /**
     * The set of pairwise ciphers for WPA supported by this configuration.
     * See {@link PairwiseCipher} for descriptions of the values.
     * Defaults to CCMP TKIP.
     */
    @NonNull
    public BitSet allowedPairwiseCiphers;
    /**
     * The set of group ciphers supported by this configuration.
     * See {@link GroupCipher} for descriptions of the values.
     * Defaults to CCMP TKIP WEP104 WEP40.
     */
    @NonNull
    public BitSet allowedGroupCiphers;
    /**
     * The set of group management ciphers supported by this configuration.
     * See {@link GroupMgmtCipher} for descriptions of the values.
     */
    @NonNull
    public BitSet allowedGroupManagementCiphers;
    /**
     * The set of SuiteB ciphers supported by this configuration.
     * To be used for WPA3-Enterprise mode. Set automatically by the framework based on the
     * certificate type that is used in this configuration.
     */
    @NonNull
    public BitSet allowedSuiteBCiphers;
    /**
     * The enterprise configuration details specifying the EAP method,
     * certificates and other settings associated with the EAP.
     */
    public WifiEnterpriseConfig enterpriseConfig;

    /**
     * Fully qualified domain name of a Passpoint configuration
     */
    public String FQDN;

    /**
     * Name of Passpoint credential provider
     */
    public String providerFriendlyName;

    /**
     * Flag indicating if this network is provided by a home Passpoint provider or a roaming
     * Passpoint provider.  This flag will be {@code true} if this network is provided by
     * a home Passpoint provider and {@code false} if is provided by a roaming Passpoint provider
     * or is a non-Passpoint network.
     */
    public boolean isHomeProviderNetwork;

    /**
     * Roaming Consortium Id list for Passpoint credential; identifies a set of networks where
     * Passpoint credential will be considered valid
     */
    public long[] roamingConsortiumIds;

    /**
     * True if this network configuration is visible to and usable by other users on the
     * same device, false otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean shared;

    /**
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public String defaultGwMacAddress;

    /**
     * @hide
     * last time we connected, this configuration had validated internet access
     */
    @UnsupportedAppUsage
    public boolean validatedInternetAccess;

    /**
     * @hide
     * The number of beacon intervals between Delivery Traffic Indication Maps (DTIM)
     * This value is populated from scan results that contain Beacon Frames, which are infrequent.
     * The value is not guaranteed to be set or current (Although it SHOULDNT change once set)
     * Valid values are from 1 - 255. Initialized here as 0, use this to check if set.
     */
    public int dtimInterval = 0;

    /**
     * Flag indicating if this configuration represents a legacy Passpoint configuration
     * (Release N or older).  This is used for migrating Passpoint configuration from N to O.
     * This will no longer be needed after O.
     * @hide
     */
    public boolean isLegacyPasspointConfig = false;
    /**
     * @hide
     * Uid of app creating the configuration
     */
    @SystemApi
    public int creatorUid;

    /**
     * @hide
     * Uid of last app issuing a connection related command
     */
    @UnsupportedAppUsage
    public int lastConnectUid;

    /**
     * @hide
     * Uid of last app modifying the configuration
     */
    @SystemApi
    public int lastUpdateUid;

    /**
     * @hide
     * Universal name for app creating the configuration
     *    see {@link PackageManager#getNameForUid(int)}
     */
    @SystemApi
    public String creatorName;

    /**
     * @hide
     * Universal name for app updating the configuration
     *    see {@link PackageManager#getNameForUid(int)}
     */
    @SystemApi
    public String lastUpdateName;

    /**
     * The carrier ID identifies the operator who provides this network configuration.
     *    see {@link TelephonyManager#getSimCarrierId()}
     * @hide
     */
    @SystemApi
    public int carrierId = TelephonyManager.UNKNOWN_CARRIER_ID;

    /**
     * @hide
     * Auto-join is allowed by user for this network.
     * Default true.
     */
    @SystemApi
    public boolean allowAutojoin = true;

    /** @hide **/
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static int INVALID_RSSI = -127;

    /**
     * @hide
     * Number of reports indicating no Internet Access
     */
    @UnsupportedAppUsage
    public int numNoInternetAccessReports;

    /**
     * @hide
     * The WiFi configuration is considered to have no internet access for purpose of autojoining
     * if there has been a report of it having no internet access, and, it never have had
     * internet access in the past.
     */
    @SystemApi
    public boolean hasNoInternetAccess() {
        return numNoInternetAccessReports > 0 && !validatedInternetAccess;
    }

    /**
     * The WiFi configuration is expected not to have Internet access (e.g., a wireless printer, a
     * Chromecast hotspot, etc.). This will be set if the user explicitly confirms a connection to
     * this configuration and selects "don't ask again".
     * @hide
     */
    @UnsupportedAppUsage
    public boolean noInternetAccessExpected;

    /**
     * The WiFi configuration is expected not to have Internet access (e.g., a wireless printer, a
     * Chromecast hotspot, etc.). This will be set if the user explicitly confirms a connection to
     * this configuration and selects "don't ask again".
     * @hide
     */
    @SystemApi
    public boolean isNoInternetAccessExpected() {
        return noInternetAccessExpected;
    }

    /**
     * This Wifi configuration is expected for OSU(Online Sign Up) of Passpoint Release 2.
     * @hide
     */
    public boolean osu;

    /**
     * @hide
     * Last time the system was connected to this configuration.
     */
    public long lastConnected;

    /**
     * @hide
     * Last time the system was disconnected to this configuration.
     */
    public long lastDisconnected;

    /**
     * Set if the configuration was self added by the framework
     * This boolean is cleared if we get a connect/save/ update or
     * any wifiManager command that indicate the user interacted with the configuration
     * since we will now consider that the configuration belong to him.
     * @deprecated only kept for @UnsupportedAppUsage
     * @hide
     */
    @UnsupportedAppUsage
    public boolean selfAdded;

    /**
     * Peer WifiConfiguration this WifiConfiguration was added for
     * @hide
     */
    public String peerWifiConfiguration;

    /**
     * @hide
     * Indicate that a WifiConfiguration is temporary and should not be saved
     * nor considered by AutoJoin.
     */
    public boolean ephemeral;

    /**
     * @hide
     * Indicate that a WifiConfiguration is temporary and should not be saved
     * nor considered by AutoJoin.
     */
    @SystemApi
    public boolean isEphemeral() {
      return ephemeral;
    }

    /**
     * Indicate whether the network is trusted or not. Networks are considered trusted
     * if the user explicitly allowed this network connection.
     * This bit can be used by suggestion network, see
     * {@link WifiNetworkSuggestion.Builder#setUntrusted(boolean)}
     * @hide
     */
    public boolean trusted;

    /**
     * True if this Wifi configuration is created from a {@link WifiNetworkSuggestion},
     * false otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean fromWifiNetworkSuggestion;

    /**
     * True if this Wifi configuration is created from a {@link WifiNetworkSpecifier},
     * false otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean fromWifiNetworkSpecifier;

    /**
     * True if the creator of this configuration has expressed that it
     * should be considered metered, false otherwise.
     *
     * @see #isMetered(WifiConfiguration, WifiInfo)
     *
     * @hide
     */
    @SystemApi
    public boolean meteredHint;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"METERED_OVERRIDE_"}, value = {
            METERED_OVERRIDE_NONE,
            METERED_OVERRIDE_METERED,
            METERED_OVERRIDE_NOT_METERED})
    public @interface MeteredOverride {}

    /**
     * No metered override.
     * @hide
     */
    @SystemApi
    public static final int METERED_OVERRIDE_NONE = 0;
    /**
     * Override network to be metered.
     * @hide
     */
    @SystemApi
    public static final int METERED_OVERRIDE_METERED = 1;
    /**
     * Override network to be unmetered.
     * @hide
     */
    @SystemApi
    public static final int METERED_OVERRIDE_NOT_METERED = 2;

    /**
     * Indicates if the end user has expressed an explicit opinion about the
     * meteredness of this network, such as through the Settings app.
     * This value is one of {@link #METERED_OVERRIDE_NONE}, {@link #METERED_OVERRIDE_METERED},
     * or {@link #METERED_OVERRIDE_NOT_METERED}.
     * <p>
     * This should always override any values from {@link #meteredHint} or
     * {@link WifiInfo#getMeteredHint()}.
     *
     * By default this field is set to {@link #METERED_OVERRIDE_NONE}.
     *
     * @see #isMetered(WifiConfiguration, WifiInfo)
     * @hide
     */
    @SystemApi
    @MeteredOverride
    public int meteredOverride = METERED_OVERRIDE_NONE;

    /**
     * Blend together all the various opinions to decide if the given network
     * should be considered metered or not.
     *
     * @hide
     */
    @SystemApi
    public static boolean isMetered(@Nullable WifiConfiguration config, @Nullable WifiInfo info) {
        boolean metered = false;
        if (info != null && info.getMeteredHint()) {
            metered = true;
        }
        if (config != null && config.meteredHint) {
            metered = true;
        }
        if (config != null
                && config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
            metered = true;
        }
        if (config != null
                && config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
            metered = false;
        }
        return metered;
    }

    /**
     * @hide
     * Returns true if this WiFi config is for an open network.
     */
    public boolean isOpenNetwork() {
        final int cardinality = allowedKeyManagement.cardinality();
        final boolean hasNoKeyMgmt = cardinality == 0
                || (cardinality == 1 && (allowedKeyManagement.get(KeyMgmt.NONE)
                || allowedKeyManagement.get(KeyMgmt.OWE)));

        boolean hasNoWepKeys = true;
        if (wepKeys != null) {
            for (int i = 0; i < wepKeys.length; i++) {
                if (wepKeys[i] != null) {
                    hasNoWepKeys = false;
                    break;
                }
            }
        }

        return hasNoKeyMgmt && hasNoWepKeys;
    }

    /**
     * @hide
     * Setting this value will force scan results associated with this configuration to
     * be included in the bucket of networks that are externally scored.
     * If not set, associated scan results will be treated as legacy saved networks and
     * will take precedence over networks in the scored category.
     */
    @SystemApi
    public boolean useExternalScores;

    /**
     * @hide
     * Number of time the scorer overrode a the priority based choice, when comparing two
     * WifiConfigurations, note that since comparing WifiConfiguration happens very often
     * potentially at every scan, this number might become very large, even on an idle
     * system.
     */
    @SystemApi
    public int numScorerOverride;

    /**
     * @hide
     * Number of time the scorer overrode a the priority based choice, and the comparison
     * triggered a network switch
     */
    @SystemApi
    public int numScorerOverrideAndSwitchedNetwork;

    /**
     * @hide
     * Number of time we associated to this configuration.
     */
    @SystemApi
    public int numAssociation;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RANDOMIZATION_"}, value = {
            RANDOMIZATION_NONE,
            RANDOMIZATION_PERSISTENT})
    public @interface MacRandomizationSetting {}

    /**
     * Use factory MAC when connecting to this network
     * @hide
     */
    @SystemApi
    public static final int RANDOMIZATION_NONE = 0;
    /**
     * Generate a randomized MAC once and reuse it for all connections to this network
     * @hide
     */
    @SystemApi
    public static final int RANDOMIZATION_PERSISTENT = 1;

    /**
     * Level of MAC randomization for this network.
     * One of {@link #RANDOMIZATION_NONE} or {@link #RANDOMIZATION_PERSISTENT}.
     * By default this field is set to {@link #RANDOMIZATION_PERSISTENT}.
     * @hide
     */
    @SystemApi
    @MacRandomizationSetting
    public int macRandomizationSetting = RANDOMIZATION_PERSISTENT;

    /**
     * @hide
     * Randomized MAC address to use with this particular network
     */
    @NonNull
    private MacAddress mRandomizedMacAddress;

    /**
     * @hide
     * The wall clock time of when |mRandomizedMacAddress| should be re-randomized in aggressive
     * randomization mode.
     */
    public long randomizedMacExpirationTimeMs = 0;

    /**
     * @hide
     * Checks if the given MAC address can be used for Connected Mac Randomization
     * by verifying that it is non-null, unicast, locally assigned, and not default mac.
     * @param mac MacAddress to check
     * @return true if mac is good to use
     */
    public static boolean isValidMacAddressForRandomization(MacAddress mac) {
        return mac != null && !MacAddressUtils.isMulticastAddress(mac) && mac.isLocallyAssigned()
                && !MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS).equals(mac);
    }

    /**
     * Returns MAC address set to be the local randomized MAC address.
     * Depending on user preference, the device may or may not use the returned MAC address for
     * connections to this network.
     * <p>
     * Information is restricted to Device Owner, Profile Owner, and Carrier apps
     * (which will only obtain addresses for configurations which they create). Other callers
     * will receive a default "02:00:00:00:00:00" MAC address.
     */
    public @NonNull MacAddress getRandomizedMacAddress() {
        return mRandomizedMacAddress;
    }

    /**
     * @hide
     * @param mac MacAddress to change into
     */
    public void setRandomizedMacAddress(@NonNull MacAddress mac) {
        if (mac == null) {
            Log.e(TAG, "setRandomizedMacAddress received null MacAddress.");
            return;
        }
        mRandomizedMacAddress = mac;
    }

    /** @hide
     * Boost given to RSSI on a home network for the purpose of calculating the score
     * This adds stickiness to home networks, as defined by:
     * - less than 4 known BSSIDs
     * - PSK only
     * - TODO: add a test to verify that all BSSIDs are behind same gateway
     ***/
    public static final int HOME_NETWORK_RSSI_BOOST = 5;

    /**
     * This class is used to contain all the information and API used for quality network selection.
     * @hide
     */
    @SystemApi
    public static class NetworkSelectionStatus {
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = "NETWORK_SELECTION_",
                value = {
                NETWORK_SELECTION_ENABLED,
                NETWORK_SELECTION_TEMPORARY_DISABLED,
                NETWORK_SELECTION_PERMANENTLY_DISABLED})
        public @interface NetworkEnabledStatus {}
        /**
         * This network will be considered as a potential candidate to connect to during network
         * selection.
         */
        public static final int NETWORK_SELECTION_ENABLED = 0;
        /**
         * This network was temporary disabled. May be re-enabled after a time out.
         */
        public static final int NETWORK_SELECTION_TEMPORARY_DISABLED = 1;
        /**
         * This network was permanently disabled.
         */
        public static final int NETWORK_SELECTION_PERMANENTLY_DISABLED = 2;
        /**
         * Maximum Network selection status
         * @hide
         */
        public static final int NETWORK_SELECTION_STATUS_MAX = 3;

        /**
         * Quality network selection status String (for debug purpose). Use Quality network
         * selection status value as index to extec the corresponding debug string
         * @hide
         */
        public static final String[] QUALITY_NETWORK_SELECTION_STATUS = {
                "NETWORK_SELECTION_ENABLED",
                "NETWORK_SELECTION_TEMPORARY_DISABLED",
                "NETWORK_SELECTION_PERMANENTLY_DISABLED"};

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = "DISABLED_", value = {
                DISABLED_NONE,
                DISABLED_ASSOCIATION_REJECTION,
                DISABLED_AUTHENTICATION_FAILURE,
                DISABLED_DHCP_FAILURE,
                DISABLED_NO_INTERNET_TEMPORARY,
                DISABLED_AUTHENTICATION_NO_CREDENTIALS,
                DISABLED_NO_INTERNET_PERMANENT,
                DISABLED_BY_WIFI_MANAGER,
                DISABLED_BY_WRONG_PASSWORD,
                DISABLED_AUTHENTICATION_NO_SUBSCRIPTION})
        public @interface NetworkSelectionDisableReason {}

        // Quality Network disabled reasons
        /** Default value. Means not disabled. */
        public static final int DISABLED_NONE = 0;
        /**
         * The starting index for network selection disabled reasons.
         * @hide
         */
        public static final int NETWORK_SELECTION_DISABLED_STARTING_INDEX = 1;
        /**
         * The starting index for network selection temporarily disabled reasons.
         * @hide
         */
        public static final int TEMPORARILY_DISABLED_STARTING_INDEX = 1;
        /** This network is disabled because of multiple association rejections. */
        public static final int DISABLED_ASSOCIATION_REJECTION = 1;
        /** This network is disabled because of multiple authentication failure. */
        public static final int DISABLED_AUTHENTICATION_FAILURE = 2;
        /** This network is disabled because of multiple DHCP failure. */
        public static final int DISABLED_DHCP_FAILURE = 3;
        /** This network is temporarily disabled because it has no Internet access. */
        public static final int DISABLED_NO_INTERNET_TEMPORARY = 4;
        /**
         * The starting index for network selection permanently disabled reasons.
         * @hide
         */
        public static final int PERMANENTLY_DISABLED_STARTING_INDEX = 5;
        /** This network is disabled due to absence of user credentials */
        public static final int DISABLED_AUTHENTICATION_NO_CREDENTIALS = 5;
        /**
         * This network is permanently disabled because it has no Internet access and the user does
         * not want to stay connected.
         */
        public static final int DISABLED_NO_INTERNET_PERMANENT = 6;
        /** This network is disabled due to WifiManager disabling it explicitly. */
        public static final int DISABLED_BY_WIFI_MANAGER = 7;
        /** This network is disabled due to wrong password. */
        public static final int DISABLED_BY_WRONG_PASSWORD = 8;
        /** This network is disabled because service is not subscribed. */
        public static final int DISABLED_AUTHENTICATION_NO_SUBSCRIPTION = 9;
        /**
         * All other disable reasons should be strictly less than this value.
         * @hide
         */
        public static final int NETWORK_SELECTION_DISABLED_MAX = 10;

        /**
         * Get an integer that is equal to the maximum integer value of all the
         * DISABLED_* reasons
         * e.g. {@link #DISABLED_NONE}, {@link #DISABLED_ASSOCIATION_REJECTION}, etc.
         *
         * All DISABLED_* constants will be contiguous in the range
         * 0, 1, 2, 3, ..., getMaxNetworkSelectionDisableReasons()
         *
         * <br />
         * For example, this can be used to iterate through all the network selection
         * disable reasons like so:
         * <pre>{@code
         * for (int reason = 0; reason <= getMaxNetworkSelectionDisableReasons(); reason++) {
         *     ...
         * }
         * }</pre>
         */
        public static int getMaxNetworkSelectionDisableReason() {
            return NETWORK_SELECTION_DISABLED_MAX - 1;
        }

        /**
         * Contains info about disable reasons.
         * @hide
         */
        public static final class DisableReasonInfo {
            /**
             * String representation for the disable reason.
             * Note that these strings are persisted in
             * {@link
             * com.android.server.wifi.util.XmlUtil.NetworkSelectionStatusXmlUtil#writeToXml},
             * so do not change the string values to maintain backwards compatibility.
             */
            public final String mReasonStr;
            /**
             * Network Selection disable reason threshold, used to debounce network failures before
             * we disable them.
             */
            public final int mDisableThreshold;
            /**
             * Network Selection disable timeout for the error. After the timeout milliseconds,
             * enable the network again.
             */
            public final int mDisableTimeoutMillis;

            /**
             * Constructor
             * @param reasonStr string representation of the error
             * @param disableThreshold number of failures before we disable the network
             * @param disableTimeoutMillis the timeout, in milliseconds, before we re-enable the
             *                             network after disabling it
             */
            public DisableReasonInfo(String reasonStr, int disableThreshold,
                    int disableTimeoutMillis) {
                mReasonStr = reasonStr;
                mDisableThreshold = disableThreshold;
                mDisableTimeoutMillis = disableTimeoutMillis;
            }
        }

        /**
         * Quality network selection disable reason infos.
         * @hide
         */
        public static final SparseArray<DisableReasonInfo> DISABLE_REASON_INFOS =
                buildDisableReasonInfos();

        private static SparseArray<DisableReasonInfo> buildDisableReasonInfos() {
            SparseArray<DisableReasonInfo> reasons = new SparseArray<>();

            reasons.append(DISABLED_NONE,
                    new DisableReasonInfo(
                            // Note that these strings are persisted in
                            // XmlUtil.NetworkSelectionStatusXmlUtil#writeToXml,
                            // so do not change the string values to maintain backwards
                            // compatibility.
                            "NETWORK_SELECTION_ENABLE",
                            -1,
                            Integer.MAX_VALUE));

            reasons.append(DISABLED_ASSOCIATION_REJECTION,
                    new DisableReasonInfo(
                            // Note that there is a space at the end of this string. Cannot fix
                            // since this string is persisted.
                            "NETWORK_SELECTION_DISABLED_ASSOCIATION_REJECTION ",
                            5,
                            5 * 60 * 1000));

            reasons.append(DISABLED_AUTHENTICATION_FAILURE,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_AUTHENTICATION_FAILURE",
                            5,
                            5 * 60 * 1000));

            reasons.append(DISABLED_DHCP_FAILURE,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_DHCP_FAILURE",
                            5,
                            5 * 60 * 1000));

            reasons.append(DISABLED_NO_INTERNET_TEMPORARY,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_NO_INTERNET_TEMPORARY",
                            1,
                            10 * 60 * 1000));

            reasons.append(DISABLED_AUTHENTICATION_NO_CREDENTIALS,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_AUTHENTICATION_NO_CREDENTIALS",
                            1,
                            Integer.MAX_VALUE));

            reasons.append(DISABLED_NO_INTERNET_PERMANENT,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_NO_INTERNET_PERMANENT",
                            1,
                            Integer.MAX_VALUE));

            reasons.append(DISABLED_BY_WIFI_MANAGER,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_BY_WIFI_MANAGER",
                            1,
                            Integer.MAX_VALUE));

            reasons.append(DISABLED_BY_WRONG_PASSWORD,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD",
                            1,
                            Integer.MAX_VALUE));

            reasons.append(DISABLED_AUTHENTICATION_NO_SUBSCRIPTION,
                    new DisableReasonInfo(
                            "NETWORK_SELECTION_DISABLED_AUTHENTICATION_NO_SUBSCRIPTION",
                            1,
                            Integer.MAX_VALUE));

            return reasons;
        }

        /**
         * Get the {@link NetworkSelectionDisableReason} int code by its string value.
         * @return the NetworkSelectionDisableReason int code corresponding to the reason string,
         * or -1 if the reason string is unrecognized.
         * @hide
         */
        @NetworkSelectionDisableReason
        public static int getDisableReasonByString(@NonNull String reasonString) {
            for (int i = 0; i < DISABLE_REASON_INFOS.size(); i++) {
                int key = DISABLE_REASON_INFOS.keyAt(i);
                DisableReasonInfo value = DISABLE_REASON_INFOS.valueAt(i);
                if (value != null && TextUtils.equals(reasonString, value.mReasonStr)) {
                    return key;
                }
            }
            Log.e(TAG, "Unrecognized network disable reason: " + reasonString);
            return -1;
        }

        /**
         * Invalid time stamp for network selection disable
         * @hide
         */
        public static final long INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP = -1L;

        /**
         * This constant indicates the current configuration has connect choice set
         */
        private static final int CONNECT_CHOICE_EXISTS = 1;

        /**
         * This constant indicates the current configuration does not have connect choice set
         */
        private static final int CONNECT_CHOICE_NOT_EXISTS = -1;

        // fields for QualityNetwork Selection
        /**
         * Network selection status, should be in one of three status: enable, temporaily disabled
         * or permanently disabled
         */
        @NetworkEnabledStatus
        private int mStatus;

        /**
         * Reason for disable this network
         */
        @NetworkSelectionDisableReason
        private int mNetworkSelectionDisableReason;

        /**
         * Last time we temporarily disabled the configuration
         */
        private long mTemporarilyDisabledTimestamp = INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP;

        /**
         * counter for each Network selection disable reason
         */
        private int[] mNetworkSeclectionDisableCounter = new int[NETWORK_SELECTION_DISABLED_MAX];

        /**
         * Connect Choice over this configuration
         *
         * When current wifi configuration is visible to the user but user explicitly choose to
         * connect to another network X, the another networks X's configure key will be stored here.
         * We will consider user has a preference of X over this network. And in the future,
         * network selection will always give X a higher preference over this configuration.
         * configKey is : "SSID"-WEP-WPA_PSK-WPA_EAP
         */
        private String mConnectChoice;

        /**
         * Used to cache the temporary candidate during the network selection procedure. It will be
         * kept updating once a new scan result has a higher score than current one
         */
        private ScanResult mCandidate;

        /**
         * Used to cache the score of the current temporary candidate during the network
         * selection procedure.
         */
        private int mCandidateScore;

        /**
         * Indicate whether this network is visible in latest Qualified Network Selection. This
         * means there is scan result found related to this Configuration and meet the minimum
         * requirement. The saved network need not join latest Qualified Network Selection. For
         * example, it is disabled. True means network is visible in latest Qualified Network
         * Selection and false means network is invisible
         */
        private boolean mSeenInLastQualifiedNetworkSelection;

        /**
         * Boolean indicating if we have ever successfully connected to this network.
         *
         * This value will be set to true upon a successful connection.
         * This value will be set to false if a previous value was not stored in the config or if
         * the credentials are updated (ex. a password change).
         */
        private boolean mHasEverConnected;

        /**
         * set whether this network is visible in latest Qualified Network Selection
         * @param seen value set to candidate
         * @hide
         */
        public void setSeenInLastQualifiedNetworkSelection(boolean seen) {
            mSeenInLastQualifiedNetworkSelection =  seen;
        }

        /**
         * get whether this network is visible in latest Qualified Network Selection
         * @return returns true -- network is visible in latest Qualified Network Selection
         *         false -- network is invisible in latest Qualified Network Selection
         * @hide
         */
        public boolean getSeenInLastQualifiedNetworkSelection() {
            return mSeenInLastQualifiedNetworkSelection;
        }
        /**
         * set the temporary candidate of current network selection procedure
         * @param scanCandidate {@link ScanResult} the candidate set to mCandidate
         * @hide
         */
        public void setCandidate(ScanResult scanCandidate) {
            mCandidate = scanCandidate;
        }

        /**
         * get the temporary candidate of current network selection procedure
         * @return  returns {@link ScanResult} temporary candidate of current network selection
         * procedure
         * @hide
         */
        public ScanResult getCandidate() {
            return mCandidate;
        }

        /**
         * set the score of the temporary candidate of current network selection procedure
         * @param score value set to mCandidateScore
         * @hide
         */
        public void setCandidateScore(int score) {
            mCandidateScore = score;
        }

        /**
         * get the score of the temporary candidate of current network selection procedure
         * @return returns score of the temporary candidate of current network selection procedure
         * @hide
         */
        public int getCandidateScore() {
            return mCandidateScore;
        }

        /**
         * get user preferred choice over this configuration
         * @return returns configKey of user preferred choice over this configuration
         * @hide
         */
        public String getConnectChoice() {
            return mConnectChoice;
        }

        /**
         * set user preferred choice over this configuration
         * @param newConnectChoice, the configKey of user preferred choice over this configuration
         * @hide
         */
        public void setConnectChoice(String newConnectChoice) {
            mConnectChoice = newConnectChoice;
        }

        /** Get the current Quality network selection status as a String (for debugging). */
        @NonNull
        public String getNetworkStatusString() {
            return QUALITY_NETWORK_SELECTION_STATUS[mStatus];
        }

        /** @hide */
        public void setHasEverConnected(boolean value) {
            mHasEverConnected = value;
        }

        /** True if the device has ever connected to this network, false otherwise. */
        public boolean hasEverConnected() {
            return mHasEverConnected;
        }

        /** @hide */
        public NetworkSelectionStatus() {
            // previously stored configs will not have this parameter, so we default to false.
            mHasEverConnected = false;
        }

        /**
         * NetworkSelectionStatus exports an immutable public API.
         * However, test code has a need to construct a NetworkSelectionStatus in a specific state.
         * (Note that mocking using Mockito does not work if the object needs to be parceled and
         * unparceled.)
         * Export a @SystemApi Builder to allow tests to construct a NetworkSelectionStatus object
         * in the desired state, without sacrificing NetworkSelectionStatus's immutability.
         */
        @VisibleForTesting
        public static final class Builder {
            private final NetworkSelectionStatus mNetworkSelectionStatus =
                    new NetworkSelectionStatus();

            /**
             * Set the current network selection status.
             * One of:
             * {@link #NETWORK_SELECTION_ENABLED},
             * {@link #NETWORK_SELECTION_TEMPORARY_DISABLED},
             * {@link #NETWORK_SELECTION_PERMANENTLY_DISABLED}
             * @see NetworkSelectionStatus#getNetworkSelectionStatus()
             */
            @NonNull
            public Builder setNetworkSelectionStatus(@NetworkEnabledStatus int status) {
                mNetworkSelectionStatus.setNetworkSelectionStatus(status);
                return this;
            }

            /**
             *
             * Set the current network's disable reason.
             * One of the {@link #DISABLED_NONE} or DISABLED_* constants.
             * e.g. {@link #DISABLED_ASSOCIATION_REJECTION}.
             * @see NetworkSelectionStatus#getNetworkSelectionDisableReason()
             */
            @NonNull
            public Builder setNetworkSelectionDisableReason(
                    @NetworkSelectionDisableReason int reason) {
                mNetworkSelectionStatus.setNetworkSelectionDisableReason(reason);
                return this;
            }

            /**
             * Build a NetworkSelectionStatus object.
             */
            @NonNull
            public NetworkSelectionStatus build() {
                NetworkSelectionStatus status = new NetworkSelectionStatus();
                status.copy(mNetworkSelectionStatus);
                return status;
            }
        }

        /**
         * Get the network disable reason string for a reason code (for debugging).
         * @param reason specific error reason. One of the {@link #DISABLED_NONE} or
         *               DISABLED_* constants e.g. {@link #DISABLED_ASSOCIATION_REJECTION}.
         * @return network disable reason string, or null if the reason is invalid.
         */
        @Nullable
        public static String getNetworkSelectionDisableReasonString(
                @NetworkSelectionDisableReason int reason) {
            DisableReasonInfo info = DISABLE_REASON_INFOS.get(reason);
            if (info == null) {
                return null;
            } else {
                return info.mReasonStr;
            }
        }
        /**
         * get current network disable reason
         * @return current network disable reason in String (for debug purpose)
         * @hide
         */
        public String getNetworkSelectionDisableReasonString() {
            return getNetworkSelectionDisableReasonString(mNetworkSelectionDisableReason);
        }

        /**
         * Get the current network network selection status.
         * One of:
         * {@link #NETWORK_SELECTION_ENABLED},
         * {@link #NETWORK_SELECTION_TEMPORARY_DISABLED},
         * {@link #NETWORK_SELECTION_PERMANENTLY_DISABLED}
         */
        @NetworkEnabledStatus
        public int getNetworkSelectionStatus() {
            return mStatus;
        }

        /**
         * True if the current network is enabled to join network selection, false otherwise.
         * @hide
         */
        public boolean isNetworkEnabled() {
            return mStatus == NETWORK_SELECTION_ENABLED;
        }

        /**
         * @return whether current network is temporary disabled
         * @hide
         */
        public boolean isNetworkTemporaryDisabled() {
            return mStatus == NETWORK_SELECTION_TEMPORARY_DISABLED;
        }

        /**
         * True if the current network is permanently disabled, false otherwise.
         * @hide
         */
        public boolean isNetworkPermanentlyDisabled() {
            return mStatus == NETWORK_SELECTION_PERMANENTLY_DISABLED;
        }

        /**
         * set current network selection status
         * @param status network selection status to set
         * @hide
         */
        public void setNetworkSelectionStatus(int status) {
            if (status >= 0 && status < NETWORK_SELECTION_STATUS_MAX) {
                mStatus = status;
            }
        }

        /**
         * Returns the current network's disable reason.
         * One of the {@link #DISABLED_NONE} or DISABLED_* constants
         * e.g. {@link #DISABLED_ASSOCIATION_REJECTION}.
         */
        @NetworkSelectionDisableReason
        public int getNetworkSelectionDisableReason() {
            return mNetworkSelectionDisableReason;
        }

        /**
         * set Network disable reason
         * @param reason Network disable reason
         * @hide
         */
        public void setNetworkSelectionDisableReason(@NetworkSelectionDisableReason int reason) {
            if (reason >= 0 && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSelectionDisableReason = reason;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * @param timeStamp Set when current network is disabled in millisecond since January 1,
         * 1970 00:00:00.0 UTC
         * @hide
         */
        public void setDisableTime(long timeStamp) {
            mTemporarilyDisabledTimestamp = timeStamp;
        }

        /**
         * Returns when the current network was disabled, in milliseconds since January 1,
         * 1970 00:00:00.0 UTC.
         */
        public long getDisableTime() {
            return mTemporarilyDisabledTimestamp;
        }

        /**
         * Get the disable counter of a specific reason.
         * @param reason specific failure reason. One of the {@link #DISABLED_NONE} or
         *              DISABLED_* constants e.g. {@link #DISABLED_ASSOCIATION_REJECTION}.
         * @exception IllegalArgumentException for invalid reason
         * @return counter number for specific error reason.
         */
        public int getDisableReasonCounter(@NetworkSelectionDisableReason int reason) {
            if (reason >= DISABLED_NONE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                return mNetworkSeclectionDisableCounter[reason];
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * set the counter of a specific failure reason
         * @param reason reason for disable error
         * @param value the counter value for this specific reason
         * @exception throw IllegalArgumentException for illegal input
         * @hide
         */
        public void setDisableReasonCounter(int reason, int value) {
            if (reason >= DISABLED_NONE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason] = value;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * increment the counter of a specific failure reason
         * @param reason a specific failure reason
         * @exception throw IllegalArgumentException for illegal input
         * @hide
         */
        public void incrementDisableReasonCounter(int reason) {
            if (reason >= DISABLED_NONE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason]++;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * clear the counter of a specific failure reason
         * @param reason a specific failure reason
         * @exception throw IllegalArgumentException for illegal input
         * @hide
         */
        public void clearDisableReasonCounter(int reason) {
            if (reason >= DISABLED_NONE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason] = DISABLED_NONE;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * clear all the failure reason counters
         * @hide
         */
        public void clearDisableReasonCounter() {
            Arrays.fill(mNetworkSeclectionDisableCounter, DISABLED_NONE);
        }

        /**
         * BSSID for connection to this network (through network selection procedure)
         */
        private String mNetworkSelectionBSSID;

        /**
         * get current network Selection BSSID
         * @return current network Selection BSSID
         * @hide
         */
        public String getNetworkSelectionBSSID() {
            return mNetworkSelectionBSSID;
        }

        /**
         * set network Selection BSSID
         * @param bssid The target BSSID for assocaition
         * @hide
         */
        public void setNetworkSelectionBSSID(String bssid) {
            mNetworkSelectionBSSID = bssid;
        }

        /** @hide */
        public void copy(NetworkSelectionStatus source) {
            mStatus = source.mStatus;
            mNetworkSelectionDisableReason = source.mNetworkSelectionDisableReason;
            for (int index = DISABLED_NONE; index < NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                mNetworkSeclectionDisableCounter[index] =
                        source.mNetworkSeclectionDisableCounter[index];
            }
            mTemporarilyDisabledTimestamp = source.mTemporarilyDisabledTimestamp;
            mNetworkSelectionBSSID = source.mNetworkSelectionBSSID;
            setSeenInLastQualifiedNetworkSelection(source.getSeenInLastQualifiedNetworkSelection());
            setCandidate(source.getCandidate());
            setCandidateScore(source.getCandidateScore());
            setConnectChoice(source.getConnectChoice());
            setHasEverConnected(source.hasEverConnected());
        }

        /** @hide */
        public void writeToParcel(Parcel dest) {
            dest.writeInt(getNetworkSelectionStatus());
            dest.writeInt(getNetworkSelectionDisableReason());
            for (int index = DISABLED_NONE; index < NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                dest.writeInt(getDisableReasonCounter(index));
            }
            dest.writeLong(getDisableTime());
            dest.writeString(getNetworkSelectionBSSID());
            if (getConnectChoice() != null) {
                dest.writeInt(CONNECT_CHOICE_EXISTS);
                dest.writeString(getConnectChoice());
            } else {
                dest.writeInt(CONNECT_CHOICE_NOT_EXISTS);
            }
            dest.writeInt(hasEverConnected() ? 1 : 0);
        }

        /** @hide */
        public void readFromParcel(Parcel in) {
            setNetworkSelectionStatus(in.readInt());
            setNetworkSelectionDisableReason(in.readInt());
            for (int index = DISABLED_NONE; index < NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                setDisableReasonCounter(index, in.readInt());
            }
            setDisableTime(in.readLong());
            setNetworkSelectionBSSID(in.readString());
            if (in.readInt() == CONNECT_CHOICE_EXISTS) {
                setConnectChoice(in.readString());
            } else {
                setConnectChoice(null);
            }
            setHasEverConnected(in.readInt() != 0);
        }
    }

    /**
     * @hide
     * network selection related member
     */
    private NetworkSelectionStatus mNetworkSelectionStatus = new NetworkSelectionStatus();

    /**
     * This class is intended to store extra failure reason information for the most recent
     * connection attempt, so that it may be surfaced to the settings UI
     * @hide
     */
    // TODO(b/148626966): called by SUW via reflection, remove once SUW is updated
    public static class RecentFailure {

        private RecentFailure() {}

        /**
         * Association Rejection Status code (NONE for success/non-association-rejection-fail)
         */
        @RecentFailureReason
        private int mAssociationStatus = RECENT_FAILURE_NONE;

        /**
         * @param status the association status code for the recent failure
         */
        public void setAssociationStatus(@RecentFailureReason int status) {
            mAssociationStatus = status;
        }
        /**
         * Sets the RecentFailure to NONE
         */
        public void clear() {
            mAssociationStatus = RECENT_FAILURE_NONE;
        }
        /**
         * Get the recent failure code. One of {@link #RECENT_FAILURE_NONE} or
         * {@link #RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA}.
         */
        @RecentFailureReason
        public int getAssociationStatus() {
            return mAssociationStatus;
        }
    }

    /**
     * RecentFailure member
     * @hide
     */
    // TODO(b/148626966): called by SUW via reflection, once SUW is updated, make private and
    //  rename to mRecentFailure
    @NonNull
    public final RecentFailure recentFailure = new RecentFailure();

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "RECENT_FAILURE_", value = {
            RECENT_FAILURE_NONE,
            RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA})
    public @interface RecentFailureReason {}

    /**
     * No recent failure, or no specific reason given for the recent connection failure
     * @hide
     */
    @SystemApi
    public static final int RECENT_FAILURE_NONE = 0;
    /**
     * Connection to this network recently failed due to Association Rejection Status 17
     * (AP is full)
     * @hide
     */
    @SystemApi
    public static final int RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA = 17;

    /**
     * Get the failure reason for the most recent connection attempt, or
     * {@link #RECENT_FAILURE_NONE} if there was no failure.
     *
     * Failure reasons include:
     * {@link #RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA}
     *
     * @hide
     */
    @RecentFailureReason
    @SystemApi
    public int getRecentFailureReason() {
        return recentFailure.getAssociationStatus();
    }

    /**
     * Get the network selection status.
     * @hide
     */
    @NonNull
    @SystemApi
    public NetworkSelectionStatus getNetworkSelectionStatus() {
        return mNetworkSelectionStatus;
    }

    /**
     * Set the network selection status.
     * @hide
     */
    @SystemApi
    public void setNetworkSelectionStatus(@NonNull NetworkSelectionStatus status) {
        mNetworkSelectionStatus = status;
    }

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
        FQDN = null;
        roamingConsortiumIds = new long[0];
        priority = 0;
        hiddenSSID = false;
        allowedKeyManagement = new BitSet();
        allowedProtocols = new BitSet();
        allowedAuthAlgorithms = new BitSet();
        allowedPairwiseCiphers = new BitSet();
        allowedGroupCiphers = new BitSet();
        allowedGroupManagementCiphers = new BitSet();
        allowedSuiteBCiphers = new BitSet();
        wepKeys = new String[4];
        for (int i = 0; i < wepKeys.length; i++) {
            wepKeys[i] = null;
        }
        enterpriseConfig = new WifiEnterpriseConfig();
        ephemeral = false;
        osu = false;
        trusted = true; // Networks are considered trusted by default.
        fromWifiNetworkSuggestion = false;
        fromWifiNetworkSpecifier = false;
        meteredHint = false;
        meteredOverride = METERED_OVERRIDE_NONE;
        useExternalScores = false;
        validatedInternetAccess = false;
        mIpConfiguration = new IpConfiguration();
        lastUpdateUid = -1;
        creatorUid = -1;
        shared = true;
        dtimInterval = 0;
        mRandomizedMacAddress = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
    }

    /**
     * Identify if this configuration represents a Passpoint network
     */
    public boolean isPasspoint() {
        return !TextUtils.isEmpty(FQDN)
                && !TextUtils.isEmpty(providerFriendlyName)
                && enterpriseConfig != null
                && enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE
                && !TextUtils.isEmpty(mPasspointUniqueId);
    }

    /**
     * Helper function, identify if a configuration is linked
     * @hide
     */
    public boolean isLinked(WifiConfiguration config) {
        if (config != null) {
            if (config.linkedConfigurations != null && linkedConfigurations != null) {
                if (config.linkedConfigurations.get(getKey()) != null
                        && linkedConfigurations.get(config.getKey()) != null) {
                    return true;
                }
            }
        }
        return  false;
    }

    /**
     * Helper function, idenfity if a configuration should be treated as an enterprise network
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isEnterprise() {
        return (allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                || allowedKeyManagement.get(KeyMgmt.IEEE8021X)
                || allowedKeyManagement.get(KeyMgmt.SUITE_B_192)
                || allowedKeyManagement.get(KeyMgmt.WAPI_CERT))
                && enterpriseConfig != null
                && enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE;
    }

    private static String logTimeOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        if (millis >= 0) {
            c.setTimeInMillis(millis);
            return String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c);
        } else {
            return Long.toString(millis);
        }
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();
        if (this.status == WifiConfiguration.Status.CURRENT) {
            sbuf.append("* ");
        } else if (this.status == WifiConfiguration.Status.DISABLED) {
            sbuf.append("- DSBLE ");
        }
        sbuf.append("ID: ").append(this.networkId).append(" SSID: ").append(this.SSID).
                append(" PROVIDER-NAME: ").append(this.providerFriendlyName).
                append(" BSSID: ").append(this.BSSID).append(" FQDN: ").append(this.FQDN)
                .append(" HOME-PROVIDER-NETWORK: ").append(this.isHomeProviderNetwork)
                .append(" PRIO: ").append(this.priority)
                .append(" HIDDEN: ").append(this.hiddenSSID)
                .append(" PMF: ").append(this.requirePmf)
                .append("CarrierId: ").append(this.carrierId)
                .append('\n');


        sbuf.append(" NetworkSelectionStatus ")
                .append(mNetworkSelectionStatus.getNetworkStatusString())
                .append("\n");
        if (mNetworkSelectionStatus.getNetworkSelectionDisableReason() > 0) {
            sbuf.append(" mNetworkSelectionDisableReason ")
                    .append(mNetworkSelectionStatus.getNetworkSelectionDisableReasonString())
                    .append("\n");

            for (int index = NetworkSelectionStatus.DISABLED_NONE;
                    index < NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX; index++) {
                if (mNetworkSelectionStatus.getDisableReasonCounter(index) != 0) {
                    sbuf.append(
                            NetworkSelectionStatus.getNetworkSelectionDisableReasonString(index))
                            .append(" counter:")
                            .append(mNetworkSelectionStatus.getDisableReasonCounter(index))
                            .append("\n");
                }
            }
        }
        if (mNetworkSelectionStatus.getConnectChoice() != null) {
            sbuf.append(" connect choice: ").append(mNetworkSelectionStatus.getConnectChoice());
        }
        sbuf.append(" hasEverConnected: ")
                .append(mNetworkSelectionStatus.hasEverConnected()).append("\n");

        if (this.numAssociation > 0) {
            sbuf.append(" numAssociation ").append(this.numAssociation).append("\n");
        }
        if (this.numNoInternetAccessReports > 0) {
            sbuf.append(" numNoInternetAccessReports ");
            sbuf.append(this.numNoInternetAccessReports).append("\n");
        }
        if (this.validatedInternetAccess) sbuf.append(" validatedInternetAccess");
        if (this.ephemeral) sbuf.append(" ephemeral");
        if (this.osu) sbuf.append(" osu");
        if (this.trusted) sbuf.append(" trusted");
        if (this.fromWifiNetworkSuggestion) sbuf.append(" fromWifiNetworkSuggestion");
        if (this.fromWifiNetworkSpecifier) sbuf.append(" fromWifiNetworkSpecifier");
        if (this.meteredHint) sbuf.append(" meteredHint");
        if (this.useExternalScores) sbuf.append(" useExternalScores");
        if (this.validatedInternetAccess || this.ephemeral || this.trusted
                || this.fromWifiNetworkSuggestion || this.fromWifiNetworkSpecifier
                || this.meteredHint || this.useExternalScores) {
            sbuf.append("\n");
        }
        if (this.meteredOverride != METERED_OVERRIDE_NONE) {
            sbuf.append(" meteredOverride ").append(meteredOverride).append("\n");
        }
        sbuf.append(" macRandomizationSetting: ").append(macRandomizationSetting).append("\n");
        sbuf.append(" mRandomizedMacAddress: ").append(mRandomizedMacAddress).append("\n");
        sbuf.append(" randomizedMacExpirationTimeMs: ")
                .append(randomizedMacExpirationTimeMs == 0 ? "<none>"
                        : logTimeOfDay(randomizedMacExpirationTimeMs)).append("\n");
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
        sbuf.append('\n');
        sbuf.append(" GroupMgmtCiphers:");
        for (int gmc = 0; gmc < this.allowedGroupManagementCiphers.size(); gmc++) {
            if (this.allowedGroupManagementCiphers.get(gmc)) {
                sbuf.append(" ");
                if (gmc < GroupMgmtCipher.strings.length) {
                    sbuf.append(GroupMgmtCipher.strings[gmc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n');
        sbuf.append(" SuiteBCiphers:");
        for (int sbc = 0; sbc < this.allowedSuiteBCiphers.size(); sbc++) {
            if (this.allowedSuiteBCiphers.get(sbc)) {
                sbuf.append(" ");
                if (sbc < SuiteBCipher.strings.length) {
                    sbuf.append(SuiteBCipher.strings[sbc]);
                } else {
                    sbuf.append("??");
                }
            }
        }
        sbuf.append('\n').append(" PSK/SAE: ");
        if (this.preSharedKey != null) {
            sbuf.append('*');
        }

        sbuf.append('\n').append(" SAE Password Id: ");
        sbuf.append(this.saePasswordId);

        sbuf.append("\nEnterprise config:\n");
        sbuf.append(enterpriseConfig);

        sbuf.append("IP config:\n");
        sbuf.append(mIpConfiguration.toString());

        if (mNetworkSelectionStatus.getNetworkSelectionBSSID() != null) {
            sbuf.append(" networkSelectionBSSID="
                    + mNetworkSelectionStatus.getNetworkSelectionBSSID());
        }
        long now_ms = SystemClock.elapsedRealtime();
        if (mNetworkSelectionStatus.getDisableTime() != NetworkSelectionStatus
                .INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP) {
            sbuf.append('\n');
            long diff = now_ms - mNetworkSelectionStatus.getDisableTime();
            if (diff <= 0) {
                sbuf.append(" blackListed since <incorrect>");
            } else {
                sbuf.append(" blackListed: ").append(Long.toString(diff / 1000)).append("sec ");
            }
        }
        if (creatorUid != 0) sbuf.append(" cuid=" + creatorUid);
        if (creatorName != null) sbuf.append(" cname=" + creatorName);
        if (lastUpdateUid != 0) sbuf.append(" luid=" + lastUpdateUid);
        if (lastUpdateName != null) sbuf.append(" lname=" + lastUpdateName);
        if (updateIdentifier != null) sbuf.append(" updateIdentifier=" + updateIdentifier);
        sbuf.append(" lcuid=" + lastConnectUid);
        sbuf.append(" allowAutojoin=" + allowAutojoin);
        sbuf.append(" noInternetAccessExpected=" + noInternetAccessExpected);
        sbuf.append(" ");

        if (this.lastConnected != 0) {
            sbuf.append('\n');
            sbuf.append("lastConnected: ").append(logTimeOfDay(this.lastConnected));
            sbuf.append(" ");
        }
        sbuf.append('\n');
        if (this.linkedConfigurations != null) {
            for (String key : this.linkedConfigurations.keySet()) {
                sbuf.append(" linked: ").append(key);
                sbuf.append('\n');
            }
        }
        sbuf.append("recentFailure: ").append("Association Rejection code: ")
                .append(recentFailure.getAssociationStatus()).append("\n");
        return sbuf.toString();
    }

    /**
     * Get the SSID in a human-readable format, with all additional formatting removed
     * e.g. quotation marks around the SSID, "P" prefix
     * @hide
     */
    @NonNull
    @SystemApi
    public String getPrintableSsid() {
        if (SSID == null) return "";
        final int length = SSID.length();
        if (length > 2 && (SSID.charAt(0) == '"') && SSID.charAt(length - 1) == '"') {
            return SSID.substring(1, length - 1);
        }

        /* The ascii-encoded string format is P"<ascii-encoded-string>"
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
        String keyMgmt = "";

        try {
            // Get current config details for fields that are not initialized
            if (TextUtils.isEmpty(SSID)) SSID = current.SSID;
            if (allowedKeyManagement.cardinality() == 0) {
                allowedKeyManagement = current.allowedKeyManagement;
            }
            if (allowedKeyManagement.get(KeyMgmt.WPA_EAP)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.WPA_EAP];
            }
            if (allowedKeyManagement.get(KeyMgmt.OSEN)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.OSEN];
            }
            if (allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.IEEE8021X];
            }
            if (allowedKeyManagement.get(KeyMgmt.SUITE_B_192)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.SUITE_B_192];
            }
            if (allowedKeyManagement.get(KeyMgmt.WAPI_CERT)) {
                keyMgmt += KeyMgmt.strings[KeyMgmt.WAPI_CERT];
            }

            if (TextUtils.isEmpty(keyMgmt)) {
                throw new IllegalStateException("Not an EAP network");
            }
            String keyId = trimStringForKeyId(SSID) + "_" + keyMgmt + "_"
                    + trimStringForKeyId(enterpriseConfig.getKeyId(current != null
                    ? current.enterpriseConfig : null));

            if (!fromWifiNetworkSuggestion) {
                return keyId;
            }
            return keyId + "_" + trimStringForKeyId(BSSID) + "_" + trimStringForKeyId(creatorName);
        } catch (NullPointerException e) {
            throw new IllegalStateException("Invalid config details");
        }
    }

    private String trimStringForKeyId(String string) {
        if (string == null) {
            return "";
        }
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

    /**
     * Get the authentication type of the network.
     * @return One of the {@link KeyMgmt} constants. e.g. {@link KeyMgmt#WPA2_PSK}.
     * @hide
     */
    @SystemApi
    @KeyMgmt.KeyMgmtScheme
    public int getAuthType() {
        if (allowedKeyManagement.cardinality() > 1) {
            throw new IllegalStateException("More than one auth type set");
        }
        if (allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            return KeyMgmt.WPA_PSK;
        } else if (allowedKeyManagement.get(KeyMgmt.WPA2_PSK)) {
            return KeyMgmt.WPA2_PSK;
        } else if (allowedKeyManagement.get(KeyMgmt.WPA_EAP)) {
            return KeyMgmt.WPA_EAP;
        } else if (allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            return KeyMgmt.IEEE8021X;
        } else if (allowedKeyManagement.get(KeyMgmt.SAE)) {
            return KeyMgmt.SAE;
        } else if (allowedKeyManagement.get(KeyMgmt.OWE)) {
            return KeyMgmt.OWE;
        } else if (allowedKeyManagement.get(KeyMgmt.SUITE_B_192)) {
            return KeyMgmt.SUITE_B_192;
        } else if (allowedKeyManagement.get(KeyMgmt.WAPI_PSK)) {
            return KeyMgmt.WAPI_PSK;
        } else if (allowedKeyManagement.get(KeyMgmt.WAPI_CERT)) {
            return KeyMgmt.WAPI_CERT;
        }
        return KeyMgmt.NONE;
    }

    /**
     * Return a String that can be used to uniquely identify this WifiConfiguration.
     * <br />
     * Note: Do not persist this value! This value is not guaranteed to remain backwards compatible.
     */
    @NonNull
    public String getKey() {
        // Passpoint ephemeral networks have their unique identifier set. Return it as is to be
        // able to match internally.
        if (mPasspointUniqueId != null) {
            return mPasspointUniqueId;
        }

        String key = getSsidAndSecurityTypeString();
        if (!shared) {
            key += "-" + UserHandle.getUserHandleForUid(creatorUid).getIdentifier();
        }

        return key;
    }

    /** @hide
     *  return the SSID + security type in String format.
     */
    public String getSsidAndSecurityTypeString() {
        String key;
        if (allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.WPA_PSK];
        } else if (allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                || allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.WPA_EAP];
        } else if (wepTxKeyIndex >= 0 && wepTxKeyIndex < wepKeys.length
                && wepKeys[wepTxKeyIndex] != null) {
            key = SSID + "WEP";
        } else if (allowedKeyManagement.get(KeyMgmt.OWE)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.OWE];
        } else if (allowedKeyManagement.get(KeyMgmt.SAE)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.SAE];
        } else if (allowedKeyManagement.get(KeyMgmt.SUITE_B_192)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.SUITE_B_192];
        } else if (allowedKeyManagement.get(KeyMgmt.WAPI_PSK)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.WAPI_PSK];
        } else if (allowedKeyManagement.get(KeyMgmt.WAPI_CERT)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.WAPI_CERT];
        } else if (allowedKeyManagement.get(KeyMgmt.OSEN)) {
            key = SSID + KeyMgmt.strings[KeyMgmt.OSEN];
        } else {
            key = SSID + KeyMgmt.strings[KeyMgmt.NONE];
        }
        return key;
    }

    /**
     * Get the IpConfiguration object associated with this WifiConfiguration.
     * @hide
     */
    @NonNull
    @SystemApi
    public IpConfiguration getIpConfiguration() {
        return new IpConfiguration(mIpConfiguration);
    }

    /**
     * Set the {@link IpConfiguration} for this network.
     * @param ipConfiguration the {@link IpConfiguration} to set, or null to use the default
     *                        constructor {@link IpConfiguration#IpConfiguration()}.
     * @hide
     */
    @SystemApi
    public void setIpConfiguration(@Nullable IpConfiguration ipConfiguration) {
        if (ipConfiguration == null) ipConfiguration = new IpConfiguration();
        mIpConfiguration = ipConfiguration;
    }

    /**
     * Get the {@link StaticIpConfiguration} for this network.
     * @return the {@link StaticIpConfiguration}, or null if unset.
     * @hide
     */
    @Nullable
    @UnsupportedAppUsage
    public StaticIpConfiguration getStaticIpConfiguration() {
        return mIpConfiguration.getStaticIpConfiguration();
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setStaticIpConfiguration(StaticIpConfiguration staticIpConfiguration) {
        mIpConfiguration.setStaticIpConfiguration(staticIpConfiguration);
    }

    /**
     * Get the {@link IpConfiguration.IpAssignment} for this network.
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
    public IpConfiguration.IpAssignment getIpAssignment() {
        return mIpConfiguration.getIpAssignment();
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setIpAssignment(IpConfiguration.IpAssignment ipAssignment) {
        mIpConfiguration.setIpAssignment(ipAssignment);
    }

    /**
     * Get the {@link IpConfiguration.ProxySettings} for this network.
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
    public IpConfiguration.ProxySettings getProxySettings() {
        return mIpConfiguration.getProxySettings();
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setProxySettings(IpConfiguration.ProxySettings proxySettings) {
        mIpConfiguration.setProxySettings(proxySettings);
    }

    /**
     * Returns the HTTP proxy used by this object.
     * @return a {@link ProxyInfo httpProxy} representing the proxy specified by this
     *                  WifiConfiguration, or {@code null} if no proxy is specified.
     */
    public ProxyInfo getHttpProxy() {
        if (mIpConfiguration.getProxySettings() == IpConfiguration.ProxySettings.NONE) {
            return null;
        }
        return new ProxyInfo(mIpConfiguration.getHttpProxy());
    }

    /**
     * Set the {@link ProxyInfo} for this WifiConfiguration. This method should only be used by a
     * device owner or profile owner. When other apps attempt to save a {@link WifiConfiguration}
     * with modified proxy settings, the methods {@link WifiManager#addNetwork} and
     * {@link WifiManager#updateNetwork} fail and return {@code -1}.
     *
     * @param httpProxy {@link ProxyInfo} representing the httpProxy to be used by this
     *                  WifiConfiguration. Setting this to {@code null} will explicitly set no
     *                  proxy, removing any proxy that was previously set.
     * @exception IllegalArgumentException for invalid httpProxy
     */
    public void setHttpProxy(ProxyInfo httpProxy) {
        if (httpProxy == null) {
            mIpConfiguration.setProxySettings(IpConfiguration.ProxySettings.NONE);
            mIpConfiguration.setHttpProxy(null);
            return;
        }
        ProxyInfo httpProxyCopy;
        ProxySettings proxySettingCopy;
        if (!Uri.EMPTY.equals(httpProxy.getPacFileUrl())) {
            proxySettingCopy = IpConfiguration.ProxySettings.PAC;
            // Construct a new PAC URL Proxy
            httpProxyCopy = ProxyInfo.buildPacProxy(httpProxy.getPacFileUrl(), httpProxy.getPort());
        } else {
            proxySettingCopy = IpConfiguration.ProxySettings.STATIC;
            // Construct a new HTTP Proxy
            httpProxyCopy = ProxyInfo.buildDirectProxy(httpProxy.getHost(), httpProxy.getPort(),
                    Arrays.asList(httpProxy.getExclusionList()));
        }
        if (!httpProxyCopy.isValid()) {
            throw new IllegalArgumentException("Invalid ProxyInfo: " + httpProxyCopy.toString());
        }
        mIpConfiguration.setProxySettings(proxySettingCopy);
        mIpConfiguration.setHttpProxy(httpProxyCopy);
    }

    /**
     * Set the {@link ProxySettings} and {@link ProxyInfo} for this network.
     * @hide
     */
    @UnsupportedAppUsage
    public void setProxy(@NonNull ProxySettings settings, @NonNull ProxyInfo proxy) {
        mIpConfiguration.setProxySettings(settings);
        mIpConfiguration.setHttpProxy(proxy);
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void setPasspointManagementObjectTree(String passpointManagementObjectTree) {
        mPasspointManagementObjectTree = passpointManagementObjectTree;
    }

    /** @hide */
    public String getMoTree() {
        return mPasspointManagementObjectTree;
    }

    /** Copy constructor */
    public WifiConfiguration(@NonNull WifiConfiguration source) {
        if (source != null) {
            networkId = source.networkId;
            status = source.status;
            SSID = source.SSID;
            BSSID = source.BSSID;
            FQDN = source.FQDN;
            roamingConsortiumIds = source.roamingConsortiumIds.clone();
            providerFriendlyName = source.providerFriendlyName;
            isHomeProviderNetwork = source.isHomeProviderNetwork;
            preSharedKey = source.preSharedKey;
            saePasswordId = source.saePasswordId;

            mNetworkSelectionStatus.copy(source.getNetworkSelectionStatus());
            apBand = source.apBand;
            apChannel = source.apChannel;

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
            allowedGroupManagementCiphers = (BitSet) source.allowedGroupManagementCiphers.clone();
            allowedSuiteBCiphers    = (BitSet) source.allowedSuiteBCiphers.clone();
            enterpriseConfig = new WifiEnterpriseConfig(source.enterpriseConfig);

            defaultGwMacAddress = source.defaultGwMacAddress;

            mIpConfiguration = new IpConfiguration(source.mIpConfiguration);

            if ((source.linkedConfigurations != null)
                    && (source.linkedConfigurations.size() > 0)) {
                linkedConfigurations = new HashMap<String, Integer>();
                linkedConfigurations.putAll(source.linkedConfigurations);
            }
            validatedInternetAccess = source.validatedInternetAccess;
            isLegacyPasspointConfig = source.isLegacyPasspointConfig;
            ephemeral = source.ephemeral;
            osu = source.osu;
            trusted = source.trusted;
            fromWifiNetworkSuggestion = source.fromWifiNetworkSuggestion;
            fromWifiNetworkSpecifier = source.fromWifiNetworkSpecifier;
            meteredHint = source.meteredHint;
            meteredOverride = source.meteredOverride;
            useExternalScores = source.useExternalScores;

            lastConnectUid = source.lastConnectUid;
            lastUpdateUid = source.lastUpdateUid;
            creatorUid = source.creatorUid;
            creatorName = source.creatorName;
            lastUpdateName = source.lastUpdateName;
            peerWifiConfiguration = source.peerWifiConfiguration;

            lastConnected = source.lastConnected;
            lastDisconnected = source.lastDisconnected;
            numScorerOverride = source.numScorerOverride;
            numScorerOverrideAndSwitchedNetwork = source.numScorerOverrideAndSwitchedNetwork;
            numAssociation = source.numAssociation;
            allowAutojoin = source.allowAutojoin;
            numNoInternetAccessReports = source.numNoInternetAccessReports;
            noInternetAccessExpected = source.noInternetAccessExpected;
            shared = source.shared;
            recentFailure.setAssociationStatus(source.recentFailure.getAssociationStatus());
            mRandomizedMacAddress = source.mRandomizedMacAddress;
            macRandomizationSetting = source.macRandomizationSetting;
            randomizedMacExpirationTimeMs = source.randomizedMacExpirationTimeMs;
            requirePmf = source.requirePmf;
            updateIdentifier = source.updateIdentifier;
            carrierId = source.carrierId;
            mPasspointUniqueId = source.mPasspointUniqueId;
        }
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(networkId);
        dest.writeInt(status);
        mNetworkSelectionStatus.writeToParcel(dest);
        dest.writeString(SSID);
        dest.writeString(BSSID);
        dest.writeInt(apBand);
        dest.writeInt(apChannel);
        dest.writeString(FQDN);
        dest.writeString(providerFriendlyName);
        dest.writeInt(isHomeProviderNetwork ? 1 : 0);
        dest.writeInt(roamingConsortiumIds.length);
        for (long roamingConsortiumId : roamingConsortiumIds) {
            dest.writeLong(roamingConsortiumId);
        }
        dest.writeString(preSharedKey);
        dest.writeString(saePasswordId);
        for (String wepKey : wepKeys) {
            dest.writeString(wepKey);
        }
        dest.writeInt(wepTxKeyIndex);
        dest.writeInt(priority);
        dest.writeInt(hiddenSSID ? 1 : 0);
        dest.writeInt(requirePmf ? 1 : 0);
        dest.writeString(updateIdentifier);

        writeBitSet(dest, allowedKeyManagement);
        writeBitSet(dest, allowedProtocols);
        writeBitSet(dest, allowedAuthAlgorithms);
        writeBitSet(dest, allowedPairwiseCiphers);
        writeBitSet(dest, allowedGroupCiphers);
        writeBitSet(dest, allowedGroupManagementCiphers);
        writeBitSet(dest, allowedSuiteBCiphers);

        dest.writeParcelable(enterpriseConfig, flags);

        dest.writeParcelable(mIpConfiguration, flags);
        dest.writeString(dhcpServer);
        dest.writeString(defaultGwMacAddress);
        dest.writeInt(validatedInternetAccess ? 1 : 0);
        dest.writeInt(isLegacyPasspointConfig ? 1 : 0);
        dest.writeInt(ephemeral ? 1 : 0);
        dest.writeInt(trusted ? 1 : 0);
        dest.writeInt(fromWifiNetworkSuggestion ? 1 : 0);
        dest.writeInt(fromWifiNetworkSpecifier ? 1 : 0);
        dest.writeInt(meteredHint ? 1 : 0);
        dest.writeInt(meteredOverride);
        dest.writeInt(useExternalScores ? 1 : 0);
        dest.writeInt(creatorUid);
        dest.writeInt(lastConnectUid);
        dest.writeInt(lastUpdateUid);
        dest.writeString(creatorName);
        dest.writeString(lastUpdateName);
        dest.writeInt(numScorerOverride);
        dest.writeInt(numScorerOverrideAndSwitchedNetwork);
        dest.writeInt(numAssociation);
        dest.writeBoolean(allowAutojoin);
        dest.writeInt(numNoInternetAccessReports);
        dest.writeInt(noInternetAccessExpected ? 1 : 0);
        dest.writeInt(shared ? 1 : 0);
        dest.writeString(mPasspointManagementObjectTree);
        dest.writeInt(recentFailure.getAssociationStatus());
        dest.writeParcelable(mRandomizedMacAddress, flags);
        dest.writeInt(macRandomizationSetting);
        dest.writeInt(osu ? 1 : 0);
        dest.writeLong(randomizedMacExpirationTimeMs);
        dest.writeInt(carrierId);
        dest.writeString(mPasspointUniqueId);
    }

    /** Implement the Parcelable interface {@hide} */
    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<WifiConfiguration> CREATOR =
        new Creator<WifiConfiguration>() {
            public WifiConfiguration createFromParcel(Parcel in) {
                WifiConfiguration config = new WifiConfiguration();
                config.networkId = in.readInt();
                config.status = in.readInt();
                config.mNetworkSelectionStatus.readFromParcel(in);
                config.SSID = in.readString();
                config.BSSID = in.readString();
                config.apBand = in.readInt();
                config.apChannel = in.readInt();
                config.FQDN = in.readString();
                config.providerFriendlyName = in.readString();
                config.isHomeProviderNetwork = in.readInt() != 0;
                int numRoamingConsortiumIds = in.readInt();
                config.roamingConsortiumIds = new long[numRoamingConsortiumIds];
                for (int i = 0; i < numRoamingConsortiumIds; i++) {
                    config.roamingConsortiumIds[i] = in.readLong();
                }
                config.preSharedKey = in.readString();
                config.saePasswordId = in.readString();
                for (int i = 0; i < config.wepKeys.length; i++) {
                    config.wepKeys[i] = in.readString();
                }
                config.wepTxKeyIndex = in.readInt();
                config.priority = in.readInt();
                config.hiddenSSID = in.readInt() != 0;
                config.requirePmf = in.readInt() != 0;
                config.updateIdentifier = in.readString();

                config.allowedKeyManagement   = readBitSet(in);
                config.allowedProtocols       = readBitSet(in);
                config.allowedAuthAlgorithms  = readBitSet(in);
                config.allowedPairwiseCiphers = readBitSet(in);
                config.allowedGroupCiphers    = readBitSet(in);
                config.allowedGroupManagementCiphers = readBitSet(in);
                config.allowedSuiteBCiphers   = readBitSet(in);

                config.enterpriseConfig = in.readParcelable(null);
                config.setIpConfiguration(in.readParcelable(null));
                config.dhcpServer = in.readString();
                config.defaultGwMacAddress = in.readString();
                config.validatedInternetAccess = in.readInt() != 0;
                config.isLegacyPasspointConfig = in.readInt() != 0;
                config.ephemeral = in.readInt() != 0;
                config.trusted = in.readInt() != 0;
                config.fromWifiNetworkSuggestion =  in.readInt() != 0;
                config.fromWifiNetworkSpecifier =  in.readInt() != 0;
                config.meteredHint = in.readInt() != 0;
                config.meteredOverride = in.readInt();
                config.useExternalScores = in.readInt() != 0;
                config.creatorUid = in.readInt();
                config.lastConnectUid = in.readInt();
                config.lastUpdateUid = in.readInt();
                config.creatorName = in.readString();
                config.lastUpdateName = in.readString();
                config.numScorerOverride = in.readInt();
                config.numScorerOverrideAndSwitchedNetwork = in.readInt();
                config.numAssociation = in.readInt();
                config.allowAutojoin = in.readBoolean();
                config.numNoInternetAccessReports = in.readInt();
                config.noInternetAccessExpected = in.readInt() != 0;
                config.shared = in.readInt() != 0;
                config.mPasspointManagementObjectTree = in.readString();
                config.recentFailure.setAssociationStatus(in.readInt());
                config.mRandomizedMacAddress = in.readParcelable(null);
                config.macRandomizationSetting = in.readInt();
                config.osu = in.readInt() != 0;
                config.randomizedMacExpirationTimeMs = in.readLong();
                config.carrierId = in.readInt();
                config.mPasspointUniqueId = in.readString();
                return config;
            }

            public WifiConfiguration[] newArray(int size) {
                return new WifiConfiguration[size];
            }
        };

    /**
     * Passpoint Unique identifier
     * @hide
     */
    private String mPasspointUniqueId = null;

    /**
     * Set the Passpoint unique identifier
     * @param uniqueId Passpoint unique identifier to be set
     * @hide
     */
    public void setPasspointUniqueId(String uniqueId) {
        mPasspointUniqueId = uniqueId;
    }

    /**
     * Set the Passpoint unique identifier
     * @hide
     */
    public String getPasspointUniqueId() {
        return mPasspointUniqueId;
    }

}
