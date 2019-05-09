/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.colorextraction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.graphics.Color;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.types.Tonal;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests color extraction generation.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SysuiColorExtractorTests extends SysuiTestCase {

    private static int[] sWhich = new int[]{
            WallpaperManager.FLAG_SYSTEM,
            WallpaperManager.FLAG_LOCK};
    private static int[] sTypes = new int[]{
            ColorExtractor.TYPE_NORMAL,
            ColorExtractor.TYPE_DARK,
            ColorExtractor.TYPE_EXTRA_DARK};
    @Mock
    private OverviewProxyService mOverviewProxyService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void getColors_usesGreyIfWallpaperNotVisible() {
        ColorExtractor.GradientColors colors = new ColorExtractor.GradientColors();
        colors.setMainColor(Color.RED);
        colors.setSecondaryColor(Color.RED);

        SysuiColorExtractor extractor = getTestableExtractor(colors);
        simulateEvent(extractor);
        extractor.setWallpaperVisible(false);

        ColorExtractor.GradientColors fallbackColors = extractor.getNeutralColors();

        for (int type : sTypes) {
            assertEquals("Not using fallback!",
                    extractor.getColors(WallpaperManager.FLAG_SYSTEM, type), fallbackColors);
            assertNotEquals("Wallpaper visibility event should not affect lock wallpaper.",
                    extractor.getColors(WallpaperManager.FLAG_LOCK, type), fallbackColors);
        }
    }

    @Test
    public void getColors_doesntUseFallbackIfVisible() {
        ColorExtractor.GradientColors colors = new ColorExtractor.GradientColors();
        colors.setMainColor(Color.RED);
        colors.setSecondaryColor(Color.RED);

        SysuiColorExtractor extractor = getTestableExtractor(colors);
        simulateEvent(extractor);
        extractor.setWallpaperVisible(true);

        for (int which : sWhich) {
            for (int type : sTypes) {
                assertEquals("Not using extracted colors!",
                        extractor.getColors(which, type), colors);
            }
        }
    }

    @Test
    public void getColors_fallbackWhenMediaIsVisible() {
        ColorExtractor.GradientColors colors = new ColorExtractor.GradientColors();
        colors.setMainColor(Color.RED);
        colors.setSecondaryColor(Color.RED);

        SysuiColorExtractor extractor = getTestableExtractor(colors);
        simulateEvent(extractor);
        extractor.setWallpaperVisible(true);
        extractor.setHasBackdrop(true);

        ColorExtractor.GradientColors fallbackColors = extractor.getNeutralColors();

        for (int type : sTypes) {
            assertEquals("Not using fallback!",
                    extractor.getColors(WallpaperManager.FLAG_LOCK, type), fallbackColors);
            assertNotEquals("Media visibility should not affect system wallpaper.",
                    extractor.getColors(WallpaperManager.FLAG_SYSTEM, type), fallbackColors);
        }
    }

    @Test
    public void onUiModeChanged_reloadsColors() {
        Tonal tonal = mock(Tonal.class);
        ConfigurationController configurationController = mock(ConfigurationController.class);
        SysuiColorExtractor sysuiColorExtractor = new SysuiColorExtractor(getContext(),
                tonal, configurationController, false /* registerVisibility */,
                mOverviewProxyService);
        verify(configurationController).addCallback(eq(sysuiColorExtractor));

        reset(tonal);
        sysuiColorExtractor.onUiModeChanged();
        verify(tonal).applyFallback(any(), any());
    }

    private SysuiColorExtractor getTestableExtractor(ColorExtractor.GradientColors colors) {
        return new SysuiColorExtractor(getContext(),
                (inWallpaperColors, outGradientColorsNormal, outGradientColorsDark,
                        outGradientColorsExtraDark) -> {
                    outGradientColorsNormal.set(colors);
                    outGradientColorsDark.set(colors);
                    outGradientColorsExtraDark.set(colors);
                }, mock(ConfigurationController.class), false, mOverviewProxyService);
    }

    private void simulateEvent(SysuiColorExtractor extractor) {
        // Let's fake a color event
        extractor.onColorsChanged(new WallpaperColors(Color.valueOf(Color.GREEN), null, null, 0),
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }
}