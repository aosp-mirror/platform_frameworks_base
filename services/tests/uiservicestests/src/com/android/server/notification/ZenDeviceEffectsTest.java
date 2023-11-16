/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.service.notification.ZenDeviceEffects;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ZenDeviceEffectsTest extends UiServiceTestCase {

    @Test
    public void builder() {
        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTapToWake(true).setShouldDisableTapToWake(false)
                .setShouldDisableTiltToWake(true)
                .setShouldMaximizeDoze(true)
                .setShouldUseNightMode(false)
                .setShouldSuppressAmbientDisplay(false).setShouldSuppressAmbientDisplay(true)
                .build();

        assertThat(deviceEffects.shouldDimWallpaper()).isTrue();
        assertThat(deviceEffects.shouldDisableAutoBrightness()).isFalse();
        assertThat(deviceEffects.shouldDisableTapToWake()).isFalse();
        assertThat(deviceEffects.shouldDisableTiltToWake()).isTrue();
        assertThat(deviceEffects.shouldDisableTouch()).isFalse();
        assertThat(deviceEffects.shouldDisplayGrayscale()).isFalse();
        assertThat(deviceEffects.shouldMaximizeDoze()).isTrue();
        assertThat(deviceEffects.shouldMinimizeRadioUsage()).isFalse();
        assertThat(deviceEffects.shouldUseNightMode()).isFalse();
        assertThat(deviceEffects.shouldSuppressAmbientDisplay()).isTrue();
    }

    @Test
    public void builder_fromInstance() {
        ZenDeviceEffects original = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTiltToWake(true)
                .setShouldUseNightMode(true)
                .setShouldSuppressAmbientDisplay(true)
                .build();

        ZenDeviceEffects modified = new ZenDeviceEffects.Builder(original)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(false)
                .build();

        assertThat(modified.shouldDimWallpaper()).isTrue(); // from original
        assertThat(modified.shouldDisableTiltToWake()).isTrue(); // from original
        assertThat(modified.shouldDisplayGrayscale()).isTrue(); // updated
        assertThat(modified.shouldUseNightMode()).isFalse(); // updated
        assertThat(modified.shouldSuppressAmbientDisplay()).isTrue(); // from original
    }

    @Test
    public void writeToParcel_parcelsAndUnparcels() {
        ZenDeviceEffects source = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setShouldUseNightMode(true)
                .setShouldSuppressAmbientDisplay(true)
                .build();

        Parcel parcel = Parcel.obtain();
        ZenDeviceEffects copy;
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            copy = ZenDeviceEffects.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }

        assertThat(copy.shouldDimWallpaper()).isTrue();
        assertThat(copy.shouldDisableTouch()).isTrue();
        assertThat(copy.shouldMinimizeRadioUsage()).isTrue();
        assertThat(copy.shouldUseNightMode()).isTrue();
        assertThat(copy.shouldSuppressAmbientDisplay()).isTrue();
        assertThat(copy.shouldDisplayGrayscale()).isFalse();
    }

    @Test
    public void hasEffects_none_returnsFalse() {
        ZenDeviceEffects effects = new ZenDeviceEffects.Builder().build();
        assertThat(effects.hasEffects()).isFalse();
    }

    @Test
    public void hasEffects_some_returnsTrue() {
        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .build();
        assertThat(effects.hasEffects()).isTrue();
    }
}
