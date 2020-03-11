/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_EAP_SUITE_B;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_OWE;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_SAE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.util.MacAddressUtils;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiConfiguration}.
 */
@SmallTest
public class WifiConfigurationTest {

    @Before
    public void setUp() {
    }

    /**
     * Check that parcel marshalling/unmarshalling works
     *
     * Create and populate a WifiConfiguration.
     * Marshall and unmashall it, and expect to recover a copy of the original.
     * Marshall the resulting object, and expect the bytes to match the
     * first marshall result.
     */
    @Test
    public void testWifiConfigurationParcel() {
        String cookie = "C O.o |<IE";
        WifiConfiguration config = new WifiConfiguration();
        config.setPasspointManagementObjectTree(cookie);
        config.trusted = false;
        config.updateIdentifier = "1234";
        config.fromWifiNetworkSpecifier = true;
        config.fromWifiNetworkSuggestion = true;
        config.setRandomizedMacAddress(MacAddressUtils.createRandomUnicastAddress());
        MacAddress macBeforeParcel = config.getRandomizedMacAddress();
        Parcel parcelW = Parcel.obtain();
        config.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiConfiguration reconfig = WifiConfiguration.CREATOR.createFromParcel(parcelR);

        // lacking a useful config.equals, check two fields near the end.
        assertEquals(cookie, reconfig.getMoTree());
        assertEquals(macBeforeParcel, reconfig.getRandomizedMacAddress());
        assertEquals(config.updateIdentifier, reconfig.updateIdentifier);
        assertFalse(reconfig.trusted);
        assertTrue(config.fromWifiNetworkSpecifier);
        assertTrue(config.fromWifiNetworkSuggestion);

        Parcel parcelWW = Parcel.obtain();
        reconfig.writeToParcel(parcelWW, 0);
        byte[] rebytes = parcelWW.marshall();
        parcelWW.recycle();

        assertArrayEquals(bytes, rebytes);
    }

    @Test
    public void testIsOpenNetwork_IsOpen_NullWepKeys() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = null;

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_IsOpen_ZeroLengthWepKeysArray() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[0];

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_IsOpen_NullWepKeysArray() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[1];

        assertTrue(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasWepKeys() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[] {"test"};

        assertFalse(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasNullWepKeyFollowedByNonNullKey() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.clear();
        config.wepKeys = new String[] {null, null, "test"};

        assertFalse(config.isOpenNetwork());
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasAuthType() {
        for (int keyMgmt = 0; keyMgmt < WifiConfiguration.KeyMgmt.strings.length; keyMgmt++) {
            if (keyMgmt == WifiConfiguration.KeyMgmt.NONE
                    || keyMgmt == WifiConfiguration.KeyMgmt.OWE) {
                continue;
            }
            WifiConfiguration config = new WifiConfiguration();
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(keyMgmt);
            config.wepKeys = null;

            assertFalse("Open network reported when key mgmt was set to "
                            + WifiConfiguration.KeyMgmt.strings[keyMgmt], config.isOpenNetwork());
        }
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasAuthTypeNoneAndMore() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        config.wepKeys = null;

        assertFalse(config.isOpenNetwork());
    }

    @Test
    public void testSetRandomizedMacAddress_ChangesSavedAddress() {
        WifiConfiguration config = new WifiConfiguration();
        MacAddress defaultMac = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        assertEquals(defaultMac, config.getRandomizedMacAddress());

        MacAddress macToChangeInto = MacAddressUtils.createRandomUnicastAddress();
        config.setRandomizedMacAddress(macToChangeInto);
        MacAddress macAfterChange = config.getRandomizedMacAddress();

        assertEquals(macToChangeInto, macAfterChange);
    }

    @Test
    public void testSetRandomizedMacAddress_DoesNothingWhenNull() {
        WifiConfiguration config = new WifiConfiguration();
        MacAddress defaultMac = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        config.setRandomizedMacAddress(null);
        assertEquals(defaultMac, config.getRandomizedMacAddress());
    }

    /**
     * Verifies that updateIdentifier should be copied for copy constructor.
     */
    @Test
    public void testUpdateIdentifierForCopyConstructor() {
        WifiConfiguration config = new WifiConfiguration();
        config.updateIdentifier = "1234";
        WifiConfiguration copyConfig = new WifiConfiguration(config);

        assertEquals(config.updateIdentifier, copyConfig.updateIdentifier);
    }

    /**
     * Verifies that getKeyIdForCredentials returns the expected string for Enterprise networks
     * @throws Exception
     */
    @Test
    public void testGetKeyIdForCredentials() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        final String mSsid = "TestAP";
        config.SSID = mSsid;

        // Test various combinations
        // EAP with TLS
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        String keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_WPA_EAP_TLS_NULL");

        // EAP with TTLS & MSCHAPv2
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_WPA_EAP_TTLS_MSCHAPV2");

        // Suite-B 192 with PWD & GTC
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SUITE_B_192);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PWD);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_SUITE_B_192_PWD_GTC");

        // IEEE8021X with SIM
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_IEEE8021X_SIM_NULL");

        // Try calling this method with non-Enterprise network, expect an exception
        boolean exceptionThrown = false;
        try {
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
            config.preSharedKey = "TestPsk";
            keyId = config.getKeyIdForCredentials(config);
        } catch (IllegalStateException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
    }

    /**
     * Verifies that getKeyIdForCredentials returns the expected string for Suggestion Enterprise
     * networks
     * @throws Exception
     */
    @Test
    public void testGetKeyIdForCredentialsForSuggestion() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        final String mSsid = "TestAP";
        final String packageName = "TestApp";
        final String bSsid = MacAddressUtils.createRandomUnicastAddress().toString();
        String suggestionSuffix = "_" + bSsid + "_" + packageName;
        config.SSID = mSsid;
        config.fromWifiNetworkSuggestion = true;
        config.creatorName = packageName;
        config.BSSID = bSsid;

        // Test various combinations
        // EAP with TLS
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        String keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_WPA_EAP_TLS_NULL" + suggestionSuffix);

        // EAP with TTLS & MSCHAPv2
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_WPA_EAP_TTLS_MSCHAPV2" + suggestionSuffix);

        // Suite-B 192 with PWD & GTC
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SUITE_B_192);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PWD);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_SUITE_B_192_PWD_GTC" + suggestionSuffix);

        // IEEE8021X with SIM
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        keyId = config.getKeyIdForCredentials(config);
        assertEquals(keyId, mSsid + "_IEEE8021X_SIM_NULL" + suggestionSuffix);

        // Try calling this method with non-Enterprise network, expect an exception
        boolean exceptionThrown = false;
        try {
            config.allowedKeyManagement.clear();
            config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
            config.preSharedKey = "TestPsk";
            keyId = config.getKeyIdForCredentials(config);
        } catch (IllegalStateException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
    }

    /**
     * Verifies that getSsidAndSecurityTypeString returns the correct String for networks of
     * various different security types
     */
    @Test
    public void testGetSsidAndSecurityTypeString() {
        WifiConfiguration config = new WifiConfiguration();
        final String mSsid = "TestAP";
        config.SSID = mSsid;

        // Test various combinations
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WPA_PSK],
                config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WPA_EAP],
                config.getSsidAndSecurityTypeString());

        config.wepKeys[0] = "TestWep";
        config.allowedKeyManagement.clear();
        assertEquals(mSsid + "WEP", config.getSsidAndSecurityTypeString());

        // set WEP key and give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 2;
        config.allowedKeyManagement.clear();
        assertEquals(mSsid + "WEP", config.getSsidAndSecurityTypeString());

        // set WEP key but does not give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.OWE], config.getSsidAndSecurityTypeString());

        config.wepKeys[0] = null;
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.OWE], config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SAE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.SAE], config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SUITE_B_192);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.SUITE_B_192],
                config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.NONE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.NONE], config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_PSK);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WAPI_PSK],
                config.getSsidAndSecurityTypeString());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_CERT);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WAPI_CERT],
                config.getSsidAndSecurityTypeString());
    }

    /**
     * Ensure that the {@link NetworkSelectionStatus.DisableReasonInfo}s are populated in
     * {@link NetworkSelectionStatus#DISABLE_REASON_INFOS} for reason codes from 0 to
     * {@link NetworkSelectionStatus#NETWORK_SELECTION_DISABLED_MAX} - 1.
     */
    @Test
    public void testNetworkSelectionDisableReasonInfosPopulated() {
        assertEquals(NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX,
                NetworkSelectionStatus.DISABLE_REASON_INFOS.size());
        for (int i = 0; i < NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX; i++) {
            assertNotNull(NetworkSelectionStatus.DISABLE_REASON_INFOS.get(i));
        }
    }

    /**
     * Ensure that {@link NetworkSelectionStatus#getMaxNetworkSelectionDisableReason()} returns
     * the maximum disable reason.
     */
    @Test
    public void testNetworkSelectionGetMaxNetworkSelectionDisableReason() {
        int maxReason = Integer.MIN_VALUE;
        for (int i = 0; i < NetworkSelectionStatus.DISABLE_REASON_INFOS.size(); i++) {
            int reason = NetworkSelectionStatus.DISABLE_REASON_INFOS.keyAt(i);
            maxReason = Math.max(maxReason, reason);
        }
        assertEquals(maxReason, NetworkSelectionStatus.getMaxNetworkSelectionDisableReason());
    }

    /**
     * Ensure that {@link WifiConfiguration#setSecurityParams(int)} sets up the
     * {@link WifiConfiguration} object correctly for SAE security type.
     * @throws Exception
     */
    @Test
    public void testSetSecurityParamsForSae() throws Exception {
        WifiConfiguration config = new WifiConfiguration();

        config.setSecurityParams(SECURITY_TYPE_SAE);

        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256));
        assertTrue(config.requirePmf);
    }

    /**
     * Ensure that {@link WifiConfiguration#setSecurityParams(int)} sets up the
     * {@link WifiConfiguration} object correctly for OWE security type.
     * @throws Exception
     */
    @Test
    public void testSetSecurityParamsForOwe() throws Exception {
        WifiConfiguration config = new WifiConfiguration();

        config.setSecurityParams(SECURITY_TYPE_OWE);

        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256));
        assertTrue(config.requirePmf);
    }

    /**
     * Ensure that {@link WifiConfiguration#setSecurityParams(int)} sets up the
     * {@link WifiConfiguration} object correctly for Suite-B security type.
     * @throws Exception
     */
    @Test
    public void testSetSecurityParamsForSuiteB() throws Exception {
        WifiConfiguration config = new WifiConfiguration();

        config.setSecurityParams(SECURITY_TYPE_EAP_SUITE_B);

        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192));
        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256));
        assertTrue(config.allowedGroupManagementCiphers
                .get(WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256));
        assertTrue(config.requirePmf);
    }

    /**
     * Test that the NetworkSelectionStatus Builder returns the same values that was set, and that
     * calling build multiple times returns different instances.
     */
    @Test
    public void testNetworkSelectionStatusBuilder() throws Exception {
        NetworkSelectionStatus.Builder builder = new NetworkSelectionStatus.Builder()
                .setNetworkSelectionDisableReason(
                        NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION)
                .setNetworkSelectionStatus(
                        NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);

        NetworkSelectionStatus status1 = builder.build();

        assertEquals(NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION,
                status1.getNetworkSelectionDisableReason());
        assertEquals(NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED,
                status1.getNetworkSelectionStatus());

        NetworkSelectionStatus status2 = builder
                .setNetworkSelectionDisableReason(NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD)
                .build();

        // different instances
        assertNotSame(status1, status2);

        // assert that status1 didn't change
        assertEquals(NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION,
                status1.getNetworkSelectionDisableReason());
        assertEquals(NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED,
                status1.getNetworkSelectionStatus());

        // assert that status2 changed
        assertEquals(NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD,
                status2.getNetworkSelectionDisableReason());
        assertEquals(NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED,
                status2.getNetworkSelectionStatus());
    }
}
