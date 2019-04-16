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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.IpConfiguration.ProxySettings;
import android.net.MacAddress;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.BackupUtils;
import android.util.Log;
import android.util.TimeUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;

/**
 * A class representing a configured Wi-Fi network, including the
 * security configuration.
 */
public class WifiConfiguration implements Parcelable {
    private static final String TAG = "WifiConfiguration";
    /**
     * Current Version of the Backup Serializer.
    */
    private static final int BACKUP_VERSION = 2;
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
    /** {@hide} */
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

        public static final String varName = "key_mgmt";

        public static final String[] strings = { "NONE", "WPA_PSK", "WPA_EAP",
                "IEEE8021X", "WPA2_PSK", "OSEN", "FT_PSK", "FT_EAP" };
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
        /** WPA2/IEEE 802.11i */
        public static final int RSN = 1;
        /** HS2.0 r2 OSEN
         * @hide
         */
        public static final int OSEN = 2;

        public static final String varName = "proto";

        public static final String[] strings = { "WPA", "RSN", "OSEN" };
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
        /** Temporal Key Integrity Protocol [IEEE 802.11i/D7.0]
         * @deprecated Due to security and performance limitations, use of WPA-1 networks
         * is discouraged. WPA-2 (RSN) should be used instead. */
        @Deprecated
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

        public static final String varName = "group";

        public static final String[] strings =
                { /* deprecated */ "WEP40", /* deprecated */ "WEP104",
                        "TKIP", "CCMP", "GTK_NOT_USED" };
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
     * The band which AP resides on
     * -1:Any 0:2G 1:5G
     * By default, 2G is chosen
     * @hide
     */
    @UnsupportedAppUsage
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
     * This is a network that requries Protected Management Frames (PMF).
     * @hide
     */
    public boolean requirePMF;

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
     * @hide
     * This network configuration is visible to and usable by other users on the
     * same device.
     */
    @UnsupportedAppUsage
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
     * @hide
     * Status of user approval for connection
     */
    public int userApproved = USER_UNSPECIFIED;

    /** The Below RSSI thresholds are used to configure AutoJoin
     *  - GOOD/LOW/BAD thresholds are used so as to calculate link score
     *  - UNWANTED_SOFT are used by the blacklisting logic so as to handle
     *  the unwanted network message coming from CS
     *  - UNBLACKLIST thresholds are used so as to tweak the speed at which
     *  the network is unblacklisted (i.e. if
     *          it is seen with good RSSI, it is blacklisted faster)
     *  - INITIAL_AUTOJOIN_ATTEMPT, used to determine how close from
     *  the network we need to be before autojoin kicks in
     */
    /** @hide **/
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static int INVALID_RSSI = -127;

    // States for the userApproved field
    /**
     * @hide
     * User hasn't specified if connection is okay
     */
    public static final int USER_UNSPECIFIED = 0;
    /**
     * @hide
     * User has approved this for connection
     */
    public static final int USER_APPROVED = 1;
    /**
     * @hide
     * User has banned this from connection
     */
    public static final int USER_BANNED = 2;
    /**
     * @hide
     * Waiting for user input
     */
    public static final int USER_PENDING = 3;

    /**
     * @hide
     * Number of reports indicating no Internet Access
     */
    @UnsupportedAppUsage
    public int numNoInternetAccessReports;

    /**
     * @hide
     * For debug: date at which the config was last updated
     */
    public String updateTime;

    /**
     * @hide
     * For debug: date at which the config was last updated
     */
    public String creationTime;

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
     * @hide
     */
    @UnsupportedAppUsage
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
     * Indicates if the creator of this configuration has expressed that it
     * should be considered metered.
     *
     * @see #isMetered(WifiConfiguration, WifiInfo)
     * @hide
     */
    @SystemApi
    public boolean meteredHint;

    /** {@hide} */
    public static final int METERED_OVERRIDE_NONE = 0;
    /** {@hide} */
    public static final int METERED_OVERRIDE_METERED = 1;
    /** {@hide} */
    public static final int METERED_OVERRIDE_NOT_METERED = 2;

    /**
     * Indicates if the end user has expressed an explicit opinion about the
     * meteredness of this network, such as through the Settings app.
     * <p>
     * This should always override any values from {@link #meteredHint} or
     * {@link WifiInfo#getMeteredHint()}.
     *
     * @see #isMetered(WifiConfiguration, WifiInfo)
     * @hide
     */
    public int meteredOverride = METERED_OVERRIDE_NONE;

    /**
     * Blend together all the various opinions to decide if the given network
     * should be considered metered or not.
     *
     * @hide
     */
    public static boolean isMetered(WifiConfiguration config, WifiInfo info) {
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
                || (cardinality == 1 && allowedKeyManagement.get(KeyMgmt.NONE));

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

    /**
     * @hide
     * Randomized MAC address to use with this particular network
     */
    @NonNull
    private MacAddress mRandomizedMacAddress;

    /**
     * @hide
     * Checks if the given MAC address can be used for Connected Mac Randomization
     * by verifying that it is non-null, unicast, locally assigned, and not default mac.
     * @param mac MacAddress to check
     * @return true if mac is good to use
     */
    public static boolean isValidMacAddressForRandomization(MacAddress mac) {
        return mac != null && !mac.isMulticastAddress() && mac.isLocallyAssigned()
                && !MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS).equals(mac);
    }

    /**
     * @hide
     * Returns Randomized MAC address to use with the network.
     * If it is not set/valid, creates a new randomized address.
     * If it can't generate a valid mac, returns the default MAC.
     */
    public @NonNull MacAddress getOrCreateRandomizedMacAddress() {
        int randomMacGenerationCount = 0;
        while (!isValidMacAddressForRandomization(mRandomizedMacAddress)
                && randomMacGenerationCount < MAXIMUM_RANDOM_MAC_GENERATION_RETRY) {
            mRandomizedMacAddress = MacAddress.createRandomUnicastAddress();
            randomMacGenerationCount++;
        }

        if (!isValidMacAddressForRandomization(mRandomizedMacAddress)) {
            mRandomizedMacAddress = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        }
        return mRandomizedMacAddress;
    }

    /**
     * @hide
     * Returns MAC address set to be the local randomized MAC address.
     * Does not guarantee that the returned address is valid for use.
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
     * @hide
     * This class is used to contain all the information and API used for quality network selection
     */
    public static class NetworkSelectionStatus {
        /**
         * Quality Network Selection Status enable, temporary disabled, permanently disabled
         */
        /**
         * This network is allowed to join Quality Network Selection
         */
        public static final int NETWORK_SELECTION_ENABLED = 0;
        /**
         * network was temporary disabled. Can be re-enabled after a time period expire
         */
        public static final int NETWORK_SELECTION_TEMPORARY_DISABLED  = 1;
        /**
         * network was permanently disabled.
         */
        public static final int NETWORK_SELECTION_PERMANENTLY_DISABLED  = 2;
        /**
         * Maximum Network selection status
         */
        public static final int NETWORK_SELECTION_STATUS_MAX = 3;

        /**
         * Quality network selection status String (for debug purpose). Use Quality network
         * selection status value as index to extec the corresponding debug string
         */
        public static final String[] QUALITY_NETWORK_SELECTION_STATUS = {
                "NETWORK_SELECTION_ENABLED",
                "NETWORK_SELECTION_TEMPORARY_DISABLED",
                "NETWORK_SELECTION_PERMANENTLY_DISABLED"};

        //Quality Network disabled reasons
        /**
         * Default value. Means not disabled
         */
        public static final int NETWORK_SELECTION_ENABLE = 0;
        /**
         * The starting index for network selection disabled reasons
         */
        public static final int NETWORK_SELECTION_DISABLED_STARTING_INDEX = 1;
        /**
         * @deprecated it is not used any more.
         * This network is disabled because higher layer (>2) network is bad
         */
        public static final int DISABLED_BAD_LINK = 1;
        /**
         * This network is disabled because multiple association rejects
         */
        public static final int DISABLED_ASSOCIATION_REJECTION = 2;
        /**
         * This network is disabled because multiple authentication failure
         */
        public static final int DISABLED_AUTHENTICATION_FAILURE = 3;
        /**
         * This network is disabled because multiple DHCP failure
         */
        public static final int DISABLED_DHCP_FAILURE = 4;
        /**
         * This network is disabled because of security network but no credentials
         */
        public static final int DISABLED_DNS_FAILURE = 5;
        /**
         * This network is temporarily disabled because it has no Internet access.
         */
        public static final int DISABLED_NO_INTERNET_TEMPORARY = 6;
        /**
         * This network is disabled because we started WPS
         */
        public static final int DISABLED_WPS_START = 7;
        /**
         * This network is disabled because EAP-TLS failure
         */
        public static final int DISABLED_TLS_VERSION_MISMATCH = 8;
        // Values above are for temporary disablement; values below are for permanent disablement.
        /**
         * This network is disabled due to absence of user credentials
         */
        public static final int DISABLED_AUTHENTICATION_NO_CREDENTIALS = 9;
        /**
         * This network is permanently disabled because it has no Internet access and user does not
         * want to stay connected.
         */
        public static final int DISABLED_NO_INTERNET_PERMANENT = 10;
        /**
         * This network is disabled due to WifiManager disable it explicitly
         */
        public static final int DISABLED_BY_WIFI_MANAGER = 11;
        /**
         * This network is disabled due to user switching
         */
        public static final int DISABLED_DUE_TO_USER_SWITCH = 12;
        /**
         * This network is disabled due to wrong password
         */
        public static final int DISABLED_BY_WRONG_PASSWORD = 13;
        /**
         * This Maximum disable reason value
         */
        public static final int NETWORK_SELECTION_DISABLED_MAX = 14;

        /**
         * Quality network selection disable reason String (for debug purpose)
         */
        public static final String[] QUALITY_NETWORK_SELECTION_DISABLE_REASON = {
                "NETWORK_SELECTION_ENABLE",
                "NETWORK_SELECTION_DISABLED_BAD_LINK", // deprecated
                "NETWORK_SELECTION_DISABLED_ASSOCIATION_REJECTION ",
                "NETWORK_SELECTION_DISABLED_AUTHENTICATION_FAILURE",
                "NETWORK_SELECTION_DISABLED_DHCP_FAILURE",
                "NETWORK_SELECTION_DISABLED_DNS_FAILURE",
                "NETWORK_SELECTION_DISABLED_NO_INTERNET_TEMPORARY",
                "NETWORK_SELECTION_DISABLED_WPS_START",
                "NETWORK_SELECTION_DISABLED_TLS_VERSION",
                "NETWORK_SELECTION_DISABLED_AUTHENTICATION_NO_CREDENTIALS",
                "NETWORK_SELECTION_DISABLED_NO_INTERNET_PERMANENT",
                "NETWORK_SELECTION_DISABLED_BY_WIFI_MANAGER",
                "NETWORK_SELECTION_DISABLED_BY_USER_SWITCH",
                "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD"
        };

        /**
         * Invalid time stamp for network selection disable
         */
        public static final long INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP = -1L;

        /**
         *  This constant indicates the current configuration has connect choice set
         */
        private static final int CONNECT_CHOICE_EXISTS = 1;

        /**
         *  This constant indicates the current configuration does not have connect choice set
         */
        private static final int CONNECT_CHOICE_NOT_EXISTS = -1;

        // fields for QualityNetwork Selection
        /**
         * Network selection status, should be in one of three status: enable, temporaily disabled
         * or permanently disabled
         */
        private int mStatus;

        /**
         * Reason for disable this network
         */
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
         * The system timestamp when we records the connectChoice. This value is obtained from
         * System.currentTimeMillis
         */
        private long mConnectChoiceTimestamp = INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP;

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
         * Boolean indicating whether {@link com.android.server.wifi.RecommendedNetworkEvaluator}
         * chose not to connect to this network in the last qualified network selection process.
         */
        private boolean mNotRecommended;

        /**
         * Set whether {@link com.android.server.wifi.RecommendedNetworkEvaluator} does not
         * recommend connecting to this network.
         */
        public void setNotRecommended(boolean notRecommended) {
            mNotRecommended = notRecommended;
        }

        /**
         * Returns whether {@link com.android.server.wifi.RecommendedNetworkEvaluator} does not
         * recommend connecting to this network.
         */
        public boolean isNotRecommended() {
            return mNotRecommended;
        }

        /**
         * set whether this network is visible in latest Qualified Network Selection
         * @param seen value set to candidate
         */
        public void setSeenInLastQualifiedNetworkSelection(boolean seen) {
            mSeenInLastQualifiedNetworkSelection =  seen;
        }

        /**
         * get whether this network is visible in latest Qualified Network Selection
         * @return returns true -- network is visible in latest Qualified Network Selection
         *         false -- network is invisible in latest Qualified Network Selection
         */
        public boolean getSeenInLastQualifiedNetworkSelection() {
            return mSeenInLastQualifiedNetworkSelection;
        }
        /**
         * set the temporary candidate of current network selection procedure
         * @param scanCandidate {@link ScanResult} the candidate set to mCandidate
         */
        public void setCandidate(ScanResult scanCandidate) {
            mCandidate = scanCandidate;
        }

        /**
         * get the temporary candidate of current network selection procedure
         * @return  returns {@link ScanResult} temporary candidate of current network selection
         * procedure
         */
        public ScanResult getCandidate() {
            return mCandidate;
        }

        /**
         * set the score of the temporary candidate of current network selection procedure
         * @param score value set to mCandidateScore
         */
        public void setCandidateScore(int score) {
            mCandidateScore = score;
        }

        /**
         * get the score of the temporary candidate of current network selection procedure
         * @return returns score of the temporary candidate of current network selection procedure
         */
        public int getCandidateScore() {
            return mCandidateScore;
        }

        /**
         * get user preferred choice over this configuration
         *@return returns configKey of user preferred choice over this configuration
         */
        public String getConnectChoice() {
            return mConnectChoice;
        }

        /**
         * set user preferred choice over this configuration
         * @param newConnectChoice, the configKey of user preferred choice over this configuration
         */
        public void setConnectChoice(String newConnectChoice) {
            mConnectChoice = newConnectChoice;
        }

        /**
         * get the timeStamp when user select a choice over this configuration
         * @return returns when current connectChoice is set (time from System.currentTimeMillis)
         */
        public long getConnectChoiceTimestamp() {
            return mConnectChoiceTimestamp;
        }

        /**
         * set the timeStamp when user select a choice over this configuration
         * @param timeStamp, the timestamp set to connectChoiceTimestamp, expected timestamp should
         *        be obtained from System.currentTimeMillis
         */
        public void setConnectChoiceTimestamp(long timeStamp) {
            mConnectChoiceTimestamp = timeStamp;
        }

        /**
         * get current Quality network selection status
         * @return returns current Quality network selection status in String (for debug purpose)
         */
        public String getNetworkStatusString() {
            return QUALITY_NETWORK_SELECTION_STATUS[mStatus];
        }

        public void setHasEverConnected(boolean value) {
            mHasEverConnected = value;
        }

        public boolean getHasEverConnected() {
            return mHasEverConnected;
        }

        public NetworkSelectionStatus() {
            // previously stored configs will not have this parameter, so we default to false.
            mHasEverConnected = false;
        };

        /**
         * @param reason specific error reason
         * @return  corresponding network disable reason String (for debug purpose)
         */
        public static String getNetworkDisableReasonString(int reason) {
            if (reason >= NETWORK_SELECTION_ENABLE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                return QUALITY_NETWORK_SELECTION_DISABLE_REASON[reason];
            } else {
                return null;
            }
        }
        /**
         * get current network disable reason
         * @return current network disable reason in String (for debug purpose)
         */
        public String getNetworkDisableReasonString() {
            return QUALITY_NETWORK_SELECTION_DISABLE_REASON[mNetworkSelectionDisableReason];
        }

        /**
         * get current network network selection status
         * @return return current network network selection status
         */
        public int getNetworkSelectionStatus() {
            return mStatus;
        }
        /**
         * @return whether current network is enabled to join network selection
         */
        public boolean isNetworkEnabled() {
            return mStatus == NETWORK_SELECTION_ENABLED;
        }

        /**
         * @return whether current network is temporary disabled
         */
        public boolean isNetworkTemporaryDisabled() {
            return mStatus == NETWORK_SELECTION_TEMPORARY_DISABLED;
        }

        /**
         * @return returns whether current network is permanently disabled
         */
        public boolean isNetworkPermanentlyDisabled() {
            return mStatus == NETWORK_SELECTION_PERMANENTLY_DISABLED;
        }

        /**
         * set current networ work selection status
         * @param status network selection status to set
         */
        public void setNetworkSelectionStatus(int status) {
            if (status >= 0 && status < NETWORK_SELECTION_STATUS_MAX) {
                mStatus = status;
            }
        }

        /**
         * @return returns current network's disable reason
         */
        public int getNetworkSelectionDisableReason() {
            return mNetworkSelectionDisableReason;
        }

        /**
         * set Network disable reason
         * @param  reason Network disable reason
         */
        public void setNetworkSelectionDisableReason(int reason) {
            if (reason >= 0 && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSelectionDisableReason = reason;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * check whether network is disabled by this reason
         * @param reason a specific disable reason
         * @return true -- network is disabled for this reason
         *         false -- network is not disabled for this reason
         */
        public boolean isDisabledByReason(int reason) {
            return mNetworkSelectionDisableReason == reason;
        }

        /**
         * @param timeStamp Set when current network is disabled in millisecond since January 1,
         * 1970 00:00:00.0 UTC
         */
        public void setDisableTime(long timeStamp) {
            mTemporarilyDisabledTimestamp = timeStamp;
        }

        /**
         * @return returns when current network is disabled in millisecond since January 1,
         * 1970 00:00:00.0 UTC
         */
        public long getDisableTime() {
            return mTemporarilyDisabledTimestamp;
        }

        /**
         * get the disable counter of a specific reason
         * @param  reason specific failure reason
         * @exception throw IllegalArgumentException for illegal input
         * @return counter number for specific error reason.
         */
        public int getDisableReasonCounter(int reason) {
            if (reason >= NETWORK_SELECTION_ENABLE && reason < NETWORK_SELECTION_DISABLED_MAX) {
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
         */
        public void setDisableReasonCounter(int reason, int value) {
            if (reason >= NETWORK_SELECTION_ENABLE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason] = value;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * increment the counter of a specific failure reason
         * @param reason a specific failure reason
         * @exception throw IllegalArgumentException for illegal input
         */
        public void incrementDisableReasonCounter(int reason) {
            if (reason >= NETWORK_SELECTION_ENABLE  && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason]++;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * clear the counter of a specific failure reason
         * @hide
         * @param reason a specific failure reason
         * @exception throw IllegalArgumentException for illegal input
         */
        public void clearDisableReasonCounter(int reason) {
            if (reason >= NETWORK_SELECTION_ENABLE && reason < NETWORK_SELECTION_DISABLED_MAX) {
                mNetworkSeclectionDisableCounter[reason] = NETWORK_SELECTION_ENABLE;
            } else {
                throw new IllegalArgumentException("Illegal reason value: " + reason);
            }
        }

        /**
         * clear all the failure reason counters
         */
        public void clearDisableReasonCounter() {
            Arrays.fill(mNetworkSeclectionDisableCounter, NETWORK_SELECTION_ENABLE);
        }

        /**
         * BSSID for connection to this network (through network selection procedure)
         */
        private String mNetworkSelectionBSSID;

        /**
         * get current network Selection BSSID
         * @return current network Selection BSSID
         */
        public String getNetworkSelectionBSSID() {
            return mNetworkSelectionBSSID;
        }

        /**
         * set network Selection BSSID
         * @param bssid The target BSSID for assocaition
         */
        public void setNetworkSelectionBSSID(String bssid) {
            mNetworkSelectionBSSID = bssid;
        }

        public void copy(NetworkSelectionStatus source) {
            mStatus = source.mStatus;
            mNetworkSelectionDisableReason = source.mNetworkSelectionDisableReason;
            for (int index = NETWORK_SELECTION_ENABLE; index < NETWORK_SELECTION_DISABLED_MAX;
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
            setConnectChoiceTimestamp(source.getConnectChoiceTimestamp());
            setHasEverConnected(source.getHasEverConnected());
            setNotRecommended(source.isNotRecommended());
        }

        public void writeToParcel(Parcel dest) {
            dest.writeInt(getNetworkSelectionStatus());
            dest.writeInt(getNetworkSelectionDisableReason());
            for (int index = NETWORK_SELECTION_ENABLE; index < NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                dest.writeInt(getDisableReasonCounter(index));
            }
            dest.writeLong(getDisableTime());
            dest.writeString(getNetworkSelectionBSSID());
            if (getConnectChoice() != null) {
                dest.writeInt(CONNECT_CHOICE_EXISTS);
                dest.writeString(getConnectChoice());
                dest.writeLong(getConnectChoiceTimestamp());
            } else {
                dest.writeInt(CONNECT_CHOICE_NOT_EXISTS);
            }
            dest.writeInt(getHasEverConnected() ? 1 : 0);
            dest.writeInt(isNotRecommended() ? 1 : 0);
        }

        public void readFromParcel(Parcel in) {
            setNetworkSelectionStatus(in.readInt());
            setNetworkSelectionDisableReason(in.readInt());
            for (int index = NETWORK_SELECTION_ENABLE; index < NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                setDisableReasonCounter(index, in.readInt());
            }
            setDisableTime(in.readLong());
            setNetworkSelectionBSSID(in.readString());
            if (in.readInt() == CONNECT_CHOICE_EXISTS) {
                setConnectChoice(in.readString());
                setConnectChoiceTimestamp(in.readLong());
            } else {
                setConnectChoice(null);
                setConnectChoiceTimestamp(INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
            }
            setHasEverConnected(in.readInt() != 0);
            setNotRecommended(in.readInt() != 0);
        }
    }

    /**
     * @hide
     * network selection related member
     */
    private NetworkSelectionStatus mNetworkSelectionStatus = new NetworkSelectionStatus();

    /**
     * @hide
     * This class is intended to store extra failure reason information for the most recent
     * connection attempt, so that it may be surfaced to the settings UI
     */
    public static class RecentFailure {

        /**
         * No recent failure, or no specific reason given for the recent connection failure
         */
        public static final int NONE = 0;
        /**
         * Connection to this network recently failed due to Association Rejection Status 17
         * (AP is full)
         */
        public static final int STATUS_AP_UNABLE_TO_HANDLE_NEW_STA = 17;
        /**
         * Association Rejection Status code (NONE for success/non-association-rejection-fail)
         */
        private int mAssociationStatus = NONE;

        /**
         * @param status the association status code for the recent failure
         */
        public void setAssociationStatus(int status) {
            mAssociationStatus = status;
        }
        /**
         * Sets the RecentFailure to NONE
         */
        public void clear() {
            mAssociationStatus = NONE;
        }
        /**
         * Get the recent failure code
         */
        public int getAssociationStatus() {
            return mAssociationStatus;
        }
    }

    /**
     * @hide
     * RecentFailure member
     */
    final public RecentFailure recentFailure = new RecentFailure();

    /**
     * @hide
     * @return network selection status
     */
    public NetworkSelectionStatus getNetworkSelectionStatus() {
        return mNetworkSelectionStatus;
    }

    /**
     * Set the network selection status
     * @hide
     */
    public void setNetworkSelectionStatus(NetworkSelectionStatus status) {
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
        wepKeys = new String[4];
        for (int i = 0; i < wepKeys.length; i++) {
            wepKeys[i] = null;
        }
        enterpriseConfig = new WifiEnterpriseConfig();
        selfAdded = false;
        didSelfAdd = false;
        ephemeral = false;
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
                && enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE;
    }

    /**
     * Helper function, identify if a configuration is linked
     * @hide
     */
    public boolean isLinked(WifiConfiguration config) {
        if (config != null) {
            if (config.linkedConfigurations != null && linkedConfigurations != null) {
                if (config.linkedConfigurations.get(configKey()) != null
                        && linkedConfigurations.get(config.configKey()) != null) {
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
                || allowedKeyManagement.get(KeyMgmt.IEEE8021X))
                && enterpriseConfig != null
                && enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE;
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
                .append(" PRIO: ").append(this.priority)
                .append(" HIDDEN: ").append(this.hiddenSSID)
                .append('\n');


        sbuf.append(" NetworkSelectionStatus ")
                .append(mNetworkSelectionStatus.getNetworkStatusString() + "\n");
        if (mNetworkSelectionStatus.getNetworkSelectionDisableReason() > 0) {
            sbuf.append(" mNetworkSelectionDisableReason ")
                    .append(mNetworkSelectionStatus.getNetworkDisableReasonString() + "\n");

            for (int index = mNetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                    index < mNetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX; index++) {
                if (mNetworkSelectionStatus.getDisableReasonCounter(index) != 0) {
                    sbuf.append(NetworkSelectionStatus.getNetworkDisableReasonString(index)
                            + " counter:" + mNetworkSelectionStatus.getDisableReasonCounter(index)
                            + "\n");
                }
            }
        }
        if (mNetworkSelectionStatus.getConnectChoice() != null) {
            sbuf.append(" connect choice: ").append(mNetworkSelectionStatus.getConnectChoice());
            sbuf.append(" connect choice set time: ")
                    .append(TimeUtils.logTimeOfDay(
                            mNetworkSelectionStatus.getConnectChoiceTimestamp()));
        }
        sbuf.append(" hasEverConnected: ")
                .append(mNetworkSelectionStatus.getHasEverConnected()).append("\n");

        if (this.numAssociation > 0) {
            sbuf.append(" numAssociation ").append(this.numAssociation).append("\n");
        }
        if (this.numNoInternetAccessReports > 0) {
            sbuf.append(" numNoInternetAccessReports ");
            sbuf.append(this.numNoInternetAccessReports).append("\n");
        }
        if (this.updateTime != null) {
            sbuf.append(" update ").append(this.updateTime).append("\n");
        }
        if (this.creationTime != null) {
            sbuf.append(" creation ").append(this.creationTime).append("\n");
        }
        if (this.didSelfAdd) sbuf.append(" didSelfAdd");
        if (this.selfAdded) sbuf.append(" selfAdded");
        if (this.validatedInternetAccess) sbuf.append(" validatedInternetAccess");
        if (this.ephemeral) sbuf.append(" ephemeral");
        if (this.meteredHint) sbuf.append(" meteredHint");
        if (this.useExternalScores) sbuf.append(" useExternalScores");
        if (this.didSelfAdd || this.selfAdded || this.validatedInternetAccess
            || this.ephemeral || this.meteredHint || this.useExternalScores) {
            sbuf.append("\n");
        }
        if (this.meteredOverride != METERED_OVERRIDE_NONE) {
            sbuf.append(" meteredOverride ").append(meteredOverride).append("\n");
        }
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
        sbuf.append(" lcuid=" + lastConnectUid);
        sbuf.append(" userApproved=" + userApprovedAsString(userApproved));
        sbuf.append(" noInternetAccessExpected=" + noInternetAccessExpected);
        sbuf.append(" ");

        if (this.lastConnected != 0) {
            sbuf.append('\n');
            sbuf.append("lastConnected: ").append(TimeUtils.logTimeOfDay(this.lastConnected));
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

    /** {@hide} */
    @UnsupportedAppUsage
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

    /** @hide **/
    public static String userApprovedAsString(int userApproved) {
        switch (userApproved) {
            case USER_APPROVED:
                return "USER_APPROVED";
            case USER_BANNED:
                return "USER_BANNED";
            case USER_UNSPECIFIED:
                return "USER_UNSPECIFIED";
            default:
                return "INVALID";
        }
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
            if (allowedKeyManagement.get(KeyMgmt.OSEN)) {
                keyMgmt = KeyMgmt.strings[KeyMgmt.OSEN];
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
    @UnsupportedAppUsage
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
        } else if (providerFriendlyName != null) {
            key = FQDN + KeyMgmt.strings[KeyMgmt.WPA_EAP];
            if (!shared) {
                key += "-" + Integer.toString(UserHandle.getUserId(creatorUid));
            }
        } else {
            if (allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                key = SSID + KeyMgmt.strings[KeyMgmt.WPA_PSK];
            } else if (allowedKeyManagement.get(KeyMgmt.WPA_EAP) ||
                    allowedKeyManagement.get(KeyMgmt.IEEE8021X)) {
                key = SSID + KeyMgmt.strings[KeyMgmt.WPA_EAP];
            } else if (wepKeys[0] != null) {
                key = SSID + "WEP";
            } else {
                key = SSID + KeyMgmt.strings[KeyMgmt.NONE];
            }
            if (!shared) {
                key += "-" + Integer.toString(UserHandle.getUserId(creatorUid));
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

    /** @hide */
    @UnsupportedAppUsage
    public IpConfiguration getIpConfiguration() {
        return mIpConfiguration;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setIpConfiguration(IpConfiguration ipConfiguration) {
        if (ipConfiguration == null) ipConfiguration = new IpConfiguration();
        mIpConfiguration = ipConfiguration;
    }

    /** @hide */
    @UnsupportedAppUsage
    public StaticIpConfiguration getStaticIpConfiguration() {
        return mIpConfiguration.getStaticIpConfiguration();
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setStaticIpConfiguration(StaticIpConfiguration staticIpConfiguration) {
        mIpConfiguration.setStaticIpConfiguration(staticIpConfiguration);
    }

    /** @hide */
    @UnsupportedAppUsage
    public IpConfiguration.IpAssignment getIpAssignment() {
        return mIpConfiguration.ipAssignment;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setIpAssignment(IpConfiguration.IpAssignment ipAssignment) {
        mIpConfiguration.ipAssignment = ipAssignment;
    }

    /** @hide */
    @UnsupportedAppUsage
    public IpConfiguration.ProxySettings getProxySettings() {
        return mIpConfiguration.proxySettings;
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setProxySettings(IpConfiguration.ProxySettings proxySettings) {
        mIpConfiguration.proxySettings = proxySettings;
    }

    /**
     * Returns the HTTP proxy used by this object.
     * @return a {@link ProxyInfo httpProxy} representing the proxy specified by this
     *                  WifiConfiguration, or {@code null} if no proxy is specified.
     */
    public ProxyInfo getHttpProxy() {
        if (mIpConfiguration.proxySettings == IpConfiguration.ProxySettings.NONE) {
            return null;
        }
        return new ProxyInfo(mIpConfiguration.httpProxy);
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
            httpProxyCopy = new ProxyInfo(httpProxy.getPacFileUrl(), httpProxy.getPort());
        } else {
            proxySettingCopy = IpConfiguration.ProxySettings.STATIC;
            // Construct a new HTTP Proxy
            httpProxyCopy = new ProxyInfo(httpProxy.getHost(), httpProxy.getPort(),
                    httpProxy.getExclusionListAsString());
        }
        if (!httpProxyCopy.isValid()) {
            throw new IllegalArgumentException("Invalid ProxyInfo: " + httpProxyCopy.toString());
        }
        mIpConfiguration.setProxySettings(proxySettingCopy);
        mIpConfiguration.setHttpProxy(httpProxyCopy);
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setProxy(ProxySettings settings, ProxyInfo proxy) {
        mIpConfiguration.proxySettings = settings;
        mIpConfiguration.httpProxy = proxy;
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

    /** copy constructor {@hide} */
    @UnsupportedAppUsage
    public WifiConfiguration(WifiConfiguration source) {
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
            enterpriseConfig = new WifiEnterpriseConfig(source.enterpriseConfig);

            defaultGwMacAddress = source.defaultGwMacAddress;

            mIpConfiguration = new IpConfiguration(source.mIpConfiguration);

            if ((source.linkedConfigurations != null)
                    && (source.linkedConfigurations.size() > 0)) {
                linkedConfigurations = new HashMap<String, Integer>();
                linkedConfigurations.putAll(source.linkedConfigurations);
            }
            mCachedConfigKey = null; //force null configKey
            selfAdded = source.selfAdded;
            validatedInternetAccess = source.validatedInternetAccess;
            isLegacyPasspointConfig = source.isLegacyPasspointConfig;
            ephemeral = source.ephemeral;
            meteredHint = source.meteredHint;
            meteredOverride = source.meteredOverride;
            useExternalScores = source.useExternalScores;

            didSelfAdd = source.didSelfAdd;
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
            userApproved = source.userApproved;
            numNoInternetAccessReports = source.numNoInternetAccessReports;
            noInternetAccessExpected = source.noInternetAccessExpected;
            creationTime = source.creationTime;
            updateTime = source.updateTime;
            shared = source.shared;
            recentFailure.setAssociationStatus(source.recentFailure.getAssociationStatus());
            mRandomizedMacAddress = source.mRandomizedMacAddress;
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
        for (String wepKey : wepKeys) {
            dest.writeString(wepKey);
        }
        dest.writeInt(wepTxKeyIndex);
        dest.writeInt(priority);
        dest.writeInt(hiddenSSID ? 1 : 0);
        dest.writeInt(requirePMF ? 1 : 0);
        dest.writeString(updateIdentifier);

        writeBitSet(dest, allowedKeyManagement);
        writeBitSet(dest, allowedProtocols);
        writeBitSet(dest, allowedAuthAlgorithms);
        writeBitSet(dest, allowedPairwiseCiphers);
        writeBitSet(dest, allowedGroupCiphers);

        dest.writeParcelable(enterpriseConfig, flags);

        dest.writeParcelable(mIpConfiguration, flags);
        dest.writeString(dhcpServer);
        dest.writeString(defaultGwMacAddress);
        dest.writeInt(selfAdded ? 1 : 0);
        dest.writeInt(didSelfAdd ? 1 : 0);
        dest.writeInt(validatedInternetAccess ? 1 : 0);
        dest.writeInt(isLegacyPasspointConfig ? 1 : 0);
        dest.writeInt(ephemeral ? 1 : 0);
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
        dest.writeInt(userApproved);
        dest.writeInt(numNoInternetAccessReports);
        dest.writeInt(noInternetAccessExpected ? 1 : 0);
        dest.writeInt(shared ? 1 : 0);
        dest.writeString(mPasspointManagementObjectTree);
        dest.writeInt(recentFailure.getAssociationStatus());
        dest.writeParcelable(mRandomizedMacAddress, flags);
    }

    /** Implement the Parcelable interface {@hide} */
    @UnsupportedAppUsage
    public static final Creator<WifiConfiguration> CREATOR =
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
                for (int i = 0; i < config.wepKeys.length; i++) {
                    config.wepKeys[i] = in.readString();
                }
                config.wepTxKeyIndex = in.readInt();
                config.priority = in.readInt();
                config.hiddenSSID = in.readInt() != 0;
                config.requirePMF = in.readInt() != 0;
                config.updateIdentifier = in.readString();

                config.allowedKeyManagement   = readBitSet(in);
                config.allowedProtocols       = readBitSet(in);
                config.allowedAuthAlgorithms  = readBitSet(in);
                config.allowedPairwiseCiphers = readBitSet(in);
                config.allowedGroupCiphers    = readBitSet(in);

                config.enterpriseConfig = in.readParcelable(null);
                config.setIpConfiguration(in.readParcelable(null));
                config.dhcpServer = in.readString();
                config.defaultGwMacAddress = in.readString();
                config.selfAdded = in.readInt() != 0;
                config.didSelfAdd = in.readInt() != 0;
                config.validatedInternetAccess = in.readInt() != 0;
                config.isLegacyPasspointConfig = in.readInt() != 0;
                config.ephemeral = in.readInt() != 0;
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
                config.userApproved = in.readInt();
                config.numNoInternetAccessReports = in.readInt();
                config.noInternetAccessExpected = in.readInt() != 0;
                config.shared = in.readInt() != 0;
                config.mPasspointManagementObjectTree = in.readString();
                config.recentFailure.setAssociationStatus(in.readInt());
                config.mRandomizedMacAddress = in.readParcelable(null);
                return config;
            }

            public WifiConfiguration[] newArray(int size) {
                return new WifiConfiguration[size];
            }
        };

    /**
     * Serializes the object for backup
     * @hide
     */
    public byte[] getBytesForBackup() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(BACKUP_VERSION);
        BackupUtils.writeString(out, SSID);
        out.writeInt(apBand);
        out.writeInt(apChannel);
        BackupUtils.writeString(out, preSharedKey);
        out.writeInt(getAuthType());
        return baos.toByteArray();
    }

    /**
     * Deserializes a byte array into the WiFiConfiguration Object
     * @hide
     */
    public static WifiConfiguration getWifiConfigFromBackup(DataInputStream in) throws IOException,
            BackupUtils.BadVersionException {
        WifiConfiguration config = new WifiConfiguration();
        int version = in.readInt();
        if (version < 1 || version > BACKUP_VERSION) {
            throw new BackupUtils.BadVersionException("Unknown Backup Serialization Version");
        }

        if (version == 1) return null; // Version 1 is a bad dataset.

        config.SSID = BackupUtils.readString(in);
        config.apBand = in.readInt();
        config.apChannel = in.readInt();
        config.preSharedKey = BackupUtils.readString(in);
        config.allowedKeyManagement.set(in.readInt());
        return config;
    }
}
