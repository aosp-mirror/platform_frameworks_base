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

import android.net.MacAddress;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class SoftApConfigurationTest {
    private SoftApConfiguration parcelUnparcel(SoftApConfiguration configIn) {
        Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(configIn, 0);
        parcel.setDataPosition(0);
        SoftApConfiguration configOut =
                parcel.readParcelable(SoftApConfiguration.class.getClassLoader());
        parcel.recycle();
        return configOut;
    }

    @Test
    public void testBasicSettings() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setSsid("ssid")
                .setBssid(MacAddress.fromString("11:22:33:44:55:66"))
                .build();
        assertThat(original.getSsid()).isEqualTo("ssid");
        assertThat(original.getBssid()).isEqualTo(MacAddress.fromString("11:22:33:44:55:66"));
        assertThat(original.getWpa2Passphrase()).isNull();
        assertThat(original.getSecurityType()).isEqualTo(SoftApConfiguration.SECURITY_TYPE_OPEN);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_2GHZ);
        assertThat(original.getChannel()).isEqualTo(0);
        assertThat(original.isHiddenSsid()).isEqualTo(false);

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
                .setWpa2Passphrase("secretsecret")
                .build();
        assertThat(original.getWpa2Passphrase()).isEqualTo("secretsecret");
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
        assertThat(original.getBand()).isEqualTo(SoftApConfiguration.BAND_2GHZ);
        assertThat(original.getChannel()).isEqualTo(0);
        assertThat(original.isHiddenSsid()).isEqualTo(false);


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
    public void testWpa2WithBandAndChannelAndHiddenNetwork() {
        SoftApConfiguration original = new SoftApConfiguration.Builder()
                .setWpa2Passphrase("secretsecret")
                .setBand(SoftApConfiguration.BAND_ANY)
                .setChannel(149, SoftApConfiguration.BAND_5GHZ)
                .setHiddenSsid(true)
                .build();
        assertThat(original.getWpa2Passphrase()).isEqualTo("secretsecret");
        assertThat(original.getSecurityType()).isEqualTo(
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
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
}
