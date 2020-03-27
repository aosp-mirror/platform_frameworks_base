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

import static android.os.PatternMatcher.PATTERN_LITERAL;
import static android.os.PatternMatcher.PATTERN_PREFIX;
import static android.os.PatternMatcher.PATTERN_SIMPLE_GLOB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkSpecifier}.
 */
@SmallTest
public class WifiNetworkSpecifierTest {
    private static final String TEST_SSID = "Test123";
    private static final String TEST_BSSID_OUI_BASE_ADDRESS = "12:12:12:00:00:00";
    private static final String TEST_BSSID_OUI_MASK = "ff:ff:ff:00:00:00";
    private static final String TEST_BSSID = "12:12:12:12:12:12";
    private static final String TEST_PRESHARED_KEY = "\"Test123\"";

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkSpecifier.Builder#build()} for open network with SSID pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForOpenNetworkWithSsidPattern() {
        NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_PREFIX))
                .build();

        assertTrue(specifier instanceof WifiNetworkSpecifier);
        WifiNetworkSpecifier wifiNetworkSpecifier = (WifiNetworkSpecifier) specifier;

        assertEquals(TEST_SSID, wifiNetworkSpecifier.ssidPatternMatcher.getPath());
        assertEquals(PATTERN_PREFIX, wifiNetworkSpecifier.ssidPatternMatcher.getType());
        assertEquals(WifiManager.ALL_ZEROS_MAC_ADDRESS,
                wifiNetworkSpecifier.bssidPatternMatcher.first);
        assertEquals(WifiManager.ALL_ZEROS_MAC_ADDRESS,
                wifiNetworkSpecifier.bssidPatternMatcher.second);
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.NONE));
    }

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkSpecifier.Builder#build()} for WPA_PSK network with BSSID
     * pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForWpa2PskNetworkWithBssidPattern() {
        NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setBssidPattern(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK))
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .build();

        assertTrue(specifier instanceof WifiNetworkSpecifier);
        WifiNetworkSpecifier wifiNetworkSpecifier = (WifiNetworkSpecifier) specifier;

        assertEquals(".*", wifiNetworkSpecifier.ssidPatternMatcher.getPath());
        assertEquals(PATTERN_SIMPLE_GLOB, wifiNetworkSpecifier.ssidPatternMatcher.getType());
        assertEquals(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                wifiNetworkSpecifier.bssidPatternMatcher.first);
        assertEquals(MacAddress.fromString(TEST_BSSID_OUI_MASK),
                wifiNetworkSpecifier.bssidPatternMatcher.second);
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                wifiNetworkSpecifier.wifiConfiguration.preSharedKey);
    }

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkSpecifier.Builder#build()} for WPA_EAP network with
     * SSID and BSSID pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForWpa2EapHiddenNetworkWithSsidAndBssid() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);

        NetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.fromString(TEST_BSSID))
                .setWpa2EnterpriseConfig(enterpriseConfig)
                .setIsHiddenSsid(true)
                .build();

        assertTrue(specifier instanceof WifiNetworkSpecifier);
        WifiNetworkSpecifier wifiNetworkSpecifier = (WifiNetworkSpecifier) specifier;

        assertEquals(TEST_SSID, wifiNetworkSpecifier.ssidPatternMatcher.getPath());
        assertEquals(PATTERN_LITERAL, wifiNetworkSpecifier.ssidPatternMatcher.getType());
        assertEquals(MacAddress.fromString(TEST_BSSID),
                wifiNetworkSpecifier.bssidPatternMatcher.first);
        assertEquals(MacAddress.BROADCAST_ADDRESS,
                wifiNetworkSpecifier.bssidPatternMatcher.second);
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_EAP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.IEEE8021X));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.hiddenSSID);
        assertEquals(enterpriseConfig.getEapMethod(),
                wifiNetworkSpecifier.wifiConfiguration.enterpriseConfig.getEapMethod());
        assertEquals(enterpriseConfig.getPhase2Method(),
                wifiNetworkSpecifier.wifiConfiguration.enterpriseConfig.getPhase2Method());
    }


    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#setSsid(String)} throws an exception
     * when the string is not Unicode.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSpecifierBuilderSetSsidWithNonUnicodeString() {
        new WifiNetworkSpecifier.Builder()
                .setSsid("\ud800")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#setWpa2Passphrase(String)} throws an exception
     * when the string is not ASCII encodable.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSpecifierSetWpa2PasphraseWithNonAsciiString() {
        new WifiNetworkSpecifier.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase("salvē")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when neither SSID nor BSSID patterns were set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithNoSsidAndBssidPattern() {
        new WifiNetworkSpecifier.Builder().build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern1() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(".*", PATTERN_SIMPLE_GLOB))
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern2() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(".*", PatternMatcher.PATTERN_ADVANCED_GLOB))
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern3() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher("", PATTERN_PREFIX))
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-all BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllBssidPattern() {
        new WifiNetworkSpecifier.Builder()
                .setBssidPattern(WifiManager.ALL_ZEROS_MAC_ADDRESS,
                        WifiManager.ALL_ZEROS_MAC_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-none SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneSsidPattern1() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher("", PatternMatcher.PATTERN_LITERAL))
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-none SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneSsidPattern2() {
        new WifiNetworkSpecifier.Builder()
                .setSsid("")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-none BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneBssidPattern1() {
        new WifiNetworkSpecifier.Builder()
                .setBssidPattern(MacAddress.BROADCAST_ADDRESS, MacAddress.BROADCAST_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-none BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneBssidPattern2() {
        new WifiNetworkSpecifier.Builder()
                .setBssid(MacAddress.BROADCAST_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when match-none BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneBssidPattern3() {
        new WifiNetworkSpecifier.Builder()
                .setBssid(WifiManager.ALL_ZEROS_MAC_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when SSID pattern is set for hidden network.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBssidMatchPatternForHiddenNetwork() {
        new WifiNetworkSpecifier.Builder()
                .setBssidPattern(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK))
                .setIsHiddenSsid(true)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when both {@link WifiNetworkSpecifier.Builder#setWpa2Passphrase(String)} and
     * {@link WifiNetworkSpecifier.Builder#setWpa2EnterpriseConfig(WifiEnterpriseConfig)} are
     * invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBothWpa2PasphraseAndEnterpriseConfig() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setWpa2EnterpriseConfig(new WifiEnterpriseConfig())
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when SSID pattern is set for hidden network.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithSsidMatchPatternForHiddenNetwork() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_PREFIX))
                .setIsHiddenSsid(true)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when both {@link WifiNetworkSpecifier.Builder#setWpa2Passphrase(String)} and
     * {@link WifiNetworkSpecifier.Builder#setWpa3Passphrase(String)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBothWpa2PasphraseAndWpa3Passphrase() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when both {@link WifiNetworkSpecifier.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSpecifier.Builder#setWpa3EnterpriseConfig(WifiEnterpriseConfig)} are
     * invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBothWpa3PasphraseAndEnterprise() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setWpa3EnterpriseConfig(new WifiEnterpriseConfig())
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSpecifier.Builder#build()} throws an exception
     * when both {@link WifiNetworkSpecifier.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSpecifier.Builder#setIsEnhancedOpen(boolean)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBothWpa3PasphraseAndEnhancedOpen() {
        new WifiNetworkSpecifier.Builder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setIsEnhancedOpen(true)
                .build();
    }

    /**
     * Validate that parcel marshalling/unmarshalling works
     */
    @Test
    public void testWifiNetworkSpecifierParcel() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;
        WifiNetworkSpecifier specifier =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        Parcel parcelW = Parcel.obtain();
        specifier.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkSpecifier parcelSpecifier =
                WifiNetworkSpecifier.CREATOR.createFromParcel(parcelR);

        assertEquals(specifier, parcelSpecifier);
    }

    /**
     * Validate NetworkSpecifier matching.
     * a) Create a network specifier for WPA_PSK network
     * b) Ensure that the specifier matches {@code null} and {@link MatchAllNetworkSpecifier}
     * specifiers.
     */
    @Test
    public void testWifiNetworkSpecifierSatisfiesNullAndAllMatch() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;
        WifiNetworkSpecifier specifier =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        assertTrue(specifier.canBeSatisfiedBy(null));
        assertTrue(specifier.canBeSatisfiedBy(new MatchAllNetworkSpecifier()));
    }

    /**
     * Validate NetworkSpecifier matching.
     * a) Create network specifier 1 for WPA_PSK network
     * b) Create network specifier 2 with the same params as specifier 1.
     * c) Ensure that the specifier 2 is satisfied by specifier 1.
     */
    @Test
    public void testWifiNetworkSpecifierSatisfiesSame() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;

        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        assertTrue(specifier2.canBeSatisfiedBy(specifier1));
    }

    /**
     * Validate NetworkSpecifier matching.
     * a) Create network specifier 1 for WPA_PSK network
     * b) Create network specifier 2 with different key mgmt params.
     * c) Ensure that the specifier 2 is not satisfied by specifier 1.
     */
    @Test
    public void testWifiNetworkSpecifierDoesNotSatisfyWhenKeyMgmtDifferent() {
        WifiConfiguration wifiConfiguration1 = new WifiConfiguration();
        wifiConfiguration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration1.preSharedKey = TEST_PRESHARED_KEY;

        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration1);

        WifiConfiguration wifiConfiguration2 = new WifiConfiguration();
        wifiConfiguration2.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration2);

        assertFalse(specifier2.canBeSatisfiedBy(specifier1));
    }

    /**
     * Validate NetworkSpecifier matching.
     * a) Create network specifier 1 for WPA_PSK network
     * b) Create network specifier 2 with different SSID pattern.
     * c) Ensure that the specifier 2 is not satisfied by specifier 1.
     */
    @Test
    public void testWifiNetworkSpecifierDoesNotSatisfyWhenSsidDifferent() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;

        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(new PatternMatcher("", PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        assertFalse(specifier2.canBeSatisfiedBy(specifier1));
    }

    /**
     * Validate NetworkSpecifier matching.
     * a) Create network specifier 1 for WPA_PSK network
     * b) Create network specifier 2 with different BSSID pattern.
     * c) Ensure that the specifier 2 is not satisfied by specifier 1.
     */
    @Test
    public void testWifiNetworkSpecifierDoesNotSatisfyWhenBssidDifferent() {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        wifiConfiguration.preSharedKey = TEST_PRESHARED_KEY;

        WifiNetworkSpecifier specifier1 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(WifiManager.ALL_ZEROS_MAC_ADDRESS,
                                WifiManager.ALL_ZEROS_MAC_ADDRESS),
                        wifiConfiguration);

        assertFalse(specifier2.canBeSatisfiedBy(specifier1));
    }
}
