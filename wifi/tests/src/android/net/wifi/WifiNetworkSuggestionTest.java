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

import static org.junit.Assert.*;

import android.net.MacAddress;
import android.os.Parcel;
import android.os.Process;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkSuggestion}.
 */
@SmallTest
public class WifiNetworkSuggestionTest {
    private static final int TEST_UID = 45677;
    private static final int TEST_UID_OTHER = 45673;
    private static final String TEST_PACKAGE_NAME = "com.test.packagename";
    private static final String TEST_PACKAGE_NAME_OTHER = "com.test.packagenameother";
    private static final String TEST_SSID = "\"Test123\"";
    private static final String TEST_BSSID = "12:12:12:12:12:12";
    private static final String TEST_SSID_1 = "\"Test1234\"";
    private static final String TEST_PRESHARED_KEY = "Test123";

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for Open network which requires
     * app interaction.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForOpenNetworkWithReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setIsAppInteractionRequired()
                .build();

        assertEquals(Process.myUid(), suggestion.suggestorUid);
        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.NONE));
        assertTrue(suggestion.isAppInteractionRequired);
        assertFalse(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_NONE,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(-1, suggestion.wifiConfiguration.priority);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WPA_EAP network which requires
     * app interaction and has a priority of zero set.
     */
    @Test
    public void
            testWifiNetworkSuggestionBuilderForWpa2EapNetworkWithPriorityAndReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setIsAppInteractionRequired()
                .setPriority(0)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.isAppInteractionRequired);
        assertFalse(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_NONE,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(0, suggestion.wifiConfiguration.priority);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for WPA_PSK network which requires
     * user interaction and is metered.
     */
    @Test
    public void
            testWifiNetworkSuggestionBuilderForWpa2PskNetworkWithMeteredAndReqUserInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setIsUserInteractionRequired()
                .setIsMetered()
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.WPA_PSK));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertFalse(suggestion.isAppInteractionRequired);
        assertTrue(suggestion.isUserInteractionRequired);
        assertEquals(WifiConfiguration.METERED_OVERRIDE_METERED,
                suggestion.wifiConfiguration.meteredOverride);
        assertEquals(-1, suggestion.wifiConfiguration.priority);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for OWE network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForEnhancedOpenNetworkWithBssid() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.fromString(TEST_BSSID))
                .setIsEnhancedOpen()
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertEquals(TEST_BSSID, suggestion.wifiConfiguration.BSSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.OWE));
        assertNull(suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.wifiConfiguration.requirePMF);
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for SAE network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpa3PskNetwork() {
        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.SAE));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                suggestion.wifiConfiguration.preSharedKey);
        assertTrue(suggestion.wifiConfiguration.requirePMF);
    }


    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkSuggestion.Builder#build()} for SuiteB network.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpa3EapNetwork() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);

        WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3EnterpriseConfig(enterpriseConfig)
                .build();

        assertEquals("\"" + TEST_SSID + "\"", suggestion.wifiConfiguration.SSID);
        assertTrue(suggestion.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.SUITE_B_192));
        assertTrue(suggestion.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.GCMP_256));
        assertTrue(suggestion.wifiConfiguration.allowedGroupManagementCiphers
                .get(WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256));
        assertTrue(suggestion.wifiConfiguration.allowedSuiteBCiphers
                .get(WifiConfiguration.SuiteBCipher.ECDHE_ECDSA));
        assertTrue(suggestion.wifiConfiguration.allowedSuiteBCiphers
                .get(WifiConfiguration.SuiteBCipher.ECDHE_RSA));
        assertTrue(suggestion.wifiConfiguration.requirePMF);
        assertNull(suggestion.wifiConfiguration.preSharedKey);
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setSsid(String)} throws an exception
     * when the string is not Unicode.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderSetSsidWithNonUnicodeString() {
        new WifiNetworkSuggestion.Builder()
                .setSsid("\ud800")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setWpa2Passphrase(String)} throws an exception
     * when the string is not ASCII encodable.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderSetWpa2PasphraseWithNonAsciiString() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase("salvē")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setSsid(String)} is not set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithNoSsid() {
        new WifiNetworkSuggestion.Builder()
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setSsid(String)} is invoked with an invalid value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidSsid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid("")
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setBssid(MacAddress)} is invoked with an invalid
     * value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidBroadcastBssid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.BROADCAST_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when {@link WifiNetworkSuggestion.Builder#setBssid(MacAddress)} is invoked with an invalid
     * value.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidAllZeroBssid() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.ALL_ZEROS_ADDRESS)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#setPriority(int)} throws an exception
     * when the value is negative.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidPriority() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setPriority(-1)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa2Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa2PasphraseAndWpa3Passphrase() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa2Passphrase(TEST_PRESHARED_KEY)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setWpa3EnterpriseConfig(WifiEnterpriseConfig)} are
     * invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa3PasphraseAndEnterprise() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setWpa3EnterpriseConfig(new WifiEnterpriseConfig())
                .build();
    }

    /**
     * Ensure {@link WifiNetworkSuggestion.Builder#build()} throws an exception
     * when both {@link WifiNetworkSuggestion.Builder#setWpa3Passphrase(String)} and
     * {@link WifiNetworkSuggestion.Builder#setIsEnhancedOpen()} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBothWpa3PasphraseAndEnhancedOpen() {
        new WifiNetworkSuggestion.Builder()
                .setSsid(TEST_SSID)
                .setWpa3Passphrase(TEST_PRESHARED_KEY)
                .setIsEnhancedOpen()
                .build();
    }

    /**
     * Check that parcel marshalling/unmarshalling works
     */
    @Test
    public void testWifiNetworkSuggestionParcel() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, true, TEST_UID, TEST_PACKAGE_NAME);

        Parcel parcelW = Parcel.obtain();
        suggestion.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkSuggestion parcelSuggestion =
                WifiNetworkSuggestion.CREATOR.createFromParcel(parcelR);

        // Two suggestion objects are considered equal if they point to the same network (i.e same
        // SSID + keyMgmt + same UID). |isAppInteractionRequired| & |isUserInteractionRequired| are
        // not considered for equality and hence needs to be checked for explicitly below.
        assertEquals(suggestion, parcelSuggestion);
        assertEquals(suggestion.isAppInteractionRequired,
                parcelSuggestion.isAppInteractionRequired);
        assertEquals(suggestion.isUserInteractionRequired,
                parcelSuggestion.isUserInteractionRequired);
    }

    /**
     * Check NetworkSuggestion equals returns {@code true} for 2 network suggestions with the same
     * SSID, BSSID, key mgmt and UID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsSame() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, true, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.BSSID = TEST_BSSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, true, TEST_UID,
                        TEST_PACKAGE_NAME);

        assertEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * BSSID, key mgmt and UID, but different SSID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenSsidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID_1;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, key mgmt and UID, but different BSSID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenBssidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.BSSID = TEST_BSSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, BSSID and UID, but different key mgmt.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenKeyMgmtIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, BSSID and key mgmt, but different UID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenUidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID,
                        TEST_PACKAGE_NAME);

        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID_OTHER,
                        TEST_PACKAGE_NAME);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID, BSSID and key mgmt, but different package name.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenPackageNameIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID, TEST_PACKAGE_NAME);

        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration, false, false, TEST_UID,
                        TEST_PACKAGE_NAME_OTHER);

        assertNotEquals(suggestion, suggestion1);
    }
}
