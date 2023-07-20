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

package com.android.systemui.statusbar.notification;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.palette.graphics.Palette;

import java.util.List;

/**
 * A gutted class that now contains only a color extraction utility used by the
 * MediaArtworkProcessor, which has otherwise supplanted this.
 *
 * TODO(b/182926117): move this into MediaArtworkProcessor.kt
 */
public class MediaNotificationProcessor {

    /**
     * The population fraction to select a white or black color as the background over a color.
     */
    private static final float POPULATION_FRACTION_FOR_WHITE_OR_BLACK = 2.5f;
    private static final float BLACK_MAX_LIGHTNESS = 0.08f;
    private static final float WHITE_MIN_LIGHTNESS = 0.90f;
    private static final int RESIZE_BITMAP_AREA = 150 * 150;

    private MediaNotificationProcessor() {
    }

    /**
     * Finds an appropriate background swatch from media artwork.
     *
     * @param artwork Media artwork
     * @return Swatch that should be used as the background of the media notification.
     */
    public static Palette.Swatch findBackgroundSwatch(Bitmap artwork) {
        return findBackgroundSwatch(generateArtworkPaletteBuilder(artwork).generate());
    }

    /**
     * Finds an appropriate background swatch from the palette of media artwork.
     *
     * @param palette Artwork palette, should be obtained from {@link generateArtworkPaletteBuilder}
     * @return Swatch that should be used as the background of the media notification.
     */
    public static Palette.Swatch findBackgroundSwatch(Palette palette) {
        // by default we use the dominant palette
        Palette.Swatch dominantSwatch = palette.getDominantSwatch();
        if (dominantSwatch == null) {
            // try to bring up the muted color of the cover art before going for the white variant
            Palette.Swatch mutedSwatch = palette.getMutedSwatch();
            if (mutedSwatch != null) {
                return mutedSwatch;
            }
            return new Palette.Swatch(Color.WHITE, 100);
        }

        if (!isWhiteOrBlack(dominantSwatch.getHsl())) {
            return dominantSwatch;
        }
        // Oh well, we selected black or white. Lets look at the second color!
        List<Palette.Swatch> swatches = palette.getSwatches();
        float highestNonWhitePopulation = -1;
        Palette.Swatch second = null;
        for (Palette.Swatch swatch : swatches) {
            if (swatch != dominantSwatch
                    && swatch.getPopulation() > highestNonWhitePopulation
                    && !isWhiteOrBlack(swatch.getHsl())) {
                second = swatch;
                highestNonWhitePopulation = swatch.getPopulation();
            }
        }
        if (second == null) {
            return dominantSwatch;
        }
        if (dominantSwatch.getPopulation() / highestNonWhitePopulation
                > POPULATION_FRACTION_FOR_WHITE_OR_BLACK) {
            // The dominant swatch is very dominant, lets take it!
            // We're not filtering on white or black
            return dominantSwatch;
        } else {
            return second;
        }
    }

    /**
     * Generate a palette builder for media artwork.
     *
     * For producing a smooth background transition, the palette is extracted from only the left
     * side of the artwork.
     *
     * @param artwork Media artwork
     * @return Builder that generates the {@link Palette} for the media artwork.
     */
    public static Palette.Builder generateArtworkPaletteBuilder(Bitmap artwork) {
        // for the background we only take the full image to ensure
        // a smooth transition
        return Palette.from(artwork)
                .clearFilters() // we want all colors, red / white / black ones too!
                .resizeBitmapArea(RESIZE_BITMAP_AREA);
    }

    private static boolean isWhiteOrBlack(float[] hsl) {
        return isBlack(hsl) || isWhite(hsl);
    }

    /**
     * @return true if the color represents a color which is close to black.
     */
    private static boolean isBlack(float[] hslColor) {
        return hslColor[2] <= BLACK_MAX_LIGHTNESS;
    }

    /**
     * @return true if the color represents a color which is close to white.
     */
    private static boolean isWhite(float[] hslColor) {
        return hslColor[2] >= WHITE_MIN_LIGHTNESS;
    }
}
