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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkAgentSpecifier}.
 */
@SmallTest
public class WifiNetworkAgentSpecifierTest {
    private static final String TEST_SSID = "Test123";
    private static final String TEST_SSID_PATTERN = "Test";
    private static final String TEST_SSID_1 = "456test";
    private static final String TEST_BSSID = "12:12:12:aa:0b:c0";
    private static final String TEST_BSSID_OUI_BASE_ADDRESS = "12:12:12:00:00:00";
    private static final String TEST_BSSID_OUI_MASK = "ff:ff:ff:00:00:00";
    private static final String TEST_BSSID_1 = "aa:cc:12:aa:0b:c0";
    private static final String TEST_PRESHARED_KEY = "\"Test123\"";

    /**
     * Validate that parcel marshalling/unmarshalling works
     */
    @Test
    public void testWifiNetworkAgentSpecifierParcel() {
        WifiNetworkAgentSpecifier specifier = createDefaultNetworkAgentSpecifier();

        Parcel parcelW = Parcel.obtain();
        specifier.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkAgentSpecifier parcelSpecifier =
                WifiNetworkAgentSpecifier.CREATOR.createFromParcel(parcelR);

        assertEquals(specifier, parcelSpecifier);
    }

    /**
     * Validate NetworkAgentSpecifier equals with itself.
     * a) Create network agent specifier 1 for WPA_PSK network
     * b) Create network agent specifier 2 with the same params as specifier 1.
     * c) Ensure that the specifier 2 equals specifier 1.
     */
    @Test
    public void testWifiNetworkAgentSpecifierEqualsSame() {
        WifiNetworkAgentSpecifier specifier1 = createDefaultNetworkAgentSpecifier();
        WifiNetworkAgentSpecifier specifier2 = createDefaultNetworkAgentSpecifier();

        assertTrue(specifier2.equals(specifier1));
    }

    /**
     * Validate NetworkAgentSpecifier equals between instances of {@link WifiNetworkAgentSpecifier}.
     * a) Create network agent specifier 1 for WPA_PSK network
     * b) Create network agent specifier 2 with different key mgmt params.
     * c) Ensure that the specifier 2 does not equal specifier 1.
     */
    @Test
    public void testWifiNetworkAgentSpecifierDoesNotEqualsWhenKeyMgmtDifferent() {
        WifiConfiguration wifiConfiguration1 = createDefaultWifiConfiguration();
        WifiNetworkAgentSpecifier specifier1 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration1);

        WifiConfiguration wifiConfiguration2 = new WifiConfiguration(wifiConfiguration1);
        wifiConfiguration2.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkAgentSpecifier specifier2 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration2);

        assertFalse(specifier2.equals(specifier1));
    }

    /**
     * Validate NetworkAgentSpecifier equals between instances of {@link WifiNetworkAgentSpecifier}.
     * a) Create network agent specifier 1 for WPA_PSK network
     * b) Create network agent specifier 2 with different SSID.
     * c) Ensure that the specifier 2 does not equal specifier 1.
     */
    @Test
    public void testWifiNetworkAgentSpecifierDoesNotSatisifyWhenSsidDifferent() {
        WifiConfiguration wifiConfiguration1 = createDefaultWifiConfiguration();
        WifiNetworkAgentSpecifier specifier1 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration1);

        WifiConfiguration wifiConfiguration2 = new WifiConfiguration(wifiConfiguration1);
        wifiConfiguration2.SSID = TEST_SSID_1;
        WifiNetworkAgentSpecifier specifier2 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration2);

        assertFalse(specifier2.equals(specifier1));
    }

    /**
     * Validate NetworkAgentSpecifier equals between instances of {@link WifiNetworkAgentSpecifier}.
     * a) Create network agent specifier 1 for WPA_PSK network
     * b) Create network agent specifier 2 with different BSSID.
     * c) Ensure that the specifier 2 does not equal specifier 1.
     */
    @Test
    public void testWifiNetworkAgentSpecifierDoesNotSatisifyWhenBssidDifferent() {
        WifiConfiguration wifiConfiguration1 = createDefaultWifiConfiguration();
        WifiNetworkAgentSpecifier specifier1 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration1);

        WifiConfiguration wifiConfiguration2 = new WifiConfiguration(wifiConfiguration1);
        wifiConfiguration2.BSSID = TEST_BSSID_1;
        WifiNetworkAgentSpecifier specifier2 =
                new WifiNetworkAgentSpecifier(
                        wifiConfiguration2);

        assertFalse(specifier2.equals(specifier1));
    }

    /**
     * Validate NetworkAgentSpecifier matching.
     * a) Create a network agent specifier for WPA_PSK network
     * b) Ensure that the specifier matches {@code null} and {@link MatchAllNetworkSpecifier}
     * specifiers.
     */
    @Test
    public void testWifiNetworkAgentSpecifierSatisifiesNullAndAllMatch() {
        WifiNetworkAgentSpecifier specifier = createDefaultNetworkAgentSpecifier();

        assertTrue(specifier.canBeSatisfiedBy(null));
        assertTrue(specifier.canBeSatisfiedBy(new MatchAllNetworkSpecifier()));
    }

    /**
     * Validate NetworkAgentSpecifier matching with itself.
     * a) Create network agent specifier 1 for WPA_PSK network
     * b) Create network agent specifier 2 with the same params as specifier 1.
     * c) Ensure that the agent specifier is satisfied by itself.
     */
    @Test
    public void testWifiNetworkAgentSpecifierDoesSatisifySame() {
        WifiNetworkAgentSpecifier specifier1 = createDefaultNetworkAgentSpecifier();
        WifiNetworkAgentSpecifier specifier2 = createDefaultNetworkAgentSpecifier();

        assertTrue(specifier2.canBeSatisfiedBy(specifier1));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with matching SSID pattern.
     * c) Ensure that the agent specifier is satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierSatisfiesNetworkSpecifierWithSsidPattern() {
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier = createDefaultNetworkAgentSpecifier();

        PatternMatcher ssidPattern =
                new PatternMatcher(TEST_SSID_PATTERN, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(WifiManager.ALL_ZEROS_MAC_ADDRESS, WifiManager.ALL_ZEROS_MAC_ADDRESS);
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertTrue(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertTrue(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with matching BSSID pattern.
     * c) Ensure that the agent specifier is satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierSatisfiesNetworkSpecifierWithBssidPattern() {
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier = createDefaultNetworkAgentSpecifier();

        PatternMatcher ssidPattern =
                new PatternMatcher(".*", PatternMatcher.PATTERN_SIMPLE_GLOB);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertTrue(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertTrue(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with matching SSID & BSSID pattern.
     * c) Ensure that the agent specifier is satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierSatisfiesNetworkSpecifierWithSsidAndBssidPattern() {
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier = createDefaultNetworkAgentSpecifier();

        PatternMatcher ssidPattern =
                new PatternMatcher(TEST_SSID_PATTERN, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertTrue(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertTrue(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with non-matching SSID pattern.
     * c) Ensure that the agent specifier is not satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierDoesNotSatisfyNetworkSpecifierWithSsidPattern() {
        WifiConfiguration wifiConfigurationNetworkAgent = createDefaultWifiConfiguration();
        wifiConfigurationNetworkAgent.SSID = "\"" + TEST_SSID_1 + "\"";
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier =
                new WifiNetworkAgentSpecifier(
                        wifiConfigurationNetworkAgent);

        PatternMatcher ssidPattern =
                new PatternMatcher(TEST_SSID_PATTERN, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(WifiManager.ALL_ZEROS_MAC_ADDRESS, WifiManager.ALL_ZEROS_MAC_ADDRESS);
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertFalse(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertFalse(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with non-matching BSSID pattern.
     * c) Ensure that the agent specifier is not satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierDoesNotSatisfyNetworkSpecifierWithBssidPattern() {
        WifiConfiguration wifiConfigurationNetworkAgent = createDefaultWifiConfiguration();
        wifiConfigurationNetworkAgent.BSSID = TEST_BSSID_1;
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier =
                new WifiNetworkAgentSpecifier(
                        wifiConfigurationNetworkAgent);

        PatternMatcher ssidPattern =
                new PatternMatcher(".*", PatternMatcher.PATTERN_SIMPLE_GLOB);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertFalse(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertFalse(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with non-matching SSID and BSSID pattern.
     * c) Ensure that the agent specifier is not satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierDoesNotSatisfyNetworkSpecifierWithSsidAndBssidPattern() {
        WifiConfiguration wifiConfigurationNetworkAgent = createDefaultWifiConfiguration();
        wifiConfigurationNetworkAgent.BSSID = TEST_BSSID_1;
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier =
                new WifiNetworkAgentSpecifier(
                        wifiConfigurationNetworkAgent);

        PatternMatcher ssidPattern =
                new PatternMatcher(TEST_SSID_PATTERN, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement
                .set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertFalse(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertFalse(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }

    /**
     * Validate {@link WifiNetworkAgentSpecifier} with {@link WifiNetworkSpecifier} matching.
     * a) Create network agent specifier for WPA_PSK network
     * b) Create network specifier with matching SSID and BSSID pattern, but different key mgmt.
     * c) Ensure that the agent specifier is not satisfied by specifier.
     */
    @Test
    public void
            testWifiNetworkAgentSpecifierDoesNotSatisfyNetworkSpecifierWithDifferentKeyMgmt() {
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier = createDefaultNetworkAgentSpecifier();

        PatternMatcher ssidPattern =
                new PatternMatcher(TEST_SSID_PATTERN, PatternMatcher.PATTERN_PREFIX);
        Pair<MacAddress, MacAddress> bssidPattern =
                Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK));
        WifiConfiguration wificonfigurationNetworkSpecifier = new WifiConfiguration();
        wificonfigurationNetworkSpecifier.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier(
                ssidPattern,
                bssidPattern,
                wificonfigurationNetworkSpecifier);

        assertFalse(wifiNetworkSpecifier.canBeSatisfiedBy(wifiNetworkAgentSpecifier));
        assertFalse(wifiNetworkAgentSpecifier.canBeSatisfiedBy(wifiNetworkSpecifier));
    }


    private WifiConfiguration createDefaultWifiConfiguration() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = "\"" + TEST_SSID + "\"";
        wifiConfiguration.BSSID = TEST_BSSID;
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;
        return wifiConfiguration;
    }

    private WifiNetworkAgentSpecifier createDefaultNetworkAgentSpecifier() {
        return new WifiNetworkAgentSpecifier(createDefaultWifiConfiguration());
    }

}
