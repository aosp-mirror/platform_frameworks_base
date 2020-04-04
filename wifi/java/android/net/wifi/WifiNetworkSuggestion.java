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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.net.MacAddress;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.TelephonyManager;
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
    public static final class Builder {
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
         * The passpoint config for use with Hotspot 2.0 network
         */
        private @Nullable PasspointConfiguration mPasspointConfiguration;
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

        /**
         * The carrier ID identifies the operator who provides this network configuration.
         *    see {@link TelephonyManager#getSimCarrierId()}
         */
        private int mCarrierId;

        /**
         * Whether this network is shared credential with user to allow user manually connect.
         */
        private boolean mIsSharedWithUser;

        /**
         * Whether the setCredentialSharedWithUser have been called.
         */
        private boolean mIsSharedWithUserSet;

        /**
         * Whether this network is initialized with auto-join enabled (the default) or not.
         */
        private boolean mIsInitialAutojoinEnabled;

        /**
         * Pre-shared key for use with WAPI-PSK networks.
         */
        private @Nullable String mWapiPskPassphrase;

        /**
         * The enterprise configuration details specifying the EAP method,
         * certificates and other settings associated with the WAPI networks.
         */
        private @Nullable WifiEnterpriseConfig mWapiEnterpriseConfig;

        /**
         * Whether this network will be brought up as untrusted (TRUSTED capability bit removed).
         */
        private boolean mIsNetworkUntrusted;

        public Builder() {
            mSsid = null;
            mBssid =  null;
            mIsEnhancedOpen = false;
            mWpa2PskPassphrase = null;
            mWpa3SaePassphrase = null;
            mWpa2EnterpriseConfig = null;
            mWpa3EnterpriseConfig = null;
            mPasspointConfiguration = null;
            mIsHiddenSSID = false;
            mIsAppInteractionRequired = false;
            mIsUserInteractionRequired = false;
            mIsMetered = false;
            mIsSharedWithUser = true;
            mIsSharedWithUserSet = false;
            mIsInitialAutojoinEnabled = true;
            mPriority = UNASSIGNED_PRIORITY;
            mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
            mWapiPskPassphrase = null;
            mWapiEnterpriseConfig = null;
            mIsNetworkUntrusted = false;
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
        public @NonNull Builder setSsid(@NonNull String ssid) {
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
        public @NonNull Builder setBssid(@NonNull MacAddress bssid) {
            checkNotNull(bssid);
            mBssid = MacAddress.fromBytes(bssid.toByteArray());
            return this;
        }

        /**
         * Specifies whether this represents an Enhanced Open (OWE) network.
         *
         * @param isEnhancedOpen {@code true} to indicate that the network used enhanced open,
         *                       {@code false} otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsEnhancedOpen(boolean isEnhancedOpen) {
            mIsEnhancedOpen = isEnhancedOpen;
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
        public @NonNull Builder setWpa2Passphrase(@NonNull String passphrase) {
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
        public @NonNull Builder setWpa3Passphrase(@NonNull String passphrase) {
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
        public @NonNull Builder setWpa2EnterpriseConfig(
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
        public @NonNull Builder setWpa3EnterpriseConfig(
                @NonNull WifiEnterpriseConfig enterpriseConfig) {
            checkNotNull(enterpriseConfig);
            mWpa3EnterpriseConfig = new WifiEnterpriseConfig(enterpriseConfig);
            return this;
        }

        /**
         * Set the associated Passpoint configuration for this network. Needed for authenticating
         * to Hotspot 2.0 networks. See {@link PasspointConfiguration} for description.
         *
         * @param passpointConfig Instance of {@link PasspointConfiguration}.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if passpoint configuration is invalid.
         */
        public @NonNull Builder setPasspointConfig(
                @NonNull PasspointConfiguration passpointConfig) {
            checkNotNull(passpointConfig);
            if (!passpointConfig.validate()) {
                throw new IllegalArgumentException("Passpoint configuration is invalid");
            }
            mPasspointConfiguration = passpointConfig;
            return this;
        }

        /**
         * Set the carrier ID of the network operator. The carrier ID associates a Suggested
         * network with a specific carrier (and therefore SIM). The carrier ID must be provided
         * for any network which uses the SIM-based authentication: e.g. EAP-SIM, EAP-AKA,
         * EAP-AKA', and EAP-PEAP with SIM-based phase 2 authentication.
         * @param carrierId see {@link TelephonyManager#getSimCarrierId()}.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.NETWORK_CARRIER_PROVISIONING)
        public @NonNull Builder setCarrierId(int carrierId) {
            mCarrierId = carrierId;
            return this;
        }

        /**
         * Set the ASCII WAPI passphrase for this network. Needed for authenticating to
         * WAPI-PSK networks.
         *
         * @param passphrase passphrase of the network.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the passphrase is not ASCII encodable.
         *
         */
        public @NonNull Builder setWapiPassphrase(@NonNull String passphrase) {
            checkNotNull(passphrase);
            final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
            if (!asciiEncoder.canEncode(passphrase)) {
                throw new IllegalArgumentException("passphrase not ASCII encodable");
            }
            mWapiPskPassphrase = passphrase;
            return this;
        }

        /**
         * Set the associated enterprise configuration for this network. Needed for authenticating
         * to WAPI-CERT networks. See {@link WifiEnterpriseConfig} for description.
         *
         * @param enterpriseConfig Instance of {@link WifiEnterpriseConfig}.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setWapiEnterpriseConfig(
                @NonNull WifiEnterpriseConfig enterpriseConfig) {
            checkNotNull(enterpriseConfig);
            mWapiEnterpriseConfig = new WifiEnterpriseConfig(enterpriseConfig);
            return this;
        }

        /**
         * Specifies whether this represents a hidden network.
         * <p>
         * <li>If not set, defaults to false (i.e not a hidden network).</li>
         *
         * @param isHiddenSsid {@code true} to indicate that the network is hidden, {@code false}
         *                     otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsHiddenSsid(boolean isHiddenSsid) {
            mIsHiddenSSID = isHiddenSsid;
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
         * @param isAppInteractionRequired {@code true} to indicate that app interaction is
         *                                 required, {@code false} otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsAppInteractionRequired(boolean isAppInteractionRequired) {
            mIsAppInteractionRequired = isAppInteractionRequired;
            return this;
        }

        /**
         * Specifies whether the user needs to log in to a captive portal to obtain Internet access.
         * <p>
         * <li>If not set, defaults to false (i.e no user interaction required).</li>
         *
         * @param isUserInteractionRequired {@code true} to indicate that user interaction is
         *                                  required, {@code false} otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsUserInteractionRequired(boolean isUserInteractionRequired) {
            mIsUserInteractionRequired = isUserInteractionRequired;
            return this;
        }

        /**
         * Specify the priority of this network among other network suggestions provided by the same
         * app (priorities have no impact on suggestions by different apps). The higher the number,
         * the higher the priority (i.e value of 0 = lowest priority).
         * <p>
         * <li>If not set, defaults a lower priority than any assigned priority.</li>
         *
         * @param priority Integer number representing the priority among suggestions by the app.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         * @throws IllegalArgumentException if the priority value is negative.
         */
        public @NonNull Builder setPriority(@IntRange(from = 0) int priority) {
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
         * @param isMetered {@code true} to indicate that the network is metered, {@code false}
         *                  otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsMetered(boolean isMetered) {
            mIsMetered = isMetered;
            return this;
        }

        /**
         * Specifies whether the network credentials provided with this suggestion can be used by
         * the user to explicitly (manually) connect to this network. If true this network will
         * appear in the Wi-Fi Picker (in Settings) and the user will be able to select and connect
         * to it with the provided credentials. If false, the user will need to enter network
         * credentials and the resulting configuration will become a user saved network.
         * <p>
         * <li>Note: Only valid for secure (non-open) networks.
         * <li>If not set, defaults to true (i.e. allow user to manually connect) for secure
         * networks and false for open networks.</li>
         *
         * @param isShared {@code true} to indicate that the credentials may be used by the user to
         *                              manually connect to the network, {@code false} otherwise.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setCredentialSharedWithUser(boolean isShared) {
            mIsSharedWithUser = isShared;
            mIsSharedWithUserSet = true;
            return this;
        }

        /**
         * Specifies whether the suggestion is created with auto-join enabled or disabled. The
         * user may modify the auto-join configuration of a suggestion directly once the device
         * associates to the network.
         * <p>
         * If auto-join is initialized as disabled the user may still be able to manually connect
         * to the network. Therefore, disabling auto-join only makes sense if
         * {@link #setCredentialSharedWithUser(boolean)} is set to true (the default) which
         * itself implies a secure (non-open) network.
         * <p>
         * If not set, defaults to true (i.e. auto-join is initialized as enabled).
         *
         * @param enabled true for initializing with auto-join enabled (the default), false to
         *                initializing with auto-join disabled.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setIsInitialAutojoinEnabled(boolean enabled) {
            mIsInitialAutojoinEnabled = enabled;
            return this;
        }

        /**
         * Specifies whether the system will bring up the network (if selected) as untrusted. An
         * untrusted network has its {@link android.net.NetworkCapabilities#NET_CAPABILITY_TRUSTED}
         * capability removed. The Wi-Fi network selection process may use this information to
         * influence priority of the suggested network for Wi-Fi network selection (most likely to
         * reduce it). The connectivity service may use this information to influence the overall
         * network configuration of the device.
         * <p>
         * <li> An untrusted network's credentials may not be shared with the user using
         * {@link #setCredentialSharedWithUser(boolean)}.</li>
         * <li> If not set, defaults to false (i.e. network is trusted).</li>
         *
         * @param isUntrusted Boolean indicating whether the network should be brought up untrusted
         *                    (if true) or trusted (if false).
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setUntrusted(boolean isUntrusted) {
            mIsNetworkUntrusted = isUntrusted;
            return this;
        }

        private void setSecurityParamsInWifiConfiguration(
                @NonNull WifiConfiguration configuration) {
            if (!TextUtils.isEmpty(mWpa2PskPassphrase)) { // WPA-PSK network.
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                // WifiConfiguration.preSharedKey needs quotes around ASCII password.
                configuration.preSharedKey = "\"" + mWpa2PskPassphrase + "\"";
            } else if (!TextUtils.isEmpty(mWpa3SaePassphrase)) { // WPA3-SAE network.
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                // WifiConfiguration.preSharedKey needs quotes around ASCII password.
                configuration.preSharedKey = "\"" + mWpa3SaePassphrase + "\"";
            } else if (mWpa2EnterpriseConfig != null) { // WPA-EAP network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
                configuration.enterpriseConfig = mWpa2EnterpriseConfig;
            } else if (mWpa3EnterpriseConfig != null) { // WPA3-SuiteB network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B);
                configuration.enterpriseConfig = mWpa3EnterpriseConfig;
            } else if (mIsEnhancedOpen) { // OWE network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
            } else if (!TextUtils.isEmpty(mWapiPskPassphrase)) { // WAPI-PSK network.
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WAPI_PSK);
                // WifiConfiguration.preSharedKey needs quotes around ASCII password.
                configuration.preSharedKey = "\"" + mWapiPskPassphrase + "\"";
            } else if (mWapiEnterpriseConfig != null) { // WAPI-CERT network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WAPI_CERT);
                configuration.enterpriseConfig = mWapiEnterpriseConfig;
            } else { // Open network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
            }
        }

        /**
         * Helper method to build WifiConfiguration object from the builder.
         * @return Instance of {@link WifiConfiguration}.
         */
        private WifiConfiguration buildWifiConfiguration() {
            final WifiConfiguration wifiConfiguration = new WifiConfiguration();
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
                            : WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
            wifiConfiguration.carrierId = mCarrierId;
            wifiConfiguration.trusted = !mIsNetworkUntrusted;
            return wifiConfiguration;
        }

        private void validateSecurityParams() {
            int numSecurityTypes = 0;
            numSecurityTypes += mIsEnhancedOpen ? 1 : 0;
            numSecurityTypes += !TextUtils.isEmpty(mWpa2PskPassphrase) ? 1 : 0;
            numSecurityTypes += !TextUtils.isEmpty(mWpa3SaePassphrase) ? 1 : 0;
            numSecurityTypes += !TextUtils.isEmpty(mWapiPskPassphrase) ? 1 : 0;
            numSecurityTypes += mWpa2EnterpriseConfig != null ? 1 : 0;
            numSecurityTypes += mWpa3EnterpriseConfig != null ? 1 : 0;
            numSecurityTypes += mWapiEnterpriseConfig != null ? 1 : 0;
            numSecurityTypes += mPasspointConfiguration != null ? 1 : 0;
            if (numSecurityTypes > 1) {
                throw new IllegalStateException("only one of setIsEnhancedOpen, setWpa2Passphrase,"
                        + " setWpa3Passphrase, setWpa2EnterpriseConfig, setWpa3EnterpriseConfig"
                        + " setWapiPassphrase, setWapiCertSuite, setIsWapiCertSuiteAuto"
                        + " or setPasspointConfig can be invoked for network suggestion");
            }
        }

        private WifiConfiguration buildWifiConfigurationForPasspoint() {
            WifiConfiguration wifiConfiguration = new WifiConfiguration();
            wifiConfiguration.FQDN = mPasspointConfiguration.getHomeSp().getFqdn();
            wifiConfiguration.setPasspointUniqueId(mPasspointConfiguration.getUniqueId());
            wifiConfiguration.priority = mPriority;
            wifiConfiguration.meteredOverride =
                    mIsMetered ? WifiConfiguration.METERED_OVERRIDE_METERED
                            : WifiConfiguration.METERED_OVERRIDE_NONE;
            wifiConfiguration.trusted = !mIsNetworkUntrusted;
            mPasspointConfiguration.setCarrierId(mCarrierId);
            mPasspointConfiguration.setMeteredOverride(wifiConfiguration.meteredOverride);
            return wifiConfiguration;
        }

        /**
         * Create a network suggestion object for use in
         * {@link WifiManager#addNetworkSuggestions(List)}.
         *
         *<p class="note">
         * <b>Note:</b> Apps can set a combination of SSID using {@link #setSsid(String)} and BSSID
         * using {@link #setBssid(MacAddress)} to provide more fine grained network suggestions to
         * the platform.
         * </p>
         *
         * For example:
         * To provide credentials for one open, one WPA2, one WPA3 network with their
         * corresponding SSID's and one with Passpoint config:
         *
         * <pre>{@code
         * final WifiNetworkSuggestion suggestion1 =
         *      new Builder()
         *      .setSsid("test111111")
         *      .build();
         * final WifiNetworkSuggestion suggestion2 =
         *      new Builder()
         *      .setSsid("test222222")
         *      .setWpa2Passphrase("test123456")
         *      .build();
         * final WifiNetworkSuggestion suggestion3 =
         *      new Builder()
         *      .setSsid("test333333")
         *      .setWpa3Passphrase("test6789")
         *      .build();
         * final PasspointConfiguration passpointConfig= new PasspointConfiguration();
         * // configure passpointConfig to include a valid Passpoint configuration
         * final WifiNetworkSuggestion suggestion4 =
         *      new Builder()
         *      .setPasspointConfig(passpointConfig)
         *      .build();
         * final List<WifiNetworkSuggestion> suggestionsList =
         *      new ArrayList<WifiNetworkSuggestion> { {
         *          add(suggestion1);
         *          add(suggestion2);
         *          add(suggestion3);
         *          add(suggestion4);
         *      } };
         * final WifiManager wifiManager =
         *      context.getSystemService(Context.WIFI_SERVICE);
         * wifiManager.addNetworkSuggestions(suggestionsList);
         * // ...
         * }</pre>
         *
         * @return Instance of {@link WifiNetworkSuggestion}
         * @throws IllegalStateException on invalid params set
         * @see WifiNetworkSuggestion
         */
        public @NonNull WifiNetworkSuggestion build() {
            validateSecurityParams();
            WifiConfiguration wifiConfiguration;
            if (mPasspointConfiguration != null) {
                if (mSsid != null) {
                    throw new IllegalStateException("setSsid should not be invoked for suggestion "
                            + "with Passpoint configuration");
                }
                if (mIsHiddenSSID) {
                    throw new IllegalStateException("setIsHiddenSsid should not be invoked for "
                            + "suggestion with Passpoint configuration");
                }
                wifiConfiguration = buildWifiConfigurationForPasspoint();
            } else {
                if (mSsid == null) {
                    throw new IllegalStateException("setSsid should be invoked for suggestion");
                }
                if (TextUtils.isEmpty(mSsid)) {
                    throw new IllegalStateException("invalid ssid for suggestion");
                }
                if (mBssid != null
                        && (mBssid.equals(MacAddress.BROADCAST_ADDRESS)
                        || mBssid.equals(WifiManager.ALL_ZEROS_MAC_ADDRESS))) {
                    throw new IllegalStateException("invalid bssid for suggestion");
                }
                wifiConfiguration = buildWifiConfiguration();
                if (wifiConfiguration.isOpenNetwork()) {
                    if (mIsSharedWithUserSet && mIsSharedWithUser) {
                        throw new IllegalStateException("Open network should not be "
                                + "setCredentialSharedWithUser to true");
                    }
                    mIsSharedWithUser = false;
                }
            }
            if (!mIsSharedWithUser && !mIsInitialAutojoinEnabled) {
                throw new IllegalStateException("Should have not a network with both "
                        + "setCredentialSharedWithUser and "
                        + "setIsAutojoinEnabled set to false");
            }
            if (mIsNetworkUntrusted) {
                if (mIsSharedWithUserSet && mIsSharedWithUser) {
                    throw new IllegalStateException("Should not be both"
                            + "setCredentialSharedWithUser and +"
                            + "setIsNetworkAsUntrusted to true");
                }
                mIsSharedWithUser = false;
            }
            return new WifiNetworkSuggestion(
                    wifiConfiguration,
                    mPasspointConfiguration,
                    mIsAppInteractionRequired,
                    mIsUserInteractionRequired,
                    mIsSharedWithUser,
                    mIsInitialAutojoinEnabled);
        }
    }

    /**
     * Network configuration for the provided network.
     * @hide
     */
    @NonNull
    public final WifiConfiguration wifiConfiguration;

    /**
     * Passpoint configuration for the provided network.
     * @hide
     */
    @Nullable
    public final PasspointConfiguration passpointConfiguration;

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
     * Whether app share credential with the user, allow user use provided credential to
     * connect network manually.
     * @hide
     */
    public final boolean isUserAllowedToManuallyConnect;

    /**
     * Whether the suggestion will be initialized as auto-joined or not.
     * @hide
     */
    public final boolean isInitialAutoJoinEnabled;

    /** @hide */
    public WifiNetworkSuggestion() {
        this.wifiConfiguration = new WifiConfiguration();
        this.passpointConfiguration = null;
        this.isAppInteractionRequired = false;
        this.isUserInteractionRequired = false;
        this.isUserAllowedToManuallyConnect = true;
        this.isInitialAutoJoinEnabled = true;
    }

    /** @hide */
    public WifiNetworkSuggestion(@NonNull WifiConfiguration networkConfiguration,
                                 @Nullable PasspointConfiguration passpointConfiguration,
                                 boolean isAppInteractionRequired,
                                 boolean isUserInteractionRequired,
                                 boolean isUserAllowedToManuallyConnect,
                                 boolean isInitialAutoJoinEnabled) {
        checkNotNull(networkConfiguration);
        this.wifiConfiguration = networkConfiguration;
        this.passpointConfiguration = passpointConfiguration;

        this.isAppInteractionRequired = isAppInteractionRequired;
        this.isUserInteractionRequired = isUserInteractionRequired;
        this.isUserAllowedToManuallyConnect = isUserAllowedToManuallyConnect;
        this.isInitialAutoJoinEnabled = isInitialAutoJoinEnabled;
    }

    public static final @NonNull Creator<WifiNetworkSuggestion> CREATOR =
            new Creator<WifiNetworkSuggestion>() {
                @Override
                public WifiNetworkSuggestion createFromParcel(Parcel in) {
                    return new WifiNetworkSuggestion(
                            in.readParcelable(null), // wifiConfiguration
                            in.readParcelable(null), // PasspointConfiguration
                            in.readBoolean(), // isAppInteractionRequired
                            in.readBoolean(), // isUserInteractionRequired
                            in.readBoolean(), // isSharedCredentialWithUser
                            in.readBoolean()  // isAutojoinEnabled
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
        dest.writeParcelable(passpointConfiguration, flags);
        dest.writeBoolean(isAppInteractionRequired);
        dest.writeBoolean(isUserInteractionRequired);
        dest.writeBoolean(isUserAllowedToManuallyConnect);
        dest.writeBoolean(isInitialAutoJoinEnabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wifiConfiguration.SSID, wifiConfiguration.BSSID,
                wifiConfiguration.allowedKeyManagement, wifiConfiguration.getKey());
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
        if (this.passpointConfiguration == null ^ lhs.passpointConfiguration == null) {
            return false;
        }

        return TextUtils.equals(this.wifiConfiguration.SSID, lhs.wifiConfiguration.SSID)
                && TextUtils.equals(this.wifiConfiguration.BSSID, lhs.wifiConfiguration.BSSID)
                && Objects.equals(this.wifiConfiguration.allowedKeyManagement,
                lhs.wifiConfiguration.allowedKeyManagement)
                && TextUtils.equals(this.wifiConfiguration.getKey(),
                lhs.wifiConfiguration.getKey());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WifiNetworkSuggestion[ ")
                .append("SSID=").append(wifiConfiguration.SSID)
                .append(", BSSID=").append(wifiConfiguration.BSSID)
                .append(", FQDN=").append(wifiConfiguration.FQDN)
                .append(", isAppInteractionRequired=").append(isAppInteractionRequired)
                .append(", isUserInteractionRequired=").append(isUserInteractionRequired)
                .append(", isCredentialSharedWithUser=").append(isUserAllowedToManuallyConnect)
                .append(", isInitialAutoJoinEnabled=").append(isInitialAutoJoinEnabled)
                .append(", isUnTrusted=").append(!wifiConfiguration.trusted)
                .append(" ]");
        return sb.toString();
    }

    /**
     * Get the {@link WifiConfiguration} associated with this Suggestion.
     * @hide
     */
    @SystemApi
    @NonNull
    public WifiConfiguration getWifiConfiguration() {
        return wifiConfiguration;
    }

    /**
     * Get the BSSID, or null if unset.
     * @see Builder#setBssid(MacAddress)
     */
    @Nullable
    public MacAddress getBssid() {
        if (wifiConfiguration.BSSID == null) {
            return null;
        }
        return MacAddress.fromString(wifiConfiguration.BSSID);
    }

    /** @see Builder#setCredentialSharedWithUser(boolean) */
    public boolean isCredentialSharedWithUser() {
        return isUserAllowedToManuallyConnect;
    }

    /** @see Builder#setIsAppInteractionRequired(boolean) */
    public boolean isAppInteractionRequired() {
        return isAppInteractionRequired;
    }

    /** @see Builder#setIsEnhancedOpen(boolean)  */
    public boolean isEnhancedOpen() {
        return wifiConfiguration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE);
    }

    /** @see Builder#setIsHiddenSsid(boolean)  */
    public boolean isHiddenSsid() {
        return wifiConfiguration.hiddenSSID;
    }

    /** @see Builder#setIsInitialAutojoinEnabled(boolean)  */
    public boolean isInitialAutojoinEnabled() {
        return isInitialAutoJoinEnabled;
    }

    /** @see Builder#setIsMetered(boolean)  */
    public boolean isMetered() {
        return wifiConfiguration.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED;
    }

    /** @see Builder#setIsUserInteractionRequired(boolean)  */
    public boolean isUserInteractionRequired() {
        return isUserInteractionRequired;
    }

    /**
     * Get the {@link PasspointConfiguration} associated with this Suggestion, or null if this
     * Suggestion is not for a Passpoint network.
     */
    @Nullable
    public PasspointConfiguration getPasspointConfig() {
        return passpointConfiguration;
    }

    /** @see Builder#setPriority(int)  */
    @IntRange(from = 0)
    public int getPriority() {
        return wifiConfiguration.priority;
    }

    /**
     * Return the SSID of the network, or null if this is a Passpoint network.
     * @see Builder#setSsid(String)
     */
    @Nullable
    public String getSsid() {
        if (wifiConfiguration.SSID == null) {
            return null;
        }
        return WifiInfo.sanitizeSsid(wifiConfiguration.SSID);
    }

    /** @see Builder#setUntrusted(boolean)  */
    public boolean isUntrusted() {
        return !wifiConfiguration.trusted;
    }

    /**
     * Get the WifiEnterpriseConfig, or null if unset.
     * @see Builder#setWapiEnterpriseConfig(WifiEnterpriseConfig)
     * @see Builder#setWpa2EnterpriseConfig(WifiEnterpriseConfig)
     * @see Builder#setWpa3EnterpriseConfig(WifiEnterpriseConfig)
     */
    @Nullable
    public WifiEnterpriseConfig getEnterpriseConfig() {
        return wifiConfiguration.enterpriseConfig;
    }

    /**
     * Get the passphrase, or null if unset.
     * @see Builder#setWapiPassphrase(String)
     * @see Builder#setWpa2Passphrase(String)
     * @see Builder#setWpa3Passphrase(String)
     */
    @Nullable
    public String getPassphrase() {
        if (wifiConfiguration.preSharedKey == null) {
            return null;
        }
        return WifiInfo.removeDoubleQuotes(wifiConfiguration.preSharedKey);
    }
}
