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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ToggleInfoTest {

    @Test
    public void build_withoutIcon_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ToggleInfo unused =
                            new ToggleInfo.Builder()
                                    .setLabel("label")
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutLabel_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ToggleInfo unused =
                            new ToggleInfo.Builder()
                                    .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutExtra_successfully() {
        ToggleInfo unused =
                new ToggleInfo.Builder()
                        .setLabel("label")
                        .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        .build();
    }

    @Test
    public void build_withAllFields_successfully() {
        ToggleInfo unused =
                new ToggleInfo.Builder()
                        .setLabel("label")
                        .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods() {
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        ToggleInfo info =
                new ToggleInfo.Builder()
                        .setLabel("label")
                        .setIcon(icon)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(info.getLabel()).isEqualTo("label");
        assertThat(info.getIcon()).isSameInstanceAs(icon);
        assertThat(info.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        ToggleInfo info =
                new ToggleInfo.Builder()
                        .setLabel("label")
                        .setIcon(icon)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        ToggleInfo fromParcel = writeAndRead(info);

        assertThat(fromParcel.getLabel()).isEqualTo(info.getLabel());
        assertThat(fromParcel.getIcon().sameAs(info.getIcon())).isTrue();
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(info.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private ToggleInfo writeAndRead(ToggleInfo state) {
        Parcel parcel = Parcel.obtain();
        state.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ToggleInfo fromParcel = ToggleInfo.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
