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
public final class MultiTogglePreferenceStateTest {

    @Test
    public void getMethods() {
        MultiTogglePreferenceState state1 =
                new MultiTogglePreferenceState.Builder()
                        .setState(1)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
        MultiTogglePreferenceState state2 =
                new MultiTogglePreferenceState.Builder()
                        .setState(2)
                        .setExtras(buildBundle("key2", "value2"))
                        .build();

        assertThat(state1.getState()).isEqualTo(1);
        assertThat(state2.getState()).isEqualTo(2);
        assertThat(state1.getExtras().getString("key1")).isEqualTo("value1");
        assertThat(state2.getExtras().getString("key2")).isEqualTo("value2");
    }

    @Test
    public void parcelOperation() {
        MultiTogglePreferenceState state =
                new MultiTogglePreferenceState.Builder()
                        .setState(123)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        MultiTogglePreferenceState fromParcel = writeAndRead(state);

        assertThat(fromParcel.getState()).isEqualTo(state.getState());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(state.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private MultiTogglePreferenceState writeAndRead(MultiTogglePreferenceState state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        MultiTogglePreferenceState fromParcel =
                MultiTogglePreferenceState.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
