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

import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class DeviceSettingTest {
    private static final ActionSwitchPreference ACTION_SWITCH_PREFERENCE =
            new ActionSwitchPreference.Builder().setTitle("action_switch_preference").build();
    private static final MultiTogglePreference MULTI_TOGGLE_PREFERENCE =
            new MultiTogglePreference.Builder().setTitle("multi_toggle_preference").build();

    @Test
    public void build_withoutPreference_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    DeviceSetting unused =
                            new DeviceSetting.Builder()
                                    .setSettingId(123)
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutExtra_successfully() {
        DeviceSetting unused =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(ACTION_SWITCH_PREFERENCE)
                        .build();
    }

    @Test
    public void build_withAllFields_successfully() {
        DeviceSetting unused =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(ACTION_SWITCH_PREFERENCE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods_actionSwitchPreference() {
        DeviceSetting setting =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(ACTION_SWITCH_PREFERENCE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(setting.getSettingId()).isEqualTo(123);
        assertThat(setting.getPreference()).isInstanceOf(ActionSwitchPreference.class);
        assertThat(setting.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void getMethods_multiTogglePreference() {
        DeviceSetting setting =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(MULTI_TOGGLE_PREFERENCE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(setting.getSettingId()).isEqualTo(123);
        assertThat(setting.getPreference()).isInstanceOf(MultiTogglePreference.class);
        assertThat(setting.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation_actionSwitchPreference() {
        DeviceSetting setting =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(ACTION_SWITCH_PREFERENCE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSetting fromParcel = writeAndRead(setting);

        assertThat(fromParcel.getSettingId()).isEqualTo(setting.getSettingId());
        assertThat(fromParcel.getPreference()).isInstanceOf(ActionSwitchPreference.class);
        assertThat(fromParcel.getPreference().getSettingType())
                .isEqualTo(DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH);
        assertThat(((ActionSwitchPreference) fromParcel.getPreference()).getTitle())
                .isEqualTo("action_switch_preference");
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(setting.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_multiTogglePreference() {
        DeviceSetting setting =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(MULTI_TOGGLE_PREFERENCE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSetting fromParcel = writeAndRead(setting);

        assertThat(fromParcel.getSettingId()).isEqualTo(setting.getSettingId());
        assertThat(fromParcel.getPreference()).isInstanceOf(MultiTogglePreference.class);
        assertThat(fromParcel.getPreference().getSettingType())
                .isEqualTo(DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE);
        assertThat(((MultiTogglePreference) fromParcel.getPreference()).getTitle())
                .isEqualTo("multi_toggle_preference");
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(setting.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_unknownPreference() {
        DeviceSetting setting =
                new DeviceSetting.Builder()
                        .setSettingId(123)
                        .setPreference(new DeviceSettingPreference(123) {})
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSetting fromParcel = writeAndRead(setting);

        assertThat(fromParcel.getSettingId()).isEqualTo(setting.getSettingId());
        assertThat(fromParcel.getPreference()).isSameInstanceAs(DeviceSettingPreference.UNKNOWN);
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(setting.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSetting writeAndRead(DeviceSetting state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSetting fromParcel = DeviceSetting.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
