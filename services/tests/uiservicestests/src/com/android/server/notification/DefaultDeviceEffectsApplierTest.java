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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.hardware.display.ColorDisplayManager;
import android.os.PowerManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenDeviceEffects;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class DefaultDeviceEffectsApplierTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private TestableContext mContext;
    private DefaultDeviceEffectsApplier mApplier;
    @Mock PowerManager mPowerManager;
    @Mock ColorDisplayManager mColorDisplayManager;
    @Mock UiModeManager mUiModeManager;
    @Mock WallpaperManager mWallpaperManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = new TestableContext(InstrumentationRegistry.getContext(), null);
        mContext.addMockSystemService(PowerManager.class, mPowerManager);
        mContext.addMockSystemService(ColorDisplayManager.class, mColorDisplayManager);
        mContext.addMockSystemService(UiModeManager.class, mUiModeManager);
        mContext.addMockSystemService(WallpaperManager.class, mWallpaperManager);

        mApplier = new DefaultDeviceEffectsApplier(mContext);
    }

    @Test
    public void apply_appliesEffects() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(true)
                .build();
        mApplier.apply(effects);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));
        verify(mColorDisplayManager).setSaturationLevel(eq(0));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.6f));
        verifyZeroInteractions(mUiModeManager); // Coming later; adding now so test fails then. :)
    }

    @Test
    public void apply_removesEffects() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects noEffects = new ZenDeviceEffects.Builder().build();
        mApplier.apply(noEffects);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(false));
        verify(mColorDisplayManager).setSaturationLevel(eq(100));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.0f));
        verifyZeroInteractions(mUiModeManager);
    }

    @Test
    public void apply_missingSomeServices_okay() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mContext.addMockSystemService(ColorDisplayManager.class, null);
        mContext.addMockSystemService(WallpaperManager.class, null);

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(true)
                .build();
        mApplier.apply(effects);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));
        // (And no crash from missing services).
    }
}
