/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNull;

import android.net.MacAddress;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@SmallTest
public class SoftApConfigurationTest {
    private static final String TEST_CHAR_SET_AS_STRING = "abcdefghijklmnopqrstuvwxyz0123456789";

    private SoftApConfiguration parcelUnparcel(SoftApConfiguration configIn) {
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(configIn, 0);
        parcel.setDataPosition(0);
        SoftApConfiguration configOut =
                parcel.readParcelable(SoftApConfiguration.class.getClassLoader());
        parcel.recycle();
        return configOut;
    }

    /**
     * Helper method to generate random string.
     *
     * Note: this method has limited use as a random string generator.
     * The characters used in this method do no not cover all valid inputs.
     * @param length number of characters to generate for the string
     * @return String generated string of random characters
     */
    private String generateRandomString(int length) {
        Random random = new Random();
        StringBuilder stringBuilder = new StringBuilder(length);
        int index = -1;
        while (stringBuilder.length() < length) {
            index = random.nextInt(TEST_CHAR_SET_AS_STRING.length());
            stringBuilder.append(TEST_CHAR_SET_AS_STRING.charAt(index));
        }
        return stringBuilder.toString();
    }

    @Test
    public void testBasicSettings() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setSsid("ssid")
                .setBssid(MacAddress.fromString("11:22:33:44:55:66"))
                .build();
        assertThat(original.getSsid()).isEqualTo("ssid");
        assertThat(original.getBssid()).isEqualTo(MacAddress.fromString("11:22:33:44:55:66"));
        assertThat(original.getPassphrase()).isNull();
        assertThat(original.getSecurityType()).isEqualTo(SoftApConfiguration.SECURITY_TYPE_OPEN);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_2GHZ);
        assertThat(original.getChannel()).isEqualTo(0);
        assertThat(original.isHiddenSsid()).isEqualTo(false);
        assertThat(original.getMaxNumberOfClients()).isEqualTo(0);

        SoftApConfiguration unparceled = parcelUnparcel(original);
        assertThat(unparceled).isNotSameAs(original);
        assertThat(unparceled).isEqualTo(original);
        assertThat(unparceled.hashCode()).isEqualTo(original.hashCode());

        SoftApConfiguration copy = new SoftApConfiguration.Builder(original).build();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    }

    @Test
    public void testWpa2() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();
        assertThat(original.getPassphrase()).isEqualTo("secretsecret");
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_2GHZ);
        assertThat(original.getChannel()).isEqualTo(0);
        assertThat(original.isHiddenSsid()).isEqualTo(false);
        assertThat(original.getMaxNumberOfClients()).isEqualTo(0);

        SoftApConfiguration unparceled = parcelUnparcel(original);
        assertThat(unparceled).isNotSameAs(original);
        assertThat(unparceled).isEqualTo(original);
        assertThat(unparceled.hashCode()).isEqualTo(original.hashCode());

        SoftApConfiguration copy = new SoftApConfiguration.Builder(original).build();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    }

    @Test
    public void testWpa2WithAllFieldCustomized() {
        List<MacAddress> testBlockedClientList = new ArrayList<>();
        List<MacAddress> testAllowedClientList = new ArrayList<>();
        testBlockedClientList.add(MacAddress.fromString("11:22:33:44:55:66"));
        testAllowedClientList.add(MacAddress.fromString("aa:bb:cc:dd:ee:ff"));
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .setMaxNumberOfClients(10)
                .setAutoShutdownEnabled(true)
                .setShutdownTimeoutMillis(500000)
                .setClientControlByUserEnabled(true)
                .setBlockedClientList(testBlockedClientList)
                .setAllowedClientList(testAllowedClientList)
                .build();
        assertThat(original.getPassphrase()).isEqualTo("secretsecret");
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_5GHZ);
        assertThat(original.getChannel()).isEqualTo(149);
        assertThat(original.isHiddenSsid()).isEqualTo(true);
        assertThat(original.getMaxNumberOfClients()).isEqualTo(10);
        assertThat(original.isAutoShutdownEnabled()).isEqualTo(true);
        assertThat(original.getShutdownTimeoutMillis()).isEqualTo(500000);
        assertThat(original.isClientControlByUserEnabled()).isEqualTo(true);
        assertThat(original.getBlockedClientList()).isEqualTo(testBlockedClientList);
        assertThat(original.getAllowedClientList()).isEqualTo(testAllowedClientList);

        SoftApConfiguration unparceled = parcelUnparcel(original);
        assertThat(unparceled).isNotSameAs(original);
        assertThat(unparceled).isEqualTo(original);
        assertThat(unparceled.hashCode()).isEqualTo(original.hashCode());

        SoftApConfiguration copy = new SoftApConfiguration.Builder(original).build();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    }

    @Test
    public void testWpa3Sae() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret", SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
        assertThat(original.getPassphrase()).isEqualTo("secretsecret");
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_5GHZ);
        assertThat(original.getChannel()).isEqualTo(149);
        assertThat(original.isHiddenSsid()).isEqualTo(true);


        SoftApConfiguration unparceled = parcelUnparcel(original);
        assertThat(unparceled).isNotSameAs(original);
        assertThat(unparceled).isEqualTo(original);
        assertThat(unparceled.hashCode()).isEqualTo(original.hashCode());

        SoftApConfiguration copy = new SoftApConfiguration.Builder(original).build();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    }

    @Test
    public void testWpa3SaeTransition() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret",
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION);
        assertThat(original.getPassphrase()).isEqualTo("secretsecret");
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_5GHZ);
        assertThat(original.getChannel()).isEqualTo(149);
        assertThat(original.isHiddenSsid()).isEqualTo(true);


        SoftApConfiguration unparceled = parcelUnparcel(original);
        assertThat(unparceled).isNotSameAs(original);
        assertThat(unparceled).isEqualTo(original);
        assertThat(unparceled.hashCode()).isEqualTo(original.hashCode());

        SoftApConfiguration copy = new SoftApConfiguration.Builder(original).build();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy).isEqualTo(original);
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidShortPasswordLengthForWpa2() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase(generateRandomString(SoftApConfiguration.PSK_MIN_LEN - 1),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidLongPasswordLengthForWpa2() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase(generateRandomString(SoftApConfiguration.PSK_MAX_LEN + 1),
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidShortPasswordLengthForWpa3SaeTransition() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase(generateRandomString(SoftApConfiguration.PSK_MIN_LEN - 1),
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidLongPasswordLengthForWpa3SaeTransition() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setPassphrase(generateRandomString(SoftApConfiguration.PSK_MAX_LEN + 1),
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalieShutdownTimeoutMillis() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setShutdownTimeoutMillis(-1)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetClientListExceptionWhenExistMacAddressInBothList() {
        final MacAddress testMacAddress_1 = MacAddress.fromString("22:33:44:55:66:77");
        final MacAddress testMacAddress_2 = MacAddress.fromString("aa:bb:cc:dd:ee:ff");
        ArrayList<MacAddress> testAllowedClientList = new ArrayList<>();
        testAllowedClientList.add(testMacAddress_1);
        testAllowedClientList.add(testMacAddress_2);
        ArrayList<MacAddress> testBlockedClientList = new ArrayList<>();
        testBlockedClientList.add(testMacAddress_1);
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setBlockedClientList(testBlockedClientList)
                .setAllowedClientList(testAllowedClientList)
                .build();
    }

    @Test
    public void testToWifiConfigurationWithUnsupportedParameter() {
        SoftApConfiguration sae_config = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret", SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
                .build();

        assertNull(sae_config.toWifiConfiguration());
        SoftApConfiguration band_6g_config = new SoftApConfiguration.Builder()
                .setBand(SoftApConfiguration.BAND_6GHZ)
                .build();

        assertNull(band_6g_config.toWifiConfiguration());
        SoftApConfiguration sae_transition_config = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret",
                        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                .build();

        assertNull(sae_transition_config.toWifiConfiguration());
    }

    @Test
    public void testToWifiConfigurationWithSupportedParameter() {
        SoftApConfiguration softApConfig_2g = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret",
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setChannel(11, SoftApConfiguration.BAND_2GHZ)
                .setHiddenSsid(true)
                .build();
        WifiConfiguration wifiConfig_2g = softApConfig_2g.toWifiConfiguration();
        assertThat(wifiConfig_2g.getAuthType()).isEqualTo(WifiConfiguration.KeyMgmt.WPA2_PSK);
        assertThat(wifiConfig_2g.preSharedKey).isEqualTo("secretsecret");
        assertThat(wifiConfig_2g.apBand).isEqualTo(WifiConfiguration.AP_BAND_2GHZ);
        assertThat(wifiConfig_2g.apChannel).isEqualTo(11);
        assertThat(wifiConfig_2g.hiddenSSID).isEqualTo(true);

        SoftApConfiguration softApConfig_5g = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret",
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
        WifiConfiguration wifiConfig_5g = softApConfig_5g.toWifiConfiguration();
        assertThat(wifiConfig_5g.getAuthType()).isEqualTo(WifiConfiguration.KeyMgmt.WPA2_PSK);
        assertThat(wifiConfig_5g.preSharedKey).isEqualTo("secretsecret");
        assertThat(wifiConfig_5g.apBand).isEqualTo(WifiConfiguration.AP_BAND_5GHZ);
        assertThat(wifiConfig_5g.apChannel).isEqualTo(149);
        assertThat(wifiConfig_5g.hiddenSSID).isEqualTo(true);

        SoftApConfiguration softApConfig_2g5g = new SoftApConfiguration.Builder()
                .setPassphrase("secretsecret",
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .setBand(SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
        WifiConfiguration wifiConfig_2g5g = softApConfig_2g5g.toWifiConfiguration();
        assertThat(wifiConfig_2g5g.getAuthType()).isEqualTo(WifiConfiguration.KeyMgmt.WPA2_PSK);
        assertThat(wifiConfig_2g5g.preSharedKey).isEqualTo("secretsecret");
        assertThat(wifiConfig_2g5g.apBand).isEqualTo(WifiConfiguration.AP_BAND_ANY);
        assertThat(wifiConfig_2g5g.apChannel).isEqualTo(0);
        assertThat(wifiConfig_2g5g.hiddenSSID).isEqualTo(true);
    }
}
