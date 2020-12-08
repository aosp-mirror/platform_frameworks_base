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
import android.net.MacAddress;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.text.TextUtils;
import android.util.Pair;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Network specifier object used to request a local Wi-Fi network. Apps should use the
 * {@link WifiNetworkSpecifier.Builder} class to create an instance.
 */
public final class WifiNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    private static final String TAG = "WifiNetworkSpecifier";

    /**
     * Builder used to create {@link WifiNetworkSpecifier} objects.
     */
    public static final class Builder {
        private static final String MATCH_ALL_SSID_PATTERN_PATH = ".*";
        private static final String MATCH_EMPTY_SSID_PATTERN_PATH = "";
        private static final Pair<MacAddress, MacAddress> MATCH_NO_BSSID_PATTERN1 =
                new Pair<>(MacAddress.BROADCAST_ADDRESS, MacAddress.BROADCAST_ADDRESS);
        private static final Pair<MacAddress, MacAddress> MATCH_NO_BSSID_PATTERN2 =
                new Pair<>(WifiManager.ALL_ZEROS_MAC_ADDRESS, MacAddress.BROADCAST_ADDRESS);
        private static final Pair<MacAddress, MacAddress> MATCH_ALL_BSSID_PATTERN =
                new Pair<>(WifiManager.ALL_ZEROS_MAC_ADDRESS, WifiManager.ALL_ZEROS_MAC_ADDRESS);
        private static final MacAddress MATCH_EXACT_BSSID_PATTERN_MASK =
                MacAddress.BROADCAST_ADDRESS;

        /**
         * SSID pattern match specified by the app.
         */
        private @Nullable PatternMatcher mSsidPatternMatcher;
        /**
         * BSSID pattern match specified by the app.
         * Pair of <BaseAddress, Mask>.
         */
        private @Nullable Pair<MacAddress, MacAddress> mBssidPatternMatcher;
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
         * certificates and other settings associated with the WPA/WPA2-Enterprise networks.
         */
        private @Nullable WifiEnterpriseConfig mWpa2EnterpriseConfig;
        /**
         * The enterprise configuration details specifying the EAP method,
         * certificates and other settings associated with the WPA3-Enterprise networks.
         */
        private @Nullable WifiEnterpriseConfig mWpa3EnterpriseConfig;
        /**
         * This is a network that does not broadcast its SSID, so an
         * SSID-specific probe request must be used for scans.
         */
        private boolean mIsHiddenSSID;

        public Builder() {
            mSsidPatternMatcher = null;
            mBssidPatternMatcher = null;
            mIsEnhancedOpen = false;
            mWpa2PskPassphrase = null;
            mWpa3SaePassphrase = null;
            mWpa2EnterpriseConfig = null;
            mWpa3EnterpriseConfig = null;
            mIsHiddenSSID = false;
        }

        /**
         * Set the unicode SSID match pattern to use for filtering networks from scan results.
         * <p>
         * <li>Overrides any previous value set using {@link #setSsid(String)} or
         * {@link #setSsidPattern(PatternMatcher)}.</li>
         *
         * @param ssidPattern Instance of {@link PatternMatcher} containing the UTF-8 encoded
         *                    string pattern to use for matching the network's SSID.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setSsidPattern(@NonNull PatternMatcher ssidPattern) {
            checkNotNull(ssidPattern);
            mSsidPatternMatcher = ssidPattern;
            return this;
        }

        /**
         * Set the unicode SSID for the network.
         * <p>
         * <li>Sets the SSID to use for filtering networks from scan results. Will only match
         * networks whose SSID is identical to the UTF-8 encoding of the specified value.</li>
         * <li>Overrides any previous value set using {@link #setSsid(String)} or
         * {@link #setSsidPattern(PatternMatcher)}.</li>
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
            mSsidPatternMatcher = new PatternMatcher(ssid, PatternMatcher.PATTERN_LITERAL);
            return this;
        }

        /**
         * Set the BSSID match pattern to use for filtering networks from scan results.
         * Will match all networks with BSSID which satisfies the following:
         * {@code BSSID & mask == baseAddress}.
         * <p>
         * <li>Overrides any previous value set using {@link #setBssid(MacAddress)} or
         * {@link #setBssidPattern(MacAddress, MacAddress)}.</li>
         *
         * @param baseAddress Base address for BSSID pattern.
         * @param mask Mask for BSSID pattern.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setBssidPattern(
                @NonNull MacAddress baseAddress, @NonNull MacAddress mask) {
            checkNotNull(baseAddress);
            checkNotNull(mask);
            mBssidPatternMatcher = Pair.create(baseAddress, mask);
            return this;
        }

        /**
         * Set the BSSID to use for filtering networks from scan results. Will only match network
         * whose BSSID is identical to the specified value.
         * <p>
         * <li>Sets the BSSID to use for filtering networks from scan results. Will only match
         * networks whose BSSID is identical to specified value.</li>
         * <li>Overrides any previous value set using {@link #setBssid(MacAddress)} or
         * {@link #setBssidPattern(MacAddress, MacAddress)}.</li>
         *
         * @param bssid BSSID of the network.
         * @return Instance of {@link Builder} to enable chaining of the builder method.
         */
        public @NonNull Builder setBssid(@NonNull MacAddress bssid) {
            checkNotNull(bssid);
            mBssidPatternMatcher = Pair.create(bssid, MATCH_EXACT_BSSID_PATTERN_MASK);
            return this;
        }

        /**
         * Specifies whether this represents an Enhanced Open (OWE) network.
         *
         * @param isEnhancedOpen {@code true} to indicate that the network uses enhanced open,
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
         * to WPA3-Enterprise networks (standard and 192-bit security). See
         * {@link WifiEnterpriseConfig} for description. For 192-bit security networks, both the
         * client and CA certificates must be provided, and must be of type of either
         * sha384WithRSAEncryption (OID 1.2.840.113549.1.1.12) or ecdsa-with-SHA384
         * (OID 1.2.840.10045.4.3.3).
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
         * Specifies whether this represents a hidden network.
         * <p>
         * <li>Setting this disallows the usage of {@link #setSsidPattern(PatternMatcher)} since
         * hidden networks need to be explicitly probed for.</li>
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
            } else if (mWpa3EnterpriseConfig != null) { // WPA3-Enterprise
                if (mWpa3EnterpriseConfig.getEapMethod() == WifiEnterpriseConfig.Eap.TLS
                        && WifiEnterpriseConfig.isSuiteBCipherCert(
                        mWpa3EnterpriseConfig.getClientCertificate())
                        && WifiEnterpriseConfig.isSuiteBCipherCert(
                        mWpa3EnterpriseConfig.getCaCertificate())) {
                    // WPA3-Enterprise in 192-bit security mode (Suite-B)
                    configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B);
                } else {
                    // WPA3-Enterprise
                    configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
                    configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                    configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                    configuration.allowedPairwiseCiphers.set(
                            WifiConfiguration.PairwiseCipher.GCMP_256);
                    configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                    configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
                    configuration.requirePmf = true;
                }
                configuration.enterpriseConfig = mWpa3EnterpriseConfig;
            } else if (mIsEnhancedOpen) { // OWE network
                configuration.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
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
            if (mSsidPatternMatcher.getType() == PatternMatcher.PATTERN_LITERAL) {
                wifiConfiguration.SSID = "\"" + mSsidPatternMatcher.getPath() + "\"";
            }
            if (mBssidPatternMatcher.second == MATCH_EXACT_BSSID_PATTERN_MASK) {
                wifiConfiguration.BSSID = mBssidPatternMatcher.first.toString();
            }
            setSecurityParamsInWifiConfiguration(wifiConfiguration);
            wifiConfiguration.hiddenSSID = mIsHiddenSSID;
            return wifiConfiguration;
        }

        private boolean hasSetAnyPattern() {
            return mSsidPatternMatcher != null || mBssidPatternMatcher != null;
        }

        private void setMatchAnyPatternIfUnset() {
            if (mSsidPatternMatcher == null) {
                mSsidPatternMatcher = new PatternMatcher(MATCH_ALL_SSID_PATTERN_PATH,
                        PatternMatcher.PATTERN_SIMPLE_GLOB);
            }
            if (mBssidPatternMatcher == null) {
                mBssidPatternMatcher = MATCH_ALL_BSSID_PATTERN;
            }
        }

        private boolean hasSetMatchNonePattern() {
            if (mSsidPatternMatcher.getType() != PatternMatcher.PATTERN_PREFIX
                    && mSsidPatternMatcher.getPath().equals(MATCH_EMPTY_SSID_PATTERN_PATH)) {
                return true;
            }
            if (mBssidPatternMatcher.equals(MATCH_NO_BSSID_PATTERN1)) {
                return true;
            }
            if (mBssidPatternMatcher.equals(MATCH_NO_BSSID_PATTERN2)) {
                return true;
            }
            return false;
        }

        private boolean hasSetMatchAllPattern() {
            if ((mSsidPatternMatcher.match(MATCH_EMPTY_SSID_PATTERN_PATH))
                    && mBssidPatternMatcher.equals(MATCH_ALL_BSSID_PATTERN)) {
                return true;
            }
            return false;
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
         * Create a specifier object used to request a local Wi-Fi network. The generated
         * {@link NetworkSpecifier} should be used in
         * {@link NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} when building
         * the {@link NetworkRequest}. These specifiers can only be used to request a local wifi
         * network (i.e no internet capability). So, the device will not switch it's default route
         * to wifi if there are other transports (cellular for example) available.
         *<p>
         * Note: Apps can set a combination of network match params:
         * <li> SSID Pattern using {@link #setSsidPattern(PatternMatcher)} OR Specific SSID using
         * {@link #setSsid(String)}. </li>
         * AND/OR
         * <li> BSSID Pattern using {@link #setBssidPattern(MacAddress, MacAddress)} OR Specific
         * BSSID using {@link #setBssid(MacAddress)} </li>
         * to trigger connection to a network that matches the set params.
         * The system will find the set of networks matching the request and present the user
         * with a system dialog which will allow the user to select a specific Wi-Fi network to
         * connect to or to deny the request.
         *</p>
         *
         * For example:
         * To connect to an open network with a SSID prefix of "test" and a BSSID OUI of "10:03:23":
         *
         * <pre>{@code
         * final NetworkSpecifier specifier =
         *      new Builder()
         *      .setSsidPattern(new PatternMatcher("test", PatterMatcher.PATTERN_PREFIX))
         *      .setBssidPattern(MacAddress.fromString("10:03:23:00:00:00"),
         *                       MacAddress.fromString("ff:ff:ff:00:00:00"))
         *      .build()
         * final NetworkRequest request =
         *      new NetworkRequest.Builder()
         *      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
         *      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
         *      .setNetworkSpecifier(specifier)
         *      .build();
         * final ConnectivityManager connectivityManager =
         *      context.getSystemService(Context.CONNECTIVITY_SERVICE);
         * final NetworkCallback networkCallback = new NetworkCallback() {
         *      ...
         *      {@literal @}Override
         *      void onAvailable(...) {}
         *      // etc.
         * };
         * connectivityManager.requestNetwork(request, networkCallback);
         * }</pre>
         *
         * @return Instance of {@link NetworkSpecifier}.
         * @throws IllegalStateException on invalid params set.
         */
        public @NonNull WifiNetworkSpecifier build() {
            if (!hasSetAnyPattern()) {
                throw new IllegalStateException("one of setSsidPattern/setSsid/setBssidPattern/"
                        + "setBssid should be invoked for specifier");
            }
            setMatchAnyPatternIfUnset();
            if (hasSetMatchNonePattern()) {
                throw new IllegalStateException("cannot set match-none pattern for specifier");
            }
            if (hasSetMatchAllPattern()) {
                throw new IllegalStateException("cannot set match-all pattern for specifier");
            }
            if (mIsHiddenSSID && mSsidPatternMatcher.getType() != PatternMatcher.PATTERN_LITERAL) {
                throw new IllegalStateException("setSsid should also be invoked when "
                        + "setIsHiddenSsid is invoked for network specifier");
            }
            validateSecurityParams();

            return new WifiNetworkSpecifier(
                    mSsidPatternMatcher,
                    mBssidPatternMatcher,
                    buildWifiConfiguration());
        }
    }

    /**
     * SSID pattern match specified by the app.
     * @hide
     */
    public final PatternMatcher ssidPatternMatcher;

    /**
     * BSSID pattern match specified by the app.
     * Pair of <BaseAddress, Mask>.
     * @hide
     */
    public final Pair<MacAddress, MacAddress> bssidPatternMatcher;

    /**
     * Security credentials for the network.
     * <p>
     * Note: {@link WifiConfiguration#SSID} & {@link WifiConfiguration#BSSID} fields from
     * WifiConfiguration are not used. Instead we use the {@link #ssidPatternMatcher} &
     * {@link #bssidPatternMatcher} fields embedded directly
     * within {@link WifiNetworkSpecifier}.
     * @hide
     */
    public final WifiConfiguration wifiConfiguration;

    /** @hide */
    public WifiNetworkSpecifier() throws IllegalAccessException {
        throw new IllegalAccessException("Use the builder to create an instance");
    }

    /** @hide */
    public WifiNetworkSpecifier(@NonNull PatternMatcher ssidPatternMatcher,
                                @NonNull Pair<MacAddress, MacAddress> bssidPatternMatcher,
                                @NonNull WifiConfiguration wifiConfiguration) {
        checkNotNull(ssidPatternMatcher);
        checkNotNull(bssidPatternMatcher);
        checkNotNull(wifiConfiguration);

        this.ssidPatternMatcher = ssidPatternMatcher;
        this.bssidPatternMatcher = bssidPatternMatcher;
        this.wifiConfiguration = wifiConfiguration;
    }

    public static final @NonNull Creator<WifiNetworkSpecifier> CREATOR =
            new Creator<WifiNetworkSpecifier>() {
                @Override
                public WifiNetworkSpecifier createFromParcel(Parcel in) {
                    PatternMatcher ssidPatternMatcher = in.readParcelable(/* classLoader */null);
                    MacAddress baseAddress = in.readParcelable(null);
                    MacAddress mask = in.readParcelable(null);
                    Pair<MacAddress, MacAddress> bssidPatternMatcher =
                            Pair.create(baseAddress, mask);
                    WifiConfiguration wifiConfiguration = in.readParcelable(null);
                    return new WifiNetworkSpecifier(ssidPatternMatcher, bssidPatternMatcher,
                            wifiConfiguration);
                }

                @Override
                public WifiNetworkSpecifier[] newArray(int size) {
                    return new WifiNetworkSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(ssidPatternMatcher, flags);
        dest.writeParcelable(bssidPatternMatcher.first, flags);
        dest.writeParcelable(bssidPatternMatcher.second, flags);
        dest.writeParcelable(wifiConfiguration, flags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                ssidPatternMatcher.getPath(), ssidPatternMatcher.getType(), bssidPatternMatcher,
                wifiConfiguration.allowedKeyManagement);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof WifiNetworkSpecifier)) {
            return false;
        }
        WifiNetworkSpecifier lhs = (WifiNetworkSpecifier) obj;
        return Objects.equals(this.ssidPatternMatcher.getPath(),
                    lhs.ssidPatternMatcher.getPath())
                && Objects.equals(this.ssidPatternMatcher.getType(),
                    lhs.ssidPatternMatcher.getType())
                && Objects.equals(this.bssidPatternMatcher,
                    lhs.bssidPatternMatcher)
                && Objects.equals(this.wifiConfiguration.allowedKeyManagement,
                    lhs.wifiConfiguration.allowedKeyManagement);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("WifiNetworkSpecifier [")
                .append(", SSID Match pattern=").append(ssidPatternMatcher)
                .append(", BSSID Match pattern=").append(bssidPatternMatcher)
                .append(", SSID=").append(wifiConfiguration.SSID)
                .append(", BSSID=").append(wifiConfiguration.BSSID)
                .append("]")
                .toString();
    }

    /** @hide */
    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        if (other instanceof WifiNetworkAgentSpecifier) {
            return ((WifiNetworkAgentSpecifier) other).satisfiesNetworkSpecifier(this);
        }
        // Specific requests are checked for equality although testing for equality of 2 patterns do
        // not make much sense!
        return equals(other);
    }
}
