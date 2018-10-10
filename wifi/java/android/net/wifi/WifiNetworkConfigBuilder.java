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
import android.os.PatternMatcher;
import android.os.Process;
import android.text.TextUtils;
import android.util.Pair;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WifiNetworkConfigBuilder to use for creating Wi-Fi network configuration.
 * <li>See {@link #buildNetworkSpecifier()} for creating a network specifier to use in
 * {@link NetworkRequest}.</li>
 */
public class WifiNetworkConfigBuilder {
    private static final String MATCH_ALL_SSID_PATTERN_PATH = ".*";
    private static final String MATCH_EMPTY_SSID_PATTERN_PATH = "";
    private static final Pair<MacAddress, MacAddress> MATCH_NO_BSSID_PATTERN =
            new Pair(MacAddress.BROADCAST_ADDRESS, MacAddress.BROADCAST_ADDRESS);
    private static final Pair<MacAddress, MacAddress> MATCH_ALL_BSSID_PATTERN =
            new Pair(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS);
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
     * Pre-shared key for use with WPA-PSK networks.
     */
    private @Nullable String mPskPassphrase;
    /**
     * The enterprise configuration details specifying the EAP method,
     * certificates and other settings associated with the EAP.
     */
    private @Nullable WifiEnterpriseConfig mEnterpriseConfig;
    /**
     * This is a network that does not broadcast its SSID, so an
     * SSID-specific probe request must be used for scans.
     */
    private boolean mIsHiddenSSID;

    public WifiNetworkConfigBuilder() {
        mSsidPatternMatcher = null;
        mBssidPatternMatcher = null;
        mPskPassphrase = null;
        mEnterpriseConfig = null;
        mIsHiddenSSID = false;
    }

    /**
     * Set the unicode SSID match pattern to use for filtering networks from scan results.
     * <p>
     * <li>Only allowed for creating network specifier, i.e {@link #buildNetworkSpecifier()}. </li>
     * <li>Overrides any previous value set using {@link #setSsid(String)} or
     * {@link #setSsidPattern(PatternMatcher)}.</li>
     *
     * @param ssidPattern Instance of {@link PatternMatcher} containing the UTF-8 encoded
     *                    string pattern to use for matching the network's SSID.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     */
    public WifiNetworkConfigBuilder setSsidPattern(@NonNull PatternMatcher ssidPattern) {
        checkNotNull(ssidPattern);
        mSsidPatternMatcher = ssidPattern;
        return this;
    }

    /**
     * Set the unicode SSID for the network.
     * <p>
     * <li>For network requests ({@link NetworkSpecifier}), built using
     * {@link #buildNetworkSpecifier}, sets the SSID to use for filtering networks from scan
     * results. Will only match networks whose SSID is identical to the UTF-8 encoding of the
     * specified value.</li>
     * <li>Overrides any previous value set using {@link #setSsid(String)} or
     * {@link #setSsidPattern(PatternMatcher)}.</li>
     *
     * @param ssid The SSID of the network. It must be valid Unicode.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     * @throws IllegalArgumentException if the SSID is not valid unicode.
     */
    public WifiNetworkConfigBuilder setSsid(@NonNull String ssid) {
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
     * <li>Only allowed for creating network specifier, i.e {@link #buildNetworkSpecifier()}. </li>
     * <li>Overrides any previous value set using {@link #setBssid(MacAddress)} or
     * {@link #setBssidPattern(MacAddress, MacAddress)}.</li>
     *
     * @param baseAddress Base address for BSSID pattern.
     * @param mask Mask for BSSID pattern.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     */
    public WifiNetworkConfigBuilder setBssidPattern(
            @NonNull MacAddress baseAddress, @NonNull MacAddress mask) {
        checkNotNull(baseAddress, mask);
        mBssidPatternMatcher = Pair.create(baseAddress, mask);
        return this;
    }

    /**
     * Set the BSSID to use for filtering networks from scan results. Will only match network whose
     * BSSID is identical to the specified value.
     * <p>
     * <li>Only allowed for creating network specifier, i.e {@link #buildNetworkSpecifier()}. </li>
     * <li>Overrides any previous value set using {@link #setBssid(MacAddress)} or
     * {@link #setBssidPattern(MacAddress, MacAddress)}.</li>
     *
     * @param bssid BSSID of the network.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     */
    public WifiNetworkConfigBuilder setBssid(@NonNull MacAddress bssid) {
        checkNotNull(bssid);
        mBssidPatternMatcher = Pair.create(bssid, MATCH_EXACT_BSSID_PATTERN_MASK);
        return this;
    }

    /**
     * Set the ASCII PSK passphrase for this network. Needed for authenticating to
     * WPA_PSK networks.
     *
     * @param pskPassphrase PSK passphrase of the network.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     * @throws IllegalArgumentException if the passphrase is not ASCII encodable.
     */
    public WifiNetworkConfigBuilder setPskPassphrase(@NonNull String pskPassphrase) {
        checkNotNull(pskPassphrase);
        final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();
        if (!asciiEncoder.canEncode(pskPassphrase)) {
            throw new IllegalArgumentException("passphrase not ASCII encodable");
        }
        mPskPassphrase = pskPassphrase;
        return this;
    }

    /**
     * Set the associated enterprise configuration for this network. Needed for authenticating to
     * WPA_EAP networks. See {@link WifiEnterpriseConfig} for description.
     *
     * @param enterpriseConfig Instance of {@link WifiEnterpriseConfig}.
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     */
    public WifiNetworkConfigBuilder setEnterpriseConfig(
            @NonNull WifiEnterpriseConfig enterpriseConfig) {
        checkNotNull(enterpriseConfig);
        mEnterpriseConfig = new WifiEnterpriseConfig(enterpriseConfig);
        return this;
    }

    /**
     * Whether this represents a hidden network.
     * <p>
     * <li>For network requests (see {@link NetworkSpecifier}), built using
     * {@link #buildNetworkSpecifier}, setting this disallows the usage of
     * {@link #setSsidPattern(PatternMatcher)} since hidden networks need to be explicitly
     * probed for.</li>
     * <li>If not set, defaults to false (i.e not a hidden network).</li>
     *
     * @return Instance of {@link WifiNetworkConfigBuilder} to enable chaining of the builder
     * method.
     */
    public WifiNetworkConfigBuilder setIsHiddenSsid() {
        mIsHiddenSSID = true;
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
    private static void setDefaultsInWifiConfiguration(@NonNull WifiConfiguration configuration) {
        configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
    }

    private void setKeyMgmtInWifiConfiguration(@NonNull WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(mPskPassphrase)) {
            // WPA_PSK network.
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else if (mEnterpriseConfig != null) {
            // WPA_EAP network
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
        } else {
            // Open network
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
        if (mSsidPatternMatcher.getType() == PatternMatcher.PATTERN_LITERAL) {
            wifiConfiguration.SSID = "\"" + mSsidPatternMatcher.getPath() + "\"";
        }
        setKeyMgmtInWifiConfiguration(wifiConfiguration);
        // WifiConfiguration.preSharedKey needs quotes around ASCII password.
        if (mPskPassphrase != null) {
            wifiConfiguration.preSharedKey = "\"" + mPskPassphrase + "\"";
        }
        wifiConfiguration.enterpriseConfig = mEnterpriseConfig;
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
        if (mBssidPatternMatcher.equals(MATCH_NO_BSSID_PATTERN)) {
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

    /**
     * Create a specifier object used to request a Wi-Fi network. The generated
     * {@link NetworkSpecifier} should be used in
     * {@link NetworkRequest.Builder#setNetworkSpecifier(NetworkSpecifier)} when building
     * the {@link NetworkRequest}.
     *<p>
     * Note: Apps can set a combination of network match params:
     * <li> SSID Pattern using {@link #setSsidPattern(PatternMatcher)} OR Specific SSID using
     * {@link #setSsid(String)}. </li>
     * AND/OR
     * <li> BSSID Pattern using {@link #setBssidPattern(MacAddress, MacAddress)} OR Specific BSSID
     * using {@link #setBssid(MacAddress)} </li>
     * to trigger connection to a network that matches the set params.
     * The system will find the set of networks matching the request and present the user
     * with a system dialog which will allow the user to select a specific Wi-Fi network to connect
     * to or to deny the request.
     *</p>
     *
     * For example:
     * To connect to an open network with a SSID prefix of "test" and a BSSID OUI of "10:03:23":
     * {@code
     * final NetworkSpecifier specifier =
     *      new WifiNetworkConfigBuilder()
     *      .setSsidPattern(new PatternMatcher("test", PatterMatcher.PATTERN_PREFIX))
     *      .setBssidPattern(MacAddress.fromString("10:03:23:00:00:00"),
     *                       MacAddress.fromString("ff:ff:ff:00:00:00"))
     *      .buildNetworkSpecifier()
     * final NetworkRequest request =
     *      new NetworkRequest.Builder()
     *      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
     *      .setNetworkSpecifier(specifier)
     *      .build();
     * final ConnectivityManager connectivityManager =
     *      context.getSystemService(Context.CONNECTIVITY_SERVICE);
     * final NetworkCallback networkCallback = new NetworkCallback() {
     *      ...
     *      @Override
     *      void onAvailable(...) {}
     *      // etc.
     * };
     * connectivityManager.requestNetwork(request, networkCallback);
     * }
     *
     * @return Instance of {@link NetworkSpecifier}.
     * @throws IllegalStateException on invalid params set.
     */
    public NetworkSpecifier buildNetworkSpecifier() {
        if (!hasSetAnyPattern()) {
            throw new IllegalStateException("one of setSsidPattern/setSsid/setBssidPattern/setBssid"
                    + " should be invoked for specifier");
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
        if (!TextUtils.isEmpty(mPskPassphrase) && mEnterpriseConfig != null) {
            throw new IllegalStateException("only one of setPskPassphrase or setEnterpriseConfig "
                    + "can be invoked for network specifier");
        }

        return new WifiNetworkSpecifier(
                mSsidPatternMatcher,
                mBssidPatternMatcher,
                buildWifiConfiguration(),
                Process.myUid());
    }
}
