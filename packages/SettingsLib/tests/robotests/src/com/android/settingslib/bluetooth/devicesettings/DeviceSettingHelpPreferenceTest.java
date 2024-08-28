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

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class DeviceSettingHelpPreferenceTest {

    @Test
    public void getMethods() {
        Intent intent = new Intent();
        DeviceSettingHelpPreference preference =
                new DeviceSettingHelpPreference.Builder()
                        .setIntent(intent)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(preference.getIntent()).isSameInstanceAs(intent);
        assertThat(preference.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        Intent intent = new Intent("intent_action");
        DeviceSettingHelpPreference preference =
                new DeviceSettingHelpPreference.Builder()
                        .setIntent(intent)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingHelpPreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getIntent().getAction())
                .isEqualTo(preference.getIntent().getAction());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(preference.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSettingHelpPreference writeAndRead(DeviceSettingHelpPreference preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSettingHelpPreference fromParcel =
                DeviceSettingHelpPreference.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
