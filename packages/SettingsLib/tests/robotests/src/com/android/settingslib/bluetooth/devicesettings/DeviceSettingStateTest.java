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
public final class DeviceSettingStateTest {
    private static final ActionSwitchPreferenceState ACTION_SWITCH_PREFERENCE_STATE =
            new ActionSwitchPreferenceState.Builder().setChecked(true).build();
    private static final MultiTogglePreferenceState MULTI_TOGGLE_PREFERENCE_STATE =
            new MultiTogglePreferenceState.Builder().setState(123).build();

    @Test
    public void build_withoutPreferenceState_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    DeviceSettingState unused =
                            new DeviceSettingState.Builder()
                                    .setSettingId(123)
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutExtra_successfully() {
        DeviceSettingState unused =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(ACTION_SWITCH_PREFERENCE_STATE)
                        .build();
    }

    @Test
    public void build_withAllFields_successfully() {
        DeviceSettingState unused =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(ACTION_SWITCH_PREFERENCE_STATE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods_actionSwitchPreferenceState() {
        DeviceSettingState state =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(ACTION_SWITCH_PREFERENCE_STATE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(state.getSettingId()).isEqualTo(123);
        assertThat(state.getPreferenceState()).isInstanceOf(ActionSwitchPreferenceState.class);
        assertThat(state.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void getMethods_multiTogglePreference() {
        DeviceSettingState state =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(MULTI_TOGGLE_PREFERENCE_STATE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(state.getSettingId()).isEqualTo(123);
        assertThat(state.getPreferenceState()).isInstanceOf(MultiTogglePreferenceState.class);
        assertThat(state.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation_actionSwitchPreferenceState() {
        DeviceSettingState state =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(ACTION_SWITCH_PREFERENCE_STATE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getSettingId()).isEqualTo(state.getSettingId());
        assertThat(fromParcel.getPreferenceState()).isInstanceOf(ActionSwitchPreferenceState.class);
        assertThat(fromParcel.getPreferenceState().getSettingType())
                .isEqualTo(DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH);
        assertThat(((ActionSwitchPreferenceState) fromParcel.getPreferenceState()).getChecked())
                .isTrue();
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(state.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_multiTogglePreferenceState() {
        DeviceSettingState state =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(MULTI_TOGGLE_PREFERENCE_STATE)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getSettingId()).isEqualTo(state.getSettingId());
        assertThat(fromParcel.getPreferenceState()).isInstanceOf(MultiTogglePreferenceState.class);
        assertThat(fromParcel.getPreferenceState().getSettingType())
                .isEqualTo(DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE);
        assertThat(((MultiTogglePreferenceState) fromParcel.getPreferenceState()).getState())
                .isEqualTo(123);
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(state.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_unknownPreferenceState() {
        DeviceSettingState state =
                new DeviceSettingState.Builder()
                        .setSettingId(123)
                        .setPreferenceState(new DeviceSettingPreferenceState(123) {})
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getSettingId()).isEqualTo(state.getSettingId());
        assertThat(fromParcel.getPreferenceState())
                .isSameInstanceAs(DeviceSettingPreferenceState.UNKNOWN);
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(state.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSettingState writeAndRead(DeviceSettingState state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSettingState fromParcel = DeviceSettingState.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
