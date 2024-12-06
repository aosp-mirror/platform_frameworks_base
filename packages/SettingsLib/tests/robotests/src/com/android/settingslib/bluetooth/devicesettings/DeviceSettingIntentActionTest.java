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
public final class DeviceSettingIntentActionTest {

    @Test
    public void getMethods() {
        DeviceSettingIntentAction action =
                new DeviceSettingIntentAction.Builder()
                        .setIntent(new Intent("intent_action"))
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(action.getIntent().getAction()).isEqualTo("intent_action");
        assertThat(action.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        DeviceSettingIntentAction action =
                new DeviceSettingIntentAction.Builder()
                        .setIntent(new Intent("intent_action"))
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingIntentAction fromParcel = writeAndRead(action);

        assertThat(fromParcel.getIntent().getAction()).isEqualTo(action.getIntent().getAction());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(action.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSettingIntentAction writeAndRead(DeviceSettingIntentAction preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSettingIntentAction fromParcel =
                DeviceSettingIntentAction.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
