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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

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
    private WallpaperManager mWallpaperManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    private ColorExtractor.GradientColors mColors;
    private SysuiColorExtractor mColorExtractor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mColors = new ColorExtractor.GradientColors();
        mColors.setMainColor(Color.RED);
        mColors.setSecondaryColor(Color.RED);
        mColorExtractor = new SysuiColorExtractor(
                getContext(),
                (inWallpaperColors, outGradientColorsNormal, outGradientColorsDark,
                        outGradientColorsExtraDark) -> {
                    outGradientColorsNormal.set(mColors);
                    outGradientColorsDark.set(mColors);
                    outGradientColorsExtraDark.set(mColors);
                },
                mock(ConfigurationController.class),
                mWallpaperManager,
                mDumpManager,
                true /* immediately */,
                () -> mSelectedUserInteractor);
    }

    @Test
    public void getColors() {
        mColors.setMainColor(Color.RED);
        mColors.setSecondaryColor(Color.RED);

        simulateEvent(mColorExtractor);
        for (int which : sWhich) {
            for (int type : sTypes) {
                assertEquals("Not using extracted colors!",
                        mColorExtractor.getColors(which, type), mColors);
            }
        }
    }

    @Test
    public void onUiModeChanged_reloadsColors() {
        Tonal tonal = mock(Tonal.class);
        ConfigurationController configurationController = mock(ConfigurationController.class);
        SysuiColorExtractor sysuiColorExtractor = new SysuiColorExtractor(
                getContext(),
                tonal,
                configurationController,
                mWallpaperManager,
                mDumpManager,
                true /* immediately */,
                () -> mSelectedUserInteractor);
        verify(configurationController).addCallback(eq(sysuiColorExtractor));

        reset(tonal);
        sysuiColorExtractor.onUiModeChanged();
        verify(tonal).applyFallback(any(), any());
    }

    @Test
    public void onUiModeChanged_notifiesListener() {
        ColorExtractor.OnColorsChangedListener listener = mock(
                ColorExtractor.OnColorsChangedListener.class);
        mColorExtractor.addOnColorsChangedListener(listener);
        mColorExtractor.onUiModeChanged();
        verify(listener).onColorsChanged(any(), anyInt());
    }

    private void simulateEvent(SysuiColorExtractor extractor) {
        // Let's fake a color event
        extractor.onColorsChanged(new WallpaperColors(Color.valueOf(Color.GREEN), null, null, 0),
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
    }
}