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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.net.MacAddress;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

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
        MacAddress macBeforeParcel = config.getOrCreateRandomizedMacAddress();
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
        assertEquals(macBeforeParcel, reconfig.getOrCreateRandomizedMacAddress());
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
    public void testNetworkSelectionStatusCopy() {
        NetworkSelectionStatus networkSelectionStatus = new NetworkSelectionStatus();
        networkSelectionStatus.setNotRecommended(true);

        NetworkSelectionStatus copy = new NetworkSelectionStatus();
        copy.copy(networkSelectionStatus);

        assertEquals(networkSelectionStatus.isNotRecommended(), copy.isNotRecommended());
    }

    @Test
    public void testNetworkSelectionStatusParcel() {
        NetworkSelectionStatus networkSelectionStatus = new NetworkSelectionStatus();
        networkSelectionStatus.setNotRecommended(true);

        Parcel parcelW = Parcel.obtain();
        networkSelectionStatus.writeToParcel(parcelW);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);

        NetworkSelectionStatus copy = new NetworkSelectionStatus();
        copy.readFromParcel(parcelR);

        assertEquals(networkSelectionStatus.isNotRecommended(), copy.isNotRecommended());
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
    public void testGetOrCreateRandomizedMacAddress_SavesAndReturnsSameAddress() {
        WifiConfiguration config = new WifiConfiguration();
        MacAddress defaultMac = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        assertEquals(defaultMac, config.getRandomizedMacAddress());

        MacAddress firstMacAddress = config.getOrCreateRandomizedMacAddress();
        MacAddress secondMacAddress = config.getOrCreateRandomizedMacAddress();

        assertNotEquals(defaultMac, firstMacAddress);
        assertEquals(firstMacAddress, secondMacAddress);
    }

    @Test
    public void testSetRandomizedMacAddress_ChangesSavedAddress() {
        WifiConfiguration config = new WifiConfiguration();
        MacAddress defaultMac = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        assertEquals(defaultMac, config.getRandomizedMacAddress());

        MacAddress macToChangeInto = MacAddress.createRandomUnicastAddress();
        config.setRandomizedMacAddress(macToChangeInto);
        MacAddress macAfterChange = config.getRandomizedMacAddress();

        assertEquals(macToChangeInto, macAfterChange);
    }

    @Test
    public void testGetOrCreateRandomizedMacAddress_ReRandomizesInvalidAddress() {
        WifiConfiguration config =  new WifiConfiguration();

        MacAddress defaultMac = MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);
        MacAddress macAddressZeroes = MacAddress.ALL_ZEROS_ADDRESS;
        MacAddress macAddressMulticast = MacAddress.fromString("03:ff:ff:ff:ff:ff");
        MacAddress macAddressGlobal = MacAddress.fromString("fc:ff:ff:ff:ff:ff");

        config.setRandomizedMacAddress(null);
        MacAddress macAfterChange = config.getOrCreateRandomizedMacAddress();
        assertNotEquals(macAfterChange, null);

        config.setRandomizedMacAddress(defaultMac);
        macAfterChange = config.getOrCreateRandomizedMacAddress();
        assertNotEquals(macAfterChange, defaultMac);

        config.setRandomizedMacAddress(macAddressZeroes);
        macAfterChange = config.getOrCreateRandomizedMacAddress();
        assertNotEquals(macAfterChange, macAddressZeroes);

        config.setRandomizedMacAddress(macAddressMulticast);
        macAfterChange = config.getOrCreateRandomizedMacAddress();
        assertNotEquals(macAfterChange, macAddressMulticast);

        config.setRandomizedMacAddress(macAddressGlobal);
        macAfterChange = config.getOrCreateRandomizedMacAddress();
        assertNotEquals(macAfterChange, macAddressGlobal);
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
     * Verifies that the serialization/de-serialization for softap config works.
     */
    @Test
    public void testSoftApConfigBackupAndRestore() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "TestAP";
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;
        config.apChannel = 40;
        config.allowedKeyManagement.set(KeyMgmt.WPA2_PSK);
        config.preSharedKey = "TestPsk";
        config.hiddenSSID = true;

        byte[] data = config.getBytesForBackup();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream in = new DataInputStream(bais);
        WifiConfiguration restoredConfig = WifiConfiguration.getWifiConfigFromBackup(in);

        assertEquals(config.SSID, restoredConfig.SSID);
        assertEquals(config.preSharedKey, restoredConfig.preSharedKey);
        assertEquals(config.getAuthType(), restoredConfig.getAuthType());
        assertEquals(config.apBand, restoredConfig.apBand);
        assertEquals(config.apChannel, restoredConfig.apChannel);
        assertEquals(config.hiddenSSID, restoredConfig.hiddenSSID);
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
}
