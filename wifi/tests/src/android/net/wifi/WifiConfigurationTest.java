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

import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_EAP;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_OPEN;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_OSEN;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_OWE;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_PASSPOINT_R1_R2;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_PASSPOINT_R3;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_PSK;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_SAE;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_WAPI_CERT;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_WAPI_PSK;
import static android.net.wifi.WifiConfiguration.SECURITY_TYPE_WEP;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.wifi.WifiConfiguration.GroupCipher;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiConfiguration.PairwiseCipher;
import android.net.wifi.WifiConfiguration.Protocol;
import android.os.Parcel;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.net.module.util.MacAddressUtils;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Unit tests for {@link android.net.wifi.WifiConfiguration}.
 */
@SmallTest
public class WifiConfigurationTest {
    private static final String TEST_PASSPOINT_UNIQUE_ID = "uniqueId";
    private static final int TEST_CARRIER_ID = 1234;
    private static final int TEST_SUB_ID = 3;
    private static final String TEST_PACKAGE_NAME = "google.com";

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
        config.oemPaid = true;
        config.oemPrivate = true;
        config.carrierMerged = true;
        config.updateIdentifier = "1234";
        config.fromWifiNetworkSpecifier = true;
        config.fromWifiNetworkSuggestion = true;
        config.setRandomizedMacAddress(MacAddressUtils.createRandomUnicastAddress());
        MacAddress macBeforeParcel = config.getRandomizedMacAddress();
        config.subscriptionId = 1;
        config.carrierId = 1189;
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
        assertTrue(reconfig.fromWifiNetworkSpecifier);
        assertTrue(reconfig.fromWifiNetworkSuggestion);
        assertTrue(reconfig.oemPaid);
        assertTrue(reconfig.oemPrivate);
        assertTrue(reconfig.carrierMerged);

        Parcel parcelWW = Parcel.obtain();
        reconfig.writeToParcel(parcelWW, 0);
        byte[] rebytes = parcelWW.marshall();
        parcelWW.recycle();

        assertArrayEquals(bytes, rebytes);
    }

    @Test
    public void testWifiConfigurationCopyConstructor() {
        WifiConfiguration config = new WifiConfiguration();
        config.trusted = false;
        config.oemPaid = true;
        config.oemPrivate = true;
        config.carrierMerged = true;
        config.updateIdentifier = "1234";
        config.fromWifiNetworkSpecifier = true;
        config.fromWifiNetworkSuggestion = true;
        config.setRandomizedMacAddress(MacAddressUtils.createRandomUnicastAddress());
        MacAddress macBeforeParcel = config.getRandomizedMacAddress();
        config.subscriptionId = 1;
        config.carrierId = 1189;

        WifiConfiguration reconfig = new WifiConfiguration(config);

        // lacking a useful config.equals, check two fields near the end.
        assertEquals(macBeforeParcel, reconfig.getRandomizedMacAddress());
        assertEquals(config.updateIdentifier, reconfig.updateIdentifier);
        assertFalse(reconfig.trusted);
        assertTrue(reconfig.fromWifiNetworkSpecifier);
        assertTrue(reconfig.fromWifiNetworkSuggestion);
        assertTrue(reconfig.oemPaid);
        assertTrue(reconfig.oemPrivate);
        assertTrue(reconfig.carrierMerged);
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
        int[] securityTypes = new int [] {
                SECURITY_TYPE_WEP,
                SECURITY_TYPE_PSK,
                SECURITY_TYPE_EAP,
                SECURITY_TYPE_SAE,
                SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT,
                SECURITY_TYPE_WAPI_PSK,
                SECURITY_TYPE_WAPI_CERT,
                SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
                SECURITY_TYPE_OSEN,
        };
        for (int type: securityTypes) {
            WifiConfiguration config = new WifiConfiguration();
            config.setSecurityParams(type);
            config.wepKeys = null;

            assertFalse("Open network reported when security type was set to "
                            + type, config.isOpenNetwork());
        }
    }

    @Test
    public void testIsOpenNetwork_NotOpen_HasAuthTypeNoneAndMore() {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        config.wepKeys = null;
        config.convertLegacyFieldsToSecurityParamsIfNeeded();

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
     * Verifies that getNetworkKey returns the correct String for networks of
     * various different security types, the result should be stable.
     */
    @Test
    public void testGetNetworkKeyString() {
        WifiConfiguration config = new WifiConfiguration();
        final String mSsid = "TestAP";
        config.SSID = mSsid;

        // Test various combinations
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WPA_PSK],
                config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WPA_EAP],
                config.getNetworkKey());

        config.wepKeys[0] = "TestWep";
        config.allowedKeyManagement.clear();
        assertEquals(mSsid + "WEP", config.getNetworkKey());

        // set WEP key and give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 2;
        config.allowedKeyManagement.clear();
        assertEquals(mSsid + "WEP", config.getNetworkKey());

        // set WEP key but does not give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.OWE], config.getNetworkKey());

        config.wepKeys[0] = null;
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.OWE], config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SAE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.SAE], config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SUITE_B_192);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.SUITE_B_192],
                config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.NONE);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.NONE], config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_PSK);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WAPI_PSK],
                config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_CERT);
        assertEquals(mSsid + KeyMgmt.strings[KeyMgmt.WAPI_CERT],
                config.getNetworkKey());

        config.allowedKeyManagement.clear();
        config.setPasspointUniqueId(TEST_PASSPOINT_UNIQUE_ID);
        assertEquals(TEST_PASSPOINT_UNIQUE_ID, config.getNetworkKey());
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

        config.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT);

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
     * Ensure that {@link WifiConfiguration#setSecurityParams(int)} sets up the
     * {@link WifiConfiguration} object correctly for WPA3 Enterprise security type.
     * @throws Exception
     */
    @Test
    public void testSetSecurityParamsForWpa3Enterprise() throws Exception {
        WifiConfiguration config = new WifiConfiguration();

        config.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE);

        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP));
        assertTrue(config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP));
        assertTrue(config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256));
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

    @Test
    public void testNeedsPreSharedKey() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();

        configuration.setSecurityParams(SECURITY_TYPE_PSK);
        assertTrue(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_SAE);
        assertTrue(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_WAPI_PSK);
        assertTrue(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_OPEN);
        assertFalse(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_OWE);
        assertFalse(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_EAP);
        assertFalse(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE);
        assertFalse(configuration.needsPreSharedKey());

        configuration.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT);
        assertFalse(configuration.needsPreSharedKey());
    }

    @Test
    public void testGetAuthType() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();

        configuration.setSecurityParams(SECURITY_TYPE_PSK);
        assertEquals(KeyMgmt.WPA_PSK, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_SAE);
        assertEquals(KeyMgmt.SAE, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_WAPI_PSK);
        assertEquals(KeyMgmt.WAPI_PSK, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_OPEN);
        assertEquals(KeyMgmt.NONE, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_OWE);
        assertEquals(KeyMgmt.OWE, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_EAP);
        assertEquals(KeyMgmt.WPA_EAP, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE);
        assertEquals(KeyMgmt.WPA_EAP, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT);
        assertEquals(KeyMgmt.SUITE_B_192, configuration.getAuthType());

        configuration.setSecurityParams(SECURITY_TYPE_WAPI_CERT);
        assertEquals(KeyMgmt.WAPI_CERT, configuration.getAuthType());
    }

    @Test (expected = IllegalStateException.class)
    public void testGetAuthTypeFailure1() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();

        configuration.setSecurityParams(SECURITY_TYPE_PSK);
        configuration.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        configuration.getAuthType();
    }

    @Test (expected = IllegalStateException.class)
    public void testGetAuthTypeFailure2() throws Exception {
        WifiConfiguration configuration = new WifiConfiguration();

        configuration.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        configuration.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        configuration.allowedKeyManagement.set(KeyMgmt.SAE);
        configuration.getAuthType();
    }

    /**
     * Verifies that getProfileKey returns the correct String for networks of
     * various different security types, the result should be stable.
     */
    @Test
    public void testGetProfileKeyString() {
        WifiConfiguration config = new WifiConfiguration();
        final String mSsid = "TestAP";
        config.SSID = mSsid;
        config.carrierId = TEST_CARRIER_ID;
        config.subscriptionId = TEST_SUB_ID;
        config.creatorName = TEST_PACKAGE_NAME;


        // Test various combinations
        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WPA_PSK], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WPA_PSK], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WPA_EAP], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WPA_EAP], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.wepKeys[0] = "TestWep";
        config.allowedKeyManagement.clear();
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, "WEP", TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, "WEP", TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        // set WEP key and give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 2;
        config.allowedKeyManagement.clear();
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, "WEP", TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, "WEP", TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        // set WEP key but does not give a valid index.
        config.wepKeys[0] = null;
        config.wepKeys[2] = "TestWep";
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.OWE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.OWE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.wepKeys[0] = null;
        config.wepTxKeyIndex = 0;
        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.OWE);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.OWE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.OWE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SAE);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.SAE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.SAE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.SUITE_B_192);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.SUITE_B_192],
                TEST_PACKAGE_NAME, TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.SUITE_B_192],
                TEST_PACKAGE_NAME, TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.NONE);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.NONE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.NONE], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_PSK);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WAPI_PSK], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WAPI_PSK], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.allowedKeyManagement.set(KeyMgmt.WAPI_CERT);
        config.fromWifiNetworkSuggestion = false;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WAPI_CERT], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, false), config.getProfileKey());
        config.fromWifiNetworkSuggestion = true;
        assertEquals(createProfileKey(mSsid, KeyMgmt.strings[KeyMgmt.WAPI_CERT], TEST_PACKAGE_NAME,
                TEST_CARRIER_ID, TEST_SUB_ID, true), config.getProfileKey());

        config.allowedKeyManagement.clear();
        config.setPasspointUniqueId(TEST_PASSPOINT_UNIQUE_ID);
        assertEquals(TEST_PASSPOINT_UNIQUE_ID, config.getProfileKey());
    }

    private String createProfileKey(String ssid, String keyMgmt, String providerName,
            int carrierId, int subId, boolean isFromSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append(ssid).append(keyMgmt);
        if (isFromSuggestion) {
            sb.append("_").append(providerName).append('-')
                    .append(carrierId).append('-').append(subId);
        }
        return sb.toString();
    }

    private void verifyAllowedKeyManagement(WifiConfiguration config, int[] akms) {
        for (int akm: akms) {
            assertTrue(config.getSecurityParamsList().stream()
                    .anyMatch(params -> params.getAllowedKeyManagement().get(akm)));
        }
    }

    private void verifyAllowedProtocols(WifiConfiguration config, int[] aps) {
        for (int ap: aps) {
            assertTrue(config.getSecurityParamsList().stream()
                    .anyMatch(params -> params.getAllowedProtocols().get(ap)));
        }
    }

    private void verifyAllowedPairwiseCiphers(WifiConfiguration config, int[] apcs) {
        for (int apc: apcs) {
            assertTrue(config.getSecurityParamsList().stream()
                    .anyMatch(params -> params.getAllowedPairwiseCiphers().get(apc)));
        }
    }

    private void verifyAllowedGroupCiphers(WifiConfiguration config, int[] agcs) {
        for (int agc: agcs) {
            assertTrue(config.getSecurityParamsList().stream()
                    .anyMatch(params -> params.getAllowedGroupCiphers().get(agc)));
        }
    }

    /** Verify that adding security types works as expected. */
    @Test
    public void testAddSecurityTypes() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_WAPI_PSK);
        List<SecurityParams> paramsList = config.getSecurityParamsList();
        assertEquals(3, paramsList.size());

        verifyAllowedKeyManagement(config, new int[] {
                KeyMgmt.WPA_PSK, KeyMgmt.SAE, KeyMgmt.WAPI_PSK});
        verifyAllowedProtocols(config, new int[] {Protocol.WPA, Protocol.RSN, Protocol.WAPI});
        verifyAllowedPairwiseCiphers(config, new int[] {
                PairwiseCipher.CCMP, PairwiseCipher.TKIP,
                PairwiseCipher.GCMP_128, PairwiseCipher.GCMP_256,
                PairwiseCipher.SMS4});
        verifyAllowedGroupCiphers(config, new int[] {
                GroupCipher.CCMP, GroupCipher.TKIP,
                GroupCipher.GCMP_128, GroupCipher.GCMP_256,
                GroupCipher.SMS4});
    }

    /** Check that a personal security type can be added to a personal configuration. */
    @Test
    public void testAddPersonalTypeToPersonalConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
    }

    /** Check that an enterprise security type can be added to an enterprise configuration. */
    @Test
    public void testAddEnterpriseTypeToEnterpriseConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE);
    }

    /** Verify that adding an enterprise type to a personal configuration. */
    @Test (expected = IllegalArgumentException.class)
    public void testAddEnterpriseTypeToPersonalConfig() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
    }

    /** Verify that adding a personal type to an enterprise configuration. */
    @Test (expected = IllegalArgumentException.class)
    public void testAddPersonalTypeToEnterpriseConfig() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
    }

    /** Check that an open security cannot be added to a non-open configuration. */
    @Test(expected = IllegalArgumentException.class)
    public void testAddOpenTypeToNonOpenConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
    }

    /** Check that a non-open security cannot be added to an open configuration. */
    @Test(expected = IllegalArgumentException.class)
    public void testAddNonOpenTypeToOpenConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
    }

    /** Check that a OSEN security cannot be added as additional type. */
    @Test(expected = IllegalArgumentException.class)
    public void testAddOsenTypeToConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_OSEN);
    }

    /** Verify that adding duplicate security types raises the exception. */
    @Test (expected = IllegalArgumentException.class)
    public void testAddDuplicateSecurityTypes() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
    }

    /** Verify that adding duplicate security params raises the exception. */
    @Test (expected = IllegalArgumentException.class)
    public void testAddDuplicateSecurityParams() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
    }

    /** Verify that Suite-B type works as expected. */
    @Test
    public void testAddSuiteBSecurityType() {
        WifiConfiguration config = new WifiConfiguration();
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        config.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.NONE);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT);

        assertFalse(config.isSuiteBCipherEcdheRsaEnabled());
        config.enableSuiteBCiphers(false, true);
        assertTrue(config.isSuiteBCipherEcdheRsaEnabled());
    }

    /** Verify that FILS bit can be set correctly. */
    @Test
    public void testFilsKeyMgmt() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);

        config.enableFils(false, true);
        assertFalse(config.isFilsSha256Enabled());
        assertTrue(config.isFilsSha384Enabled());
    }

    /** Verify that SAE mode can be configured correctly. */
    @Test
    public void testSaeTypeMethods() {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.addSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);

        SecurityParams saeParams = config.getSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
        assertNotNull(saeParams);
        assertFalse(saeParams.isSaeH2eOnlyMode());
        assertFalse(saeParams.isSaePkOnlyMode());

        config.enableSaeH2eOnlyMode(true);
        config.enableSaePkOnlyMode(true);

        saeParams = config.getSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
        assertNotNull(saeParams);
        assertTrue(saeParams.isSaeH2eOnlyMode());
        assertTrue(saeParams.isSaePkOnlyMode());
    }

    /** Verify the legacy configuration conversion */
    @Test
    public void testLegacyConfigurationConversion() {
        Pair[] keyMgmtSecurityTypePairs = new Pair[] {
                new Pair<>(KeyMgmt.WAPI_CERT, SECURITY_TYPE_WAPI_CERT),
                new Pair<>(KeyMgmt.WAPI_PSK, SECURITY_TYPE_WAPI_PSK),
                new Pair<>(KeyMgmt.SUITE_B_192, SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT),
                new Pair<>(KeyMgmt.OWE, SECURITY_TYPE_OWE),
                new Pair<>(KeyMgmt.SAE, SECURITY_TYPE_SAE),
                new Pair<>(KeyMgmt.OSEN, SECURITY_TYPE_OSEN),
                new Pair<>(KeyMgmt.WPA2_PSK, SECURITY_TYPE_PSK),
                new Pair<>(KeyMgmt.WPA_EAP, SECURITY_TYPE_EAP),
                new Pair<>(KeyMgmt.WPA_PSK, SECURITY_TYPE_PSK),
                new Pair<>(KeyMgmt.NONE, SECURITY_TYPE_OPEN),
        };

        for (Pair pair: keyMgmtSecurityTypePairs) {
            WifiConfiguration config = new WifiConfiguration();
            config.allowedKeyManagement.set((int) pair.first);
            config.convertLegacyFieldsToSecurityParamsIfNeeded();
            assertNotNull(config.getSecurityParams((int) pair.second));
        }

        // If none of key management is set, it should be open.
        WifiConfiguration emptyConfig = new WifiConfiguration();
        emptyConfig.convertLegacyFieldsToSecurityParamsIfNeeded();
        assertNotNull(emptyConfig.getSecurityParams(SECURITY_TYPE_OPEN));

        // If EAP key management is set and requirePmf is true, it is WPA3 Enterprise.
        WifiConfiguration wpa3EnterpriseConfig = new WifiConfiguration();
        wpa3EnterpriseConfig.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
        wpa3EnterpriseConfig.requirePmf = true;
        wpa3EnterpriseConfig.convertLegacyFieldsToSecurityParamsIfNeeded();
        assertNotNull(wpa3EnterpriseConfig.getSecurityParams(SECURITY_TYPE_EAP_WPA3_ENTERPRISE));

        // If key management is NONE and wep key is set, it is WEP type.
        WifiConfiguration wepConfig = new WifiConfiguration();
        wepConfig.allowedKeyManagement.set(KeyMgmt.NONE);
        wepConfig.wepKeys = new String[] {"\"abcdef\""};
        wepConfig.convertLegacyFieldsToSecurityParamsIfNeeded();
        assertNotNull(wepConfig.getSecurityParams(SECURITY_TYPE_WEP));
    }

    /** Verify the set security params by SecurityParams objects. */
    @Test
    public void testSetBySecurityParamsObject() {
        int[] securityTypes = new int[] {
                SECURITY_TYPE_WAPI_CERT,
                SECURITY_TYPE_WAPI_PSK,
                SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT,
                SECURITY_TYPE_OWE,
                SECURITY_TYPE_SAE,
                SECURITY_TYPE_OSEN,
                SECURITY_TYPE_EAP,
                SECURITY_TYPE_PSK,
                SECURITY_TYPE_OPEN,
                SECURITY_TYPE_PASSPOINT_R1_R2,
                SECURITY_TYPE_PASSPOINT_R3,
        };
        for (int type: securityTypes) {
            WifiConfiguration config = new WifiConfiguration();
            config.setSecurityParams(type);
            assertTrue(config.isSecurityType(type));
            assertNotNull(config.getSecurityParams(type));
        }
    }
}
