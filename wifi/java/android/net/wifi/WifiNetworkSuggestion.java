/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The Network Suggestion object is used to provide a Wi-Fi network for consideration when
 * auto-connecting to networks. Apps cannot directly create this object, they must use
 * {@link WifiNetworkSuggestion.Builder#build()} to obtain an instance of this object.
 *<p>
 * Apps can provide a list of such networks to the platform using
 * {@link WifiManager#addNetworkSuggestions(List)}.
 */
public final class WifiNetworkSuggestion implements Parcelable {

    /**
     * Builder used to create {@link WifiNetworkSuggestion} objects.
     */
    public static class Builder {
        private static final int UNASSIGNED_PRIORITY = -1;

        /**
         * SSID of the network.
         */
        private String mSsid;
        /**
         * Optional BSSID within the network.
         */
        private MacAddress mBssid;
        /**
         * Whether this is an OWE network or not.
         */
        private boolean mIsEnhancedOpen;
        /**
         * Pre-shared key for use with WPA-PSK networks.
         */
        private @Nullable String mWpa2PskPassphrase;
        /**
         * Pre-shared key for use with WPA3-SAE networks.
         */
        private @Nullable String mWpa3SaePassphrase;
        /**
         * The enterprise configuration details specifying the EAP method,
         * certificates and other settings associated with the WPA-EAP networks.
         */
        private @Nullable WifiEnterpriseConfig mWpa2EnterpriseConfig;
        /**
         * The enterprise configuration details specifying the EAP method,
         * certificates and other settings associated with the SuiteB networks.
         */
        private @Nullable WifiEnterpriseConfig mWpa3EnterpriseConfig;
        /**
         * This is a network that does not broadcast its SSID, so an
         * SSID-specific probe request must be used for scans.
         */
        private boolean mIsHiddenSSID;
        /**
         * Whether app needs to log in to captive portal to obtain Internet access.
         */
        private boolean mIsAppInteractionRequired;
        /**
         * Whether user needs to log in to captive portal to obtain Internet access.
         */
        private boolean mIsUserInteractionRequired;
        /**
         * Whether this network is metered or not.
         */
        private boolean mIsMetered;
        /**
         * Priority of this network among other network suggestions provided by the app.
         * The lower the number, the higher the priority (i.e value of 0 = highest priority).
         */
        private int mPriority;

        public Builder() {
            mSsid = null;
            mBssid =  null;
            mIsEnhancedOpen = false;
            mWpa2PskPassphrase = null;
            mWpa3SaePassphrase = null;
            mWpa2EnterpriseConfig = null;
            mWpa3EnterpriseConfig = null;
            mIsHiddenSSID = false;
            mIsAppInteractionRequired = false;
            mIsUserInteractionRequired = false;
            mIsMetered = false;
            mPriority = UNASSIGNED_PRIORITY;
        }

        /**
         * Set the unicode SSID for the network.
         * <p>
         * <li>Overrides any previous value set using {@link #setSsid(String)}.</li>
         *
         * @param ssid The SSID of the network. It must be valid Unicode.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the SSID is not valid unicode.
         */
        public Builder setSsid(@NonNull String ssid) {
            checkNotNull(ssid);
            final CharsetEncoder unicodeEncoder = StandardCharsets.UTF_8.newEncoder();
            if (!unicodeEncoder.canEncode(ssid)) {
                throw new IllegalArgumentException("SSID is not a valid unicode string");
            }
            mSsid = new String(ssid);
            return this;
        }

        /**
         * Set the BSSID to use for filtering networks from scan results. Will only match network
         * whose BSSID is identical to the specified value.
         * <p>
         * <li Sets a specific BSSID for the network suggestion. If set, only the specified BSSID
         * with the specified SSID will be considered for connection.
         * <li>If set, only the specified BSSID with the specified SSID will be considered for
         * connection.</li>
         * <li>If not set, all BSSIDs with the specified SSID will be considered for connection.
         * </li>
         * <li>Overrides any previous value set using {@link #setBssid(MacAddress)}.</li>
         *
         * @param bssid BSSID of the network.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setBssid(@NonNull MacAddress bssid) {
            checkNotNull(bssid);
            mBssid = MacAddress.fromBytes(bssid.toByteArray());
            return this;
        }

        /**
         * Specifies whether this represents an Enhanced Open (OWE) network.
         *
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setIsEnhancedOpen() {
            mIsEnhancedOpen = true;
            return this;
        }

        /**
         * Set the ASCII WPA2 passphrase for this network. Needed for authenticating to
         * WPA2-PSK networks.
         *
         * @param passphrase passphrase of the network.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the passphrase is not ASCII encodable.
         */
        public Builder setWpa2Passphrase(@NonNull String passphrase) {
            checkNotNull(passphrase);
            final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
            if (!asciiEncoder.canEncode(passphrase)) {
                throw new IllegalArgumentException("passphrase not ASCII encodable");
            }
            mWpa2PskPassphrase = passphrase;
            return this;
        }

        /**
         * Set the ASCII WPA3 passphrase for this network. Needed for authenticating to WPA3-SAE
         * networks.
         *
         * @param passphrase passphrase of the network.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the passphrase is not ASCII encodable.
         */
        public Builder setWpa3Passphrase(@NonNull String passphrase) {
            checkNotNull(passphrase);
            final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
            if (!asciiEncoder.canEncode(passphrase)) {
                throw new IllegalArgumentException("passphrase not ASCII encodable");
            }
            mWpa3SaePassphrase = passphrase;
            return this;
        }

        /**
         * Set the associated enterprise configuration for this network. Needed for authenticating
         * to WPA2-EAP networks. See {@link WifiEnterpriseConfig} for description.
         *
         * @param enterpriseConfig Instance of {@link WifiEnterpriseConfig}.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setWpa2EnterpriseConfig(
                @NonNull WifiEnterpriseConfig enterpriseConfig) {
            checkNotNull(enterpriseConfig);
            mWpa2EnterpriseConfig = new WifiEnterpriseConfig(enterpriseConfig);
            return this;
        }

        /**
         * Set the associated enterprise configuration for this network. Needed for authenticating
         * to WPA3-SuiteB networks. See {@link WifiEnterpriseConfig} for description.
         *
         * @param enterpriseConfig Instance of {@link WifiEnterpriseConfig}.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setWpa3EnterpriseConfig(
                @NonNull WifiEnterpriseConfig enterpriseConfig) {
            checkNotNull(enterpriseConfig);
            mWpa3EnterpriseConfig = new WifiEnterpriseConfig(enterpriseConfig);
            return this;
        }

        /**
         * Specifies whether this represents a hidden network.
         * <p>
         * <li>If not set, defaults to false (i.e not a hidden network).</li>
         *
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setIsHiddenSsid() {
            mIsHiddenSSID = true;
            return this;
        }

        /**
         * Specifies whether the app needs to log in to a captive portal to obtain Internet access.
         * <p>
         * This will dictate if the directed broadcast
         * {@link WifiManager#ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} will be sent to the
         * app after successfully connecting to the network.
         * Use this for captive portal type networks where the app needs to authenticate the user
         * before the device can access the network.
         * <p>
         * <li>If not set, defaults to false (i.e no app interaction required).</li>
         *
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setIsAppInteractionRequired() {
            mIsAppInteractionRequired = true;
            return this;
        }

        /**
         * Specifies whether the user needs to log in to a captive portal to obtain Internet access.
         * <p>
         * <li>If not set, defaults to false (i.e no user interaction required).</li>
         *
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setIsUserInteractionRequired() {
            mIsUserInteractionRequired = true;
            return this;
        }

        /**
         * Specify the priority of this network among other network suggestions provided by the same
         * app (priorities have no impact on suggestions by different apps). The lower the number,
         * the higher the priority (i.e value of 0 = highest priority).
         * <p>
         * <li>If not set, defaults to -1 (i.e unassigned priority).</li>
         *
         * @param priority Integer number representing the priority among suggestions by the app.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the priority value is negative.
         */
        public Builder setPriority(int priority) {
            if (priority < 0) {
                throw new IllegalArgumentException("Invalid priority value " + priority);
            }
            mPriority = priority;
            return this;
        }

        /**
         * Specifies whether this network is metered.
         * <p>
         * <li>If not set, defaults to false (i.e not metered).</li>
         *
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public Builder setIsMetered() {
            mIsMetered = true;
            return this;
        }

        /**
         * Set defaults for the various low level credential type fields in the newly created
         * WifiConfiguration object.
         *
         * See {@link com.android.server.wifi.WifiConfigManager#setDefaultsInWifiConfiguration(
         * WifiConfiguration)}.
         *
         * @param configuration provided WifiConfiguration object.
         */
        private static void setDefaultsInWifiConfiguration(
                @NonNull WifiConfiguration configuration) {
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }

        private void setSecurityParamsInWifiConfiguration(
                @NonNull WifiConfiguration configuration) {
            if (!TextUtils.isEmpty(mWpa2PskPassphrase)) { // WPA-PSK network.
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                // WifiConfiguration.preSharedKey needs quotes around ASCII password.
                configuration.preSharedKey = "\"" + mWpa2PskPassphrase + "\"";
            } else if (!TextUtils.isEmpty(mWpa3SaePassphrase)) { // WPA3-SAE network.
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
                // PMF mandatory for SAE.
                configuration.requirePMF = true;
                // WifiConfiguration.preSharedKey needs quotes around ASCII password.
                configuration.preSharedKey = "\"" + mWpa3SaePassphrase + "\"";
            } else if (mWpa2EnterpriseConfig != null) { // WPA-EAP network
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
                configuration.enterpriseConfig = mWpa2EnterpriseConfig;
            } else if (mWpa3EnterpriseConfig != null) { // WPA3-SuiteB network
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SUITE_B_192);
                configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                // TODO (b/113878056): Verify these params once we verify SuiteB configuration.
                configuration.allowedGroupManagementCiphers.set(
                        WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256);
                configuration.allowedSuiteBCiphers.set(
                        WifiConfiguration.SuiteBCipher.ECDHE_ECDSA);
                configuration.allowedSuiteBCiphers.set(
                        WifiConfiguration.SuiteBCipher.ECDHE_RSA);
                configuration.requirePMF = true;
                configuration.enterpriseConfig = mWpa3EnterpriseConfig;
            } else if (mIsEnhancedOpen) { // OWE network
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                // PMF mandatory.
                configuration.requirePMF = true;
            } else { // Open network
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }
        }

        /**
         * Helper method to build WifiConfiguration object from the builder.
         * @return Instance of {@link WifiConfiguration}.
         */
        private WifiConfiguration buildWifiConfiguration() {
            final WifiConfiguration wifiConfiguration = new WifiConfiguration();
            setDefaultsInWifiConfiguration(wifiConfiguration);
            // WifiConfiguration.SSID needs quotes around unicode SSID.
            wifiConfiguration.SSID = "\"" + mSsid + "\"";
            if (mBssid != null) {
                wifiConfiguration.BSSID = mBssid.toString();
            }

            setSecurityParamsInWifiConfiguration(wifiConfiguration);

            wifiConfiguration.hiddenSSID = mIsHiddenSSID;
            wifiConfiguration.priority = mPriority;
            wifiConfiguration.meteredOverride =
                    mIsMetered ? WifiConfiguration.METERED_OVERRIDE_METERED
                            : WifiConfiguration.METERED_OVERRIDE_NONE;
            return wifiConfiguration;
        }

        private void validateSecurityParams() {
            int numSecurityTypes = 0;
            numSecurityTypes += mIsEnhancedOpen ? 1 : 0;
            numSecurityTypes += !TextUtils.isEmpty(mWpa2PskPassphrase) ? 1 : 0;
            numSecurityTypes += !TextUtils.isEmpty(mWpa3SaePassphrase) ? 1 : 0;
            numSecurityTypes += mWpa2EnterpriseConfig != null ? 1 : 0;
            numSecurityTypes += mWpa3EnterpriseConfig != null ? 1 : 0;
            if (numSecurityTypes > 1) {
                throw new IllegalStateException("only one of setIsEnhancedOpen, setWpa2Passphrase,"
                        + "setWpa3Passphrase, setWpa2EnterpriseConfig or setWpa3EnterpriseConfig"
                        + " can be invoked for network specifier");
            }
        }

        /**
         * Create a network suggestion object use in
         * {@link WifiManager#addNetworkSuggestions(List)}.
         *
         * See {@link WifiNetworkSuggestion}.
         *<p>
         * Note: Apps can set a combination of SSID using {@link #setSsid(String)} and BSSID
         * using {@link #setBssid(MacAddress)} to provide more fine grained network suggestions to
         * the platform.
         * </p>
         *
         * For example:
         * To provide credentials for one open, one WPA2 and one WPA3 network with their
         * corresponding SSID's:
         * {@code
         * final WifiNetworkSuggestion suggestion1 =
         *      new Builder()
         *      .setSsid("test111111")
         *      .build()
         * final WifiNetworkSuggestion suggestion2 =
         *      new Builder()
         *      .setSsid("test222222")
         *      .setWpa2Passphrase("test123456")
         *      .build()
         * final WifiNetworkSuggestion suggestion3 =
         *      new Builder()
         *      .setSsid("test333333")
         *      .setWpa3Passphrase("test6789")
         *      .build()
         * final List<WifiNetworkSuggestion> suggestionsList =
         *      new ArrayList<WifiNetworkSuggestion> {{
         *          add(suggestion1);
         *          add(suggestion2);
         *          add(suggestion3);
         *      }};
         * final WifiManager wifiManager =
         *      context.getSystemService(Context.WIFI_SERVICE);
         * wifiManager.addNetworkSuggestions(suggestionsList);
         * ...
         * }
         *
         * @return Instance of {@link WifiNetworkSuggestion}.
         * @throws IllegalStateException on invalid params set.
         */
        public WifiNetworkSuggestion build() {
            if (mSsid == null) {
                throw new IllegalStateException("setSsid should be invoked for suggestion");
            }
            if (TextUtils.isEmpty(mSsid)) {
                throw new IllegalStateException("invalid ssid for suggestion");
            }
            if (mBssid != null
                    && (mBssid.equals(MacAddress.BROADCAST_ADDRESS)
                    || mBssid.equals(MacAddress.ALL_ZEROS_ADDRESS))) {
                throw new IllegalStateException("invalid bssid for suggestion");
            }
            validateSecurityParams();

            return new WifiNetworkSuggestion(
                    buildWifiConfiguration(),
                    mIsAppInteractionRequired,
                    mIsUserInteractionRequired,
                    Process.myUid(),
                    ActivityThread.currentApplication().getApplicationContext().getOpPackageName());
        }
    }

    /**
     * Network configuration for the provided network.
     * @hide
     */
    public final WifiConfiguration wifiConfiguration;

    /**
     * Whether app needs to log in to captive portal to obtain Internet access.
     * @hide
     */
    public final boolean isAppInteractionRequired;

    /**
     * Whether user needs to log in to captive portal to obtain Internet access.
     * @hide
     */
    public final boolean isUserInteractionRequired;

    /**
     * The UID of the process initializing this network suggestion.
     * @hide
     */
    public final int suggestorUid;

    /**
     * The package name of the process initializing this network suggestion.
     * @hide
     */
    public final String suggestorPackageName;

    /** @hide */
    public WifiNetworkSuggestion() {
        this.wifiConfiguration = null;
        this.isAppInteractionRequired = false;
        this.isUserInteractionRequired = false;
        this.suggestorUid = -1;
        this.suggestorPackageName = null;
    }

    /** @hide */
    public WifiNetworkSuggestion(@NonNull WifiConfiguration wifiConfiguration,
                                 boolean isAppInteractionRequired,
                                 boolean isUserInteractionRequired,
                                 int suggestorUid, @NonNull String suggestorPackageName) {
        checkNotNull(wifiConfiguration);
        checkNotNull(suggestorPackageName);

        this.wifiConfiguration = wifiConfiguration;
        this.isAppInteractionRequired = isAppInteractionRequired;
        this.isUserInteractionRequired = isUserInteractionRequired;
        this.suggestorUid = suggestorUid;
        this.suggestorPackageName = suggestorPackageName;
    }

    public static final Creator<WifiNetworkSuggestion> CREATOR =
            new Creator<WifiNetworkSuggestion>() {
                @Override
                public WifiNetworkSuggestion createFromParcel(Parcel in) {
                    return new WifiNetworkSuggestion(
                            in.readParcelable(null), // wifiConfiguration
                            in.readBoolean(), // isAppInteractionRequired
                            in.readBoolean(), // isUserInteractionRequired
                            in.readInt(), // suggestorUid
                            in.readString() // suggestorPackageName
                    );
                }

                @Override
                public WifiNetworkSuggestion[] newArray(int size) {
                    return new WifiNetworkSuggestion[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(wifiConfiguration, flags);
        dest.writeBoolean(isAppInteractionRequired);
        dest.writeBoolean(isUserInteractionRequired);
        dest.writeInt(suggestorUid);
        dest.writeString(suggestorPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wifiConfiguration.SSID, wifiConfiguration.BSSID,
                wifiConfiguration.allowedKeyManagement, suggestorUid, suggestorPackageName);
    }

    /**
     * Equals for network suggestions.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WifiNetworkSuggestion)) {
            return false;
        }
        WifiNetworkSuggestion lhs = (WifiNetworkSuggestion) obj;
        return Objects.equals(this.wifiConfiguration.SSID, lhs.wifiConfiguration.SSID)
                && Objects.equals(this.wifiConfiguration.BSSID, lhs.wifiConfiguration.BSSID)
                && Objects.equals(this.wifiConfiguration.allowedKeyManagement,
                                  lhs.wifiConfiguration.allowedKeyManagement)
                && suggestorUid == lhs.suggestorUid
                && TextUtils.equals(suggestorPackageName, lhs.suggestorPackageName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WifiNetworkSuggestion [")
                .append(", SSID=").append(wifiConfiguration.SSID)
                .append(", BSSID=").append(wifiConfiguration.BSSID)
                .append(", isAppInteractionRequired=").append(isAppInteractionRequired)
                .append(", isUserInteractionRequired=").append(isUserInteractionRequired)
                .append(", suggestorUid=").append(suggestorUid)
                .append(", suggestorPackageName=").append(suggestorPackageName)
                .append("]");
        return sb.toString();
    }
}
