/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.monet;

import android.app.WallpaperColors;
import android.graphics.Color;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ColorSchemeTest extends SysuiTestCase {
    @Test
    public void testFilterTransparency() {
        ColorScheme colorScheme = new ColorScheme(Color.TRANSPARENT, false /* darkTheme */);
        Assert.assertEquals(colorScheme.getAllAccentColors(),
                new ColorScheme(0xFF1b6ef3, false).getAllAccentColors());
    }

    @Test
    public void testDontFilterOpaque() {
        ColorScheme colorScheme = new ColorScheme(0xFFFF0000, false /* darkTheme */);
        Assert.assertNotEquals(colorScheme.getAllAccentColors(),
                new ColorScheme(0xFF1b6ef3, false).getAllAccentColors());
    }

    @Test
    public void testUniqueColors() {
        WallpaperColors wallpaperColors = new WallpaperColors(Color.valueOf(0xffaec00a),
                Color.valueOf(0xffaec00a), Color.valueOf(0xffaec00a));

        List<Integer> rankedSeedColors = ColorScheme.getSeedColors(wallpaperColors);
        Assert.assertEquals(rankedSeedColors, List.of(0xffaec00a));
    }


    @Test
    public void testFiltersInvalidColors() {
        WallpaperColors wallpaperColors = new WallpaperColors(Color.valueOf(0xff5e7ea2),
                Color.valueOf(0xff5e7ea2), Color.valueOf(0xff000000));

        List<Integer> rankedSeedColors = ColorScheme.getSeedColors(wallpaperColors);
        Assert.assertEquals(rankedSeedColors, List.of(0xff5e7ea2));
    }

    @Test
    public void testInvalidColorBecomesGBlue() {
        WallpaperColors wallpaperColors = new WallpaperColors(Color.valueOf(0xff000000), null,
                null);

        List<Integer> rankedSeedColors = ColorScheme.getSeedColors(wallpaperColors);
        Assert.assertEquals(rankedSeedColors, List.of(0xFF1b6ef3));
    }

    @Test
    public void testDontFilterRRGGBB() {
        ColorScheme colorScheme = new ColorScheme(0xFF0000, false /* darkTheme */);
        Assert.assertEquals(colorScheme.getAllAccentColors(),
                new ColorScheme(0xFFFF0000, false).getAllAccentColors());
    }

    @Test
    public void testNoPopulationSignal() {
        WallpaperColors wallpaperColors = new WallpaperColors(Color.valueOf(0xffaec00a),
                Color.valueOf(0xffbe0000), Color.valueOf(0xffcc040f));

        List<Integer> rankedSeedColors = ColorScheme.getSeedColors(wallpaperColors);
        Assert.assertEquals(rankedSeedColors, List.of(0xffaec00a, 0xffbe0000, 0xffcc040f));
    }
}
