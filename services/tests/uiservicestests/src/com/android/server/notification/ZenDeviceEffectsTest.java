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

import static org.junit.Assert.assertThrows;

import android.app.Flags;
import android.os.Parcel;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenDeviceEffects;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ZenDeviceEffectsTest extends UiServiceTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public final void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
    }

    @Test
    public void builder() {
        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTapToWake(true).setShouldDisableTapToWake(false)
                .setShouldDisableTiltToWake(true)
                .setShouldMaximizeDoze(true)
                .setShouldUseNightMode(false)
                .setShouldSuppressAmbientDisplay(false).setShouldSuppressAmbientDisplay(true)
                .addExtraEffect("WILL BE GONE")
                .setExtraEffects(ImmutableSet.of("1", "2"))
                .addExtraEffects(ImmutableSet.of("3", "4"))
                .addExtraEffect("5")
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
        assertThat(deviceEffects.getExtraEffects()).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    public void builder_fromInstance() {
        ZenDeviceEffects original = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTiltToWake(true)
                .setShouldUseNightMode(true)
                .setShouldSuppressAmbientDisplay(true)
                .addExtraEffect("1")
                .build();

        ZenDeviceEffects modified = new ZenDeviceEffects.Builder(original)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(false)
                .addExtraEffect("2")
                .build();

        assertThat(modified.shouldDimWallpaper()).isTrue(); // from original
        assertThat(modified.shouldDisableTiltToWake()).isTrue(); // from original
        assertThat(modified.shouldDisplayGrayscale()).isTrue(); // updated
        assertThat(modified.shouldUseNightMode()).isFalse(); // updated
        assertThat(modified.shouldSuppressAmbientDisplay()).isTrue(); // from original
        assertThat(modified.getExtraEffects()).containsExactly("1", "2"); // updated
    }

    @Test
    public void builder_add_merges() {
        ZenDeviceEffects zde1 = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .addExtraEffect("one")
                .build();
        ZenDeviceEffects zde2 = new ZenDeviceEffects.Builder()
                .setShouldDisableTouch(true)
                .addExtraEffect("two")
                .build();
        ZenDeviceEffects zde3 = new ZenDeviceEffects.Builder()
                .setShouldMinimizeRadioUsage(true)
                .addExtraEffect("three")
                .build();

        ZenDeviceEffects add = new ZenDeviceEffects.Builder().add(zde1).add(zde2).add(zde3).build();

        assertThat(add).isEqualTo(new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setExtraEffects(ImmutableSet.of("one", "two", "three"))
                .build());
    }

    @Test
    public void writeToParcel_parcelsAndUnparcels() {
        ZenDeviceEffects source = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setShouldUseNightMode(true)
                .setShouldSuppressAmbientDisplay(true)
                .setExtraEffects(ImmutableSet.of("1", "2", "3"))
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
        assertThat(copy.getExtraEffects()).containsExactly("1", "2", "3");
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

    @Test
    public void hasEffects_extras_returnsTrue() {
        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .addExtraEffect("extra")
                .build();
        assertThat(effects.hasEffects()).isTrue();
    }

    @Test
    public void validate_extrasLength() {
        ZenDeviceEffects okay = new ZenDeviceEffects.Builder()
                .addExtraEffect("short")
                .addExtraEffect("anotherShort")
                .build();

        ZenDeviceEffects pushingIt = new ZenDeviceEffects.Builder()
                .addExtraEffect("0123456789".repeat(60))
                .addExtraEffect("1234567890".repeat(60))
                .build();

        ZenDeviceEffects excessive = new ZenDeviceEffects.Builder()
                .addExtraEffect("0123456789".repeat(60))
                .addExtraEffect("1234567890".repeat(60))
                .addExtraEffect("2345678901".repeat(60))
                .addExtraEffect("3456789012".repeat(30))
                .build();

        okay.validate(); // No exception.
        pushingIt.validate(); // No exception.
        assertThrows(Exception.class, () -> excessive.validate());
    }
}
