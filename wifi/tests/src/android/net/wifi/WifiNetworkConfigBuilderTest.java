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
import android.net.NetworkSpecifier;
import android.os.PatternMatcher;
import android.os.Process;
import android.support.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkConfigBuilder}.
 */
@SmallTest
public class WifiNetworkConfigBuilderTest {
    private static final String TEST_SSID = "Test123";
    private static final String TEST_BSSID_OUI_BASE_ADDRESS = "12:12:12:00:00:00";
    private static final String TEST_BSSID_OUI_MASK = "ff:ff:ff:00:00:00";
    private static final String TEST_BSSID = "12:12:12:12:12:12";
    private static final String TEST_PRESHARED_KEY = "Test123";

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} for open network with SSID pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForOpenNetworkWithSsidPattern() {
        NetworkSpecifier specifier = new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_PREFIX))
                .buildNetworkSpecifier();

        assertTrue(specifier instanceof WifiNetworkSpecifier);
        WifiNetworkSpecifier wifiNetworkSpecifier = (WifiNetworkSpecifier) specifier;

        assertEquals(Process.myUid(), wifiNetworkSpecifier.requestorUid);
        assertEquals(TEST_SSID, wifiNetworkSpecifier.ssidPatternMatcher.getPath());
        assertEquals(PATTERN_PREFIX, wifiNetworkSpecifier.ssidPatternMatcher.getType());
        assertEquals(MacAddress.ALL_ZEROS_ADDRESS, wifiNetworkSpecifier.bssidPatternMatcher.first);
        assertEquals(MacAddress.ALL_ZEROS_ADDRESS, wifiNetworkSpecifier.bssidPatternMatcher.second);
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedKeyManagement
                .get(WifiConfiguration.KeyMgmt.NONE));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedProtocols
                .get(WifiConfiguration.Protocol.RSN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedAuthAlgorithms
                .get(WifiConfiguration.AuthAlgorithm.OPEN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.TKIP));
    }

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} for WPA_PSK network with BSSID
     * pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForWpaPskNetworkWithBssidPattern() {
        NetworkSpecifier specifier = new WifiNetworkConfigBuilder()
                .setBssidPattern(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK))
                .setPskPassphrase(TEST_PRESHARED_KEY)
                .buildNetworkSpecifier();

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
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedProtocols
                .get(WifiConfiguration.Protocol.RSN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedAuthAlgorithms
                .get(WifiConfiguration.AuthAlgorithm.OPEN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.TKIP));
        assertEquals("\"" + TEST_PRESHARED_KEY + "\"",
                wifiNetworkSpecifier.wifiConfiguration.preSharedKey);
    }

    /**
     * Validate correctness of WifiNetworkSpecifier object created by
     * {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} for WPA_EAP network with
     * SSID and BSSID pattern.
     */
    @Test
    public void testWifiNetworkSpecifierBuilderForEnterpriseHiddenNetworkWithSsidAndBssid() {
        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);

        NetworkSpecifier specifier = new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.fromString(TEST_BSSID))
                .setEnterpriseConfig(enterpriseConfig)
                .setIsHiddenSsid()
                .buildNetworkSpecifier();

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
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedProtocols
                .get(WifiConfiguration.Protocol.RSN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedAuthAlgorithms
                .get(WifiConfiguration.AuthAlgorithm.OPEN));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedPairwiseCiphers
                .get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.allowedGroupCiphers
                .get(WifiConfiguration.GroupCipher.TKIP));
        assertTrue(wifiNetworkSpecifier.wifiConfiguration.hiddenSSID);
        assertEquals(enterpriseConfig.getEapMethod(),
                wifiNetworkSpecifier.wifiConfiguration.enterpriseConfig.getEapMethod());
        assertEquals(enterpriseConfig.getPhase2Method(),
                wifiNetworkSpecifier.wifiConfiguration.enterpriseConfig.getPhase2Method());
    }


    /**
     * Ensure {@link WifiNetworkConfigBuilder#setSsid(String)} throws an exception
     * when the string is not Unicode.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSetSsidWithNonUnicodeString() {
        new WifiNetworkConfigBuilder()
                .setSsid("\ud800")
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#setPskPassphrase(String)} throws an exception
     * when the string is not ASCII encodable.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSetPskPassphraseWithNonAsciiString() {
        new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setPskPassphrase("salvē")
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when neither SSID nor BSSID patterns were set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithNoSsidAndBssidPattern() {
        new WifiNetworkConfigBuilder().buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern1() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(".*", PatternMatcher.PATTERN_SIMPLE_GLOB))
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern2() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(".*", PatternMatcher.PATTERN_ADVANCED_GLOB))
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-all SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllSsidPattern3() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher("", PatternMatcher.PATTERN_PREFIX))
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-all BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchAllBssidPattern() {
        new WifiNetworkConfigBuilder()
                .setBssidPattern(MacAddress.ALL_ZEROS_ADDRESS, MacAddress.ALL_ZEROS_ADDRESS)
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-none SSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneSsidPattern() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher("", PatternMatcher.PATTERN_LITERAL))
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when match-none BSSID pattern is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMatchNoneBssidPattern() {
        new WifiNetworkConfigBuilder()
                .setBssidPattern(MacAddress.BROADCAST_ADDRESS, MacAddress.BROADCAST_ADDRESS)
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when SSID pattern is set for hidden network.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBssidMatchPatternForHiddenNetwork() {
        new WifiNetworkConfigBuilder()
                .setBssidPattern(MacAddress.fromString(TEST_BSSID_OUI_BASE_ADDRESS),
                        MacAddress.fromString(TEST_BSSID_OUI_MASK))
                .setIsHiddenSsid()
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when both {@link WifiNetworkConfigBuilder#setPskPassphrase(String)} and
     * {@link WifiNetworkConfigBuilder#setEnterpriseConfig(WifiEnterpriseConfig)} are invoked.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithBothPskPassphraseAndEnterpriseConfig() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setPskPassphrase(TEST_PRESHARED_KEY)
                .setEnterpriseConfig(new WifiEnterpriseConfig())
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when SSID pattern is set for hidden network.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithSsidMatchPatternForHiddenNetwork() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PatternMatcher.PATTERN_PREFIX))
                .setIsHiddenSsid()
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setIsAppInteractionRequired()} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithRequiredAppInteraction() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setIsAppInteractionRequired()
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setIsUserInteractionRequired()} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithRequiredUserInteraction() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setIsUserInteractionRequired()
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setPriority(int)} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithSetPriority() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setPriority(4)
                .buildNetworkSpecifier();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSpecifier()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setIsMetered()} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSpecifierBuilderWithMetered() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_LITERAL))
                .setIsMetered()
                .buildNetworkSpecifier();
    }

    /**
     * Validate correctness of WifiNetworkSuggestion object created by
     * {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} for Open network which requires
     * app interaction.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForOpenNetworkWithReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setIsAppInteractionRequired()
                .buildNetworkSuggestion();

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
     * {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} for WPA_EAP network which requires
     * app interaction and has a priority of zero set.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpaEapNetworkWithPriorityAndReqAppInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setPskPassphrase(TEST_PRESHARED_KEY)
                .setIsAppInteractionRequired()
                .setPriority(0)
                .buildNetworkSuggestion();

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
     * {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} for WPA_PSK network which requires
     * user interaction and is metered.
     */
    @Test
    public void testWifiNetworkSuggestionBuilderForWpaPskNetworkWithMeteredAndReqUserInteraction() {
        WifiNetworkSuggestion suggestion = new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setPskPassphrase(TEST_PRESHARED_KEY)
                .setIsUserInteractionRequired()
                .setIsMetered()
                .buildNetworkSuggestion();

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
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setSsidPattern(PatternMatcher)} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithSsidPattern() {
        new WifiNetworkConfigBuilder()
                .setSsidPattern(new PatternMatcher(TEST_SSID, PATTERN_PREFIX))
                .buildNetworkSuggestion();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setBssid(MacAddress)} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBssidPattern() {
        new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setBssidPattern(MacAddress.fromString(TEST_BSSID),
                        MacAddress.fromString(TEST_BSSID))
                .buildNetworkSuggestion();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setBssidPattern(MacAddress, MacAddress)} is set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithBssid() {
        new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setBssid(MacAddress.fromString(TEST_BSSID))
                .buildNetworkSuggestion();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#buildNetworkSuggestion()} throws an exception
     * when {@link WifiNetworkConfigBuilder#setSsid(String)} is not set.
     */
    @Test(expected = IllegalStateException.class)
    public void testWifiNetworkSuggestionBuilderWithNoSsid() {
        new WifiNetworkConfigBuilder()
                .buildNetworkSuggestion();
    }

    /**
     * Ensure {@link WifiNetworkConfigBuilder#setPriority(int)} throws an exception
     * when the value is negative.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testWifiNetworkSuggestionBuilderWithInvalidPriority() {
        new WifiNetworkConfigBuilder()
                .setSsid(TEST_SSID)
                .setPriority(-1)
                .buildNetworkSuggestion();
    }
}
