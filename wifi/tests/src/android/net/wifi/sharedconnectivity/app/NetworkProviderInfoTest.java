/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_LAPTOP;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_PHONE;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link NetworkProviderInfo}.
 */
@SmallTest
public class NetworkProviderInfoTest {

    private static final int DEVICE_TYPE = DEVICE_TYPE_PHONE;
    private static final String DEVICE_NAME = "TEST_NAME";
    private static final String DEVICE_MODEL = "TEST_MODEL";
    private static final int BATTERY_PERCENTAGE = 50;
    private static final int CONNECTION_STRENGTH = 2;
    private static final String BUNDLE_KEY = "INT-KEY";
    private static final int BUNDLE_VALUE = 1;

    private static final int DEVICE_TYPE_1 = DEVICE_TYPE_LAPTOP;
    private static final String DEVICE_NAME_1 = "TEST_NAME1";
    private static final String DEVICE_MODEL_1 = "TEST_MODEL1";
    private static final int BATTERY_PERCENTAGE_1 = 30;
    private static final int CONNECTION_STRENGTH_1 = 1;

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() {
        NetworkProviderInfo info = buildNetworkProviderInfoBuilder().build();

        Parcel parcelW = Parcel.obtain();
        info.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        NetworkProviderInfo fromParcel = NetworkProviderInfo.CREATOR.createFromParcel(parcelR);

        assertThat(fromParcel).isEqualTo(info);
        assertThat(fromParcel.hashCode()).isEqualTo(info.hashCode());
    }

    /**
     * Verifies the Equals operation
     */
    @Test
    public void testEqualsOperation() {
        NetworkProviderInfo info1 = buildNetworkProviderInfoBuilder().build();
        NetworkProviderInfo info2 = buildNetworkProviderInfoBuilder().build();
        assertThat(info1).isEqualTo(info2);

        NetworkProviderInfo.Builder builder = buildNetworkProviderInfoBuilder().setDeviceType(
                DEVICE_TYPE_1);
        assertThat(builder.build()).isNotEqualTo(info1);

        builder = buildNetworkProviderInfoBuilder().setDeviceName(DEVICE_NAME_1);
        assertThat(builder.build()).isNotEqualTo(info1);

        builder = buildNetworkProviderInfoBuilder().setModelName(DEVICE_MODEL_1);
        assertThat(builder.build()).isNotEqualTo(info1);

        builder = buildNetworkProviderInfoBuilder()
                .setBatteryPercentage(BATTERY_PERCENTAGE_1);
        assertThat(builder.build()).isNotEqualTo(info1);

        builder = buildNetworkProviderInfoBuilder()
                .setConnectionStrength(CONNECTION_STRENGTH_1);
        assertThat(builder.build()).isNotEqualTo(info1);
    }

    /**
     * Verifies the get methods return the expected data.
     */
    @Test
    public void testGetMethods() {
        NetworkProviderInfo info = buildNetworkProviderInfoBuilder().build();
        assertThat(info.getDeviceType()).isEqualTo(DEVICE_TYPE);
        assertThat(info.getDeviceName()).isEqualTo(DEVICE_NAME);
        assertThat(info.getModelName()).isEqualTo(DEVICE_MODEL);
        assertThat(info.getBatteryPercentage()).isEqualTo(BATTERY_PERCENTAGE);
        assertThat(info.getConnectionStrength()).isEqualTo(CONNECTION_STRENGTH);
        assertThat(info.getExtras().getInt(BUNDLE_KEY)).isEqualTo(BUNDLE_VALUE);
    }

    @Test
    public void testHashCode() {
        NetworkProviderInfo info1 = buildNetworkProviderInfoBuilder().build();
        NetworkProviderInfo info2 = buildNetworkProviderInfoBuilder().build();

        assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
    }

    private NetworkProviderInfo.Builder buildNetworkProviderInfoBuilder() {
        return new NetworkProviderInfo.Builder(DEVICE_NAME, DEVICE_MODEL).setDeviceType(DEVICE_TYPE)
                .setBatteryPercentage(BATTERY_PERCENTAGE)
                .setConnectionStrength(CONNECTION_STRENGTH)
                .setExtras(buildBundle());
    }

    private Bundle buildBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY, BUNDLE_VALUE);
        return bundle;
    }
}
