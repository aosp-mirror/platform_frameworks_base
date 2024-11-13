/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class DeviceSettingFooterPreferenceTest {

    @Test
    public void getMethods() {
        DeviceSettingFooterPreference preference =
                new DeviceSettingFooterPreference.Builder()
                        .setFooterText("footer_text")
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(preference.getFooterText()).isEqualTo("footer_text");
        assertThat(preference.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        DeviceSettingFooterPreference preference =
                new DeviceSettingFooterPreference.Builder()
                        .setFooterText("footer_text")
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingFooterPreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getFooterText()).isEqualTo(preference.getFooterText());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(preference.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSettingFooterPreference writeAndRead(DeviceSettingFooterPreference preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSettingFooterPreference fromParcel =
                DeviceSettingFooterPreference.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
