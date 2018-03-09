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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.os.Parcel;
import android.net.MacAddress;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiConfiguration}.
 */
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
            if (keyMgmt == WifiConfiguration.KeyMgmt.NONE) continue;
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
}
