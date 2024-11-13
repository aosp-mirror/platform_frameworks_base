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
public final class MultiTogglePreferenceTest {
    private static final Bitmap ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private static final ToggleInfo TOGGLE_INFO_1 =
            new ToggleInfo.Builder().setLabel("label1").setIcon(ICON).build();
    private static final ToggleInfo TOGGLE_INFO_2 =
            new ToggleInfo.Builder().setLabel("label2").setIcon(ICON).build();

    @Test
    public void build_withoutTitle_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    MultiTogglePreference unused =
                            new MultiTogglePreference.Builder()
                                    .addToggleInfo(TOGGLE_INFO_1)
                                    .setState(0)
                                    .setAllowChangingState(true)
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withNegativeState_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    MultiTogglePreference unused =
                            new MultiTogglePreference.Builder()
                                    .setTitle("title")
                                    .addToggleInfo(TOGGLE_INFO_1)
                                    .setState(-1)
                                    .setAllowChangingState(true)
                                    .setExtras(buildBundle("key1", "value1"))
                                    .build();
                });
    }

    @Test
    public void build_withoutExtra_successfully() {
        MultiTogglePreference unused =
                new MultiTogglePreference.Builder()
                        .setTitle("title")
                        .addToggleInfo(TOGGLE_INFO_1)
                        .addToggleInfo(TOGGLE_INFO_2)
                        .setState(123)
                        .setAllowChangingState(true)
                        .build();
    }

    @Test
    public void build_withAllFields_successfully() {
        MultiTogglePreference unused =
                new MultiTogglePreference.Builder()
                        .setTitle("title")
                        .addToggleInfo(TOGGLE_INFO_1)
                        .addToggleInfo(TOGGLE_INFO_2)
                        .setState(123)
                        .setAllowChangingState(true)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods() {
        MultiTogglePreference preference =
                new MultiTogglePreference.Builder()
                        .setTitle("title")
                        .addToggleInfo(TOGGLE_INFO_1)
                        .addToggleInfo(TOGGLE_INFO_2)
                        .setState(123)
                        .setAllowChangingState(true)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        assertThat(preference.getTitle()).isEqualTo("title");
        assertThat(preference.getToggleInfos().stream().map(ToggleInfo::getLabel).toList())
                .containsExactly("label1", "label2");
        assertThat(preference.getState()).isEqualTo(123);
        assertThat(preference.isAllowedChangingState()).isTrue();
        assertThat(preference.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        MultiTogglePreference preference =
                new MultiTogglePreference.Builder()
                        .setTitle("title")
                        .addToggleInfo(TOGGLE_INFO_1)
                        .addToggleInfo(TOGGLE_INFO_2)
                        .setState(123)
                        .setIsActive(true)
                        .setAllowChangingState(true)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();

        MultiTogglePreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getTitle()).isEqualTo(preference.getTitle());
        assertThat(fromParcel.getToggleInfos().stream().map(ToggleInfo::getLabel).toList())
                .containsExactly("label1", "label2");
        assertThat(fromParcel.getState()).isEqualTo(preference.getState());
        assertThat(fromParcel.isActive()).isEqualTo(preference.isActive());
        assertThat(fromParcel.isAllowedChangingState())
                .isEqualTo(preference.isAllowedChangingState());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(preference.getExtras().getString("key1"));
    }

    private Bundle buildBundle(String key, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(key, value);
        return bundle;
    }

    private MultiTogglePreference writeAndRead(MultiTogglePreference preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        MultiTogglePreference fromParcel = MultiTogglePreference.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }
}
