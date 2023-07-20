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
import android.util.Log;

import androidx.test.filters.SmallTest;

import com.android.internal.graphics.cam.Cam;
import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    public void testStyleApplied() {
        WallpaperColors wallpaperColors = new WallpaperColors(Color.valueOf(0xffaec00a),
                null, null);
        // Expressive applies hue rotations to the theme color. The input theme color has hue
        // 117, ensuring the hue changed significantly is a strong signal styles are being applied.
        ColorScheme colorScheme = new ColorScheme(wallpaperColors, false, Style.EXPRESSIVE);
        Assert.assertEquals(357.77, Cam.fromInt(colorScheme.getAccent1().get(6)).getHue(), 0.1);
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

    @Test
    public void testTertiaryHueWrapsProperly() {
        int colorInt = 0xffB3588A; // H350 C50 T50
        ColorScheme colorScheme = new ColorScheme(colorInt, false /* darkTheme */);
        int tertiaryMid = colorScheme.getAccent3().get(colorScheme.getAccent3().size() / 2);
        Cam cam = Cam.fromInt(tertiaryMid);
        Assert.assertEquals(cam.getHue(), 50.0, 10.0);
    }

    @Test
    public void testSpritz() {
        int colorInt = 0xffB3588A; // H350 C50 T50
        ColorScheme colorScheme = new ColorScheme(colorInt, false /* darkTheme */,
                Style.SPRITZ /* style */);
        int primaryMid = colorScheme.getAccent1().get(colorScheme.getAccent1().size() / 2);
        Cam cam = Cam.fromInt(primaryMid);
        Assert.assertEquals(cam.getChroma(), 12.0, 1.0);
    }

    @Test
    public void testVibrant() {
        int colorInt = 0xffB3588A; // H350 C50 T50
        ColorScheme colorScheme = new ColorScheme(colorInt, false /* darkTheme */,
                Style.VIBRANT /* style */);
        int neutralMid = colorScheme.getNeutral1().get(colorScheme.getNeutral1().size() / 2);
        Cam cam = Cam.fromInt(neutralMid);
        Assert.assertTrue("chroma was " + cam.getChroma(), Math.floor(cam.getChroma()) <= 12.0);
    }

    @Test
    public void testExpressive() {
        int colorInt = 0xffB3588A; // H350 C50 T50
        ColorScheme colorScheme = new ColorScheme(colorInt, false /* darkTheme */,
                Style.EXPRESSIVE /* style */);
        int neutralMid = colorScheme.getNeutral1().get(colorScheme.getNeutral1().size() / 2);
        Cam cam = Cam.fromInt(neutralMid);
        Assert.assertTrue(cam.getChroma() <= 8.0);
    }

    @Test
    public void testMonochromatic() {
        int colorInt = 0xffB3588A; // H350 C50 T50
        ColorScheme colorScheme = new ColorScheme(colorInt, false /* darkTheme */,
                Style.MONOCHROMATIC /* style */);
        int neutralMid = colorScheme.getNeutral1().get(colorScheme.getNeutral1().size() / 2);
        Assert.assertTrue(
                Color.red(neutralMid) == Color.green(neutralMid)
                && Color.green(neutralMid) == Color.blue(neutralMid)
        );
    }

    @Test
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void testToString() {
        new ColorScheme(Color.TRANSPARENT, false /* darkTheme */).toString();
        new ColorScheme(Color.argb(0, 0, 0, 0xf), false /* darkTheme */).toString();
        new ColorScheme(Color.argb(0xff, 0xff, 0, 0), false /* darkTheme */).toString();
        new ColorScheme(0xFFFFFFFF, false /* darkTheme */).toString();

        new ColorScheme(Color.TRANSPARENT, true /* darkTheme */).toString();
        new ColorScheme(Color.argb(0, 0, 0, 0xf), true /* darkTheme */).toString();
        new ColorScheme(0xFFFF0000, true /* darkTheme */).toString();
        new ColorScheme(0xFFFFFFFF, true /* darkTheme */).toString();
    }

    /**
     * Generate xml for SystemPaletteTest#testThemeStyles().
     */
    @Test
    public void generateThemeStyles() {
        StringBuilder xml = new StringBuilder();
        for (int hue = 0; hue < 360; hue += 60) {
            final int sourceColor = Cam.getInt(hue, 50f, 50f);
            final String sourceColorHex = Integer.toHexString(sourceColor);

            xml.append("    <theme color=\"").append(sourceColorHex).append("\">\n");

            for (Style style : Style.values()) {
                String styleName = style.name().toLowerCase();
                ColorScheme colorScheme = new ColorScheme(sourceColor, false, style);
                xml.append("        <").append(styleName).append(">");

                List<String> colors = new ArrayList<>();
                for (Stream<Integer> stream: Arrays.asList(colorScheme.getAccent1().stream(),
                        colorScheme.getAccent2().stream(),
                        colorScheme.getAccent3().stream(),
                        colorScheme.getNeutral1().stream(),
                        colorScheme.getNeutral2().stream())) {
                    colors.add("ffffff");
                    colors.addAll(stream.map(Integer::toHexString).map(s -> s.substring(2)).collect(
                            Collectors.toList()));
                }
                xml.append(String.join(",", colors));
                xml.append("</").append(styleName).append(">\n");
            }
            xml.append("    </theme>\n");
        }
        Log.d("ColorSchemeXml", xml.toString());
    }
}
