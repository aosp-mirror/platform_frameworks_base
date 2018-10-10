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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.MatchAllNetworkSpecifier;
import android.os.Parcel;
import android.os.PatternMatcher;
import android.support.test.filters.SmallTest;
import android.util.Pair;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkSpecifier}.
 */
@SmallTest
public class WifiNetworkSpecifierTest {
    private static final int TEST_UID = 5;
    private static final String TEST_SSID = "Test123";
    private static final String TEST_BSSID_OUI_BASE_ADDRESS = "12:12:12:00:00:00";
    private static final String TEST_BSSID_OUI_MASK = "ff:ff:ff:00:00:00";
    private static final String TEST_PRESHARED_KEY = "\"Test123\"";

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
                        wifiConfiguration,
                        TEST_UID);

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
                        wifiConfiguration,
                        TEST_UID);

        assertTrue(specifier.satisfiedBy(null));
        assertTrue(specifier.satisfiedBy(new MatchAllNetworkSpecifier()));
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
                        wifiConfiguration,
                        TEST_UID);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration,
                        TEST_UID);

        assertTrue(specifier2.satisfiedBy(specifier1));
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
                        wifiConfiguration1,
                        TEST_UID);

        WifiConfiguration wifiConfiguration2 = new WifiConfiguration();
        wifiConfiguration2.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration2,
                        TEST_UID);

        assertFalse(specifier2.satisfiedBy(specifier1));
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
                        wifiConfiguration,
                        TEST_UID);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                                MacAddress.fromString(TEST_BSSID_OUI_MASK)),
                        wifiConfiguration,
                        TEST_UID);

        assertFalse(specifier2.satisfiedBy(specifier1));
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
                        wifiConfiguration,
                        TEST_UID);

        WifiNetworkSpecifier specifier2 =
                new WifiNetworkSpecifier(new PatternMatcher(TEST_SSID, PATTERN_LITERAL),
                        Pair.create(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS),
                        wifiConfiguration,
                        TEST_UID);

        assertFalse(specifier2.satisfiedBy(specifier1));
    }
}
