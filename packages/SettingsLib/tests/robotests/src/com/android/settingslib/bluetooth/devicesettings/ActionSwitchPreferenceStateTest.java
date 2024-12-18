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
public final class ActionSwitchPreferenceStateTest {

    @Test
    public void getMethods() {
        ActionSwitchPreferenceState state1 =
                new ActionSwitchPreferenceState.Builder()
                        .setChecked(true)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
        ActionSwitchPreferenceState state2 =
                new ActionSwitchPreferenceState.Builder()
                        .setChecked(false)
                        .setExtras(buildBundle("key2", "value2"))
                        .build();

        assertThat(state1.getChecked()).isTrue();
        assertThat(state2.getChecked()).isFalse();
        assertThat(state1.getExtras().getString("key1")).isEqualTo("value1");
        assertThat(state2.getExtras().getString("key2")).isEqualTo("value2");
    }

    @Test
    public void parcelOperation_notChecked() {
        ActionSwitchPreferenceState state =
                new ActionSwitchPreferenceState.Builder()
                        .setChecked(false)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        ActionSwitchPreferenceState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getChecked()).isEqualTo(state.getChecked());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(state.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_checked() {
        ActionSwitchPreferenceState state =
                new ActionSwitchPreferenceState.Builder()
                        .setChecked(true)
                        .setExtras(buildBundle("key2", "value2"))
                        .build();

        ActionSwitchPreferenceState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getChecked()).isEqualTo(state.getChecked());
        assertThat(fromParcel.getExtras().getString("key2"))
                .isEqualTo(state.getExtras().getString("key2"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private ActionSwitchPreferenceState writeAndRead(ActionSwitchPreferenceState state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActionSwitchPreferenceState fromParcel =
                ActionSwitchPreferenceState.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
