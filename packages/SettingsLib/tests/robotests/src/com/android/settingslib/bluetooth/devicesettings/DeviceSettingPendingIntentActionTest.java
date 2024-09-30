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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class DeviceSettingPendingIntentActionTest {

    private Context mContext = ApplicationProvider.getApplicationContext();

    @Test
    public void getMethods() {
        DeviceSettingPendingIntentAction action =
                new DeviceSettingPendingIntentAction.Builder()
                        .setPendingIntent(
                                PendingIntent.getBroadcast(mContext, 0, new Intent("action"), 0))
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(action.getPendingIntent()).isSameInstanceAs(action.getPendingIntent());
        assertThat(action.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        DeviceSettingPendingIntentAction action =
                new DeviceSettingPendingIntentAction.Builder()
                        .setPendingIntent(
                                PendingIntent.getBroadcast(mContext, 0, new Intent("action"), 0))
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        DeviceSettingPendingIntentAction fromParcel = writeAndRead(action);

        assertThat(action.getPendingIntent().getIntent())
                .isEqualTo(action.getPendingIntent().getIntent());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(action.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private DeviceSettingPendingIntentAction writeAndRead(
            DeviceSettingPendingIntentAction preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DeviceSettingPendingIntentAction fromParcel =
                DeviceSettingPendingIntentAction.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
