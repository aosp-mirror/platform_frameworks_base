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

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ActionSwitchPreferenceTest {

    @Test
    public void build_withoutTitle_fail() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ActionSwitchPreference unused =
                            new ActionSwitchPreference.Builder().setSummary("summary").build();
                });
    }

    @Test
    public void build_withTitle_successfully() {
        ActionSwitchPreference unused =
                new ActionSwitchPreference.Builder().setTitle("title").build();
    }

    @Test
    public void build_withAllFields_successfully() {
        ActionSwitchPreference unused =
                new ActionSwitchPreference.Builder()
                        .setTitle("title")
                        .setSummary("summary")
                        .setIntent(new Intent("intent_action"))
                        .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        .setHasSwitch(true)
                        .setChecked(true)
                        .setAllowedChangingState(true)
                        .setExtras(buildBundle("key1", "value1"))
                        .build();
    }

    @Test
    public void getMethods() {
        Intent intent = new Intent("intent_action");
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        ActionSwitchPreference preference = builder().setIcon(icon).setIntent(intent).build();

        assertThat(preference.getTitle()).isEqualTo("title");
        assertThat(preference.getSummary()).isEqualTo("summary");
        assertThat(preference.getIcon()).isSameInstanceAs(icon);
        assertThat(preference.getIntent()).isSameInstanceAs(intent);
        assertThat(preference.hasSwitch()).isTrue();
        assertThat(preference.getChecked()).isTrue();
        assertThat(preference.isAllowedChangingState()).isTrue();
        assertThat(preference.getExtras().getString("key1")).isEqualTo("value1");
    }

    @Test
    public void parcelOperation() {
        Intent intent = new Intent("intent_action");
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        ActionSwitchPreference preference = builder().setIcon(icon).setIntent(intent).build();

        ActionSwitchPreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getTitle()).isEqualTo(preference.getTitle());
        assertThat(fromParcel.getSummary()).isEqualTo(preference.getSummary());
        assertThat(fromParcel.getIcon().sameAs(preference.getIcon())).isTrue();
        assertThat(fromParcel.getIntent().getAction()).isSameInstanceAs("intent_action");
        assertThat(fromParcel.hasSwitch()).isEqualTo(preference.hasSwitch());
        assertThat(fromParcel.getChecked()).isEqualTo(preference.getChecked());
        assertThat(fromParcel.isAllowedChangingState())
                .isEqualTo(preference.isAllowedChangingState());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(preference.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_noIntent() {
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        ActionSwitchPreference preference = builder().setIcon(icon).setIntent(null).build();

        ActionSwitchPreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getTitle()).isEqualTo(preference.getTitle());
        assertThat(fromParcel.getSummary()).isEqualTo(preference.getSummary());
        assertThat(fromParcel.getIcon().sameAs(preference.getIcon())).isTrue();
        assertThat(preference.getIntent()).isNull();
        assertThat(fromParcel.hasSwitch()).isEqualTo(preference.hasSwitch());
        assertThat(fromParcel.getChecked()).isEqualTo(preference.getChecked());
        assertThat(fromParcel.isAllowedChangingState())
                .isEqualTo(preference.isAllowedChangingState());
        assertThat(fromParcel.getExtras().getString("key1"))
                .isEqualTo(preference.getExtras().getString("key1"));
    }

    @Test
    public void parcelOperation_noIcon() {
        Intent intent = new Intent("intent_action");
        ActionSwitchPreference preference = builder().setIcon(null).setIntent(intent).build();

        ActionSwitchPreference fromParcel = writeAndRead(preference);

        assertThat(fromParcel.getTitle()).isEqualTo(preference.getTitle());
        assertThat(fromParcel.getSummary()).isEqualTo(preference.getSummary());
        assertThat(fromParcel.getIcon()).isNull();
        assertThat(fromParcel.getIntent().getAction()).isSameInstanceAs("intent_action");
        assertThat(fromParcel.hasSwitch()).isEqualTo(preference.hasSwitch());
        assertThat(fromParcel.getChecked()).isEqualTo(preference.getChecked());
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

    private ActionSwitchPreference writeAndRead(ActionSwitchPreference preference) {
        Parcel parcel = Parcel.obtain();
        preference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ActionSwitchPreference fromParcel = ActionSwitchPreference.CREATOR.createFromParcel(parcel);
        return fromParcel;
    }

    private ActionSwitchPreference.Builder builder() {
        return new ActionSwitchPreference.Builder()
                .setTitle("title")
                .setSummary("summary")
                .setHasSwitch(true)
                .setChecked(true)
                .setAllowedChangingState(true)
                .setExtras(buildBundle("key1", "value1"));
    }
}
