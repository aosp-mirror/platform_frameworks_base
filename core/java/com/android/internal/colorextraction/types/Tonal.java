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

package com.android.internal.colorextraction.types;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.util.MathUtils;
import android.util.Range;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.graphics.ColorUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of tonal color extraction
 */
public class Tonal implements ExtractionType {
    private static final String TAG = "Tonal";

    // Used for tonal palette fitting
    private static final float FIT_WEIGHT_H = 1.0f;
    private static final float FIT_WEIGHT_S = 1.0f;
    private static final float FIT_WEIGHT_L = 10.0f;

    private static final boolean DEBUG = true;

    public static final int MAIN_COLOR_LIGHT = 0xffe0e0e0;
    public static final int SECONDARY_COLOR_LIGHT = 0xff9e9e9e;
    public static final int MAIN_COLOR_DARK = 0xff212121;
    public static final int SECONDARY_COLOR_DARK = 0xff000000;

    private final TonalPalette mGreyPalette;
    private final ArrayList<TonalPalette> mTonalPalettes;
    private final ArrayList<ColorRange> mBlacklistedColors;

    // Temporary variable to avoid allocations
    private float[] mTmpHSL = new float[3];

    public Tonal(Context context) {

        ConfigParser parser = new ConfigParser(context);
        mTonalPalettes = parser.getTonalPalettes();
        mBlacklistedColors = parser.getBlacklistedColors();

        mGreyPalette = mTonalPalettes.get(0);
        mTonalPalettes.remove(0);
    }

    /**
     * Grab colors from WallpaperColors and set them into GradientColors.
     * Also applies the default gradient in case extraction fails.
     *
     * @param inWallpaperColors Input.
     * @param outColorsNormal Colors for normal theme.
     * @param outColorsDark Colors for dar theme.
     * @param outColorsExtraDark Colors for extra dark theme.
     */
    public void extractInto(@Nullable WallpaperColors inWallpaperColors,
            @NonNull GradientColors outColorsNormal, @NonNull GradientColors outColorsDark,
            @NonNull GradientColors outColorsExtraDark) {
        boolean success = runTonalExtraction(inWallpaperColors, outColorsNormal, outColorsDark,
                outColorsExtraDark);
        if (!success) {
            applyFallback(inWallpaperColors, outColorsNormal, outColorsDark, outColorsExtraDark);
        }
    }

    /**
     * Grab colors from WallpaperColors and set them into GradientColors.
     *
     * @param inWallpaperColors Input.
     * @param outColorsNormal Colors for normal theme.
     * @param outColorsDark Colors for dar theme.
     * @param outColorsExtraDark Colors for extra dark theme.
     * @return True if succeeded or false if failed.
     */
    private boolean runTonalExtraction(@Nullable WallpaperColors inWallpaperColors,
            @NonNull GradientColors outColorsNormal, @NonNull GradientColors outColorsDark,
            @NonNull GradientColors outColorsExtraDark) {

        if (inWallpaperColors == null) {
            return false;
        }

        final List<Color> mainColors = inWallpaperColors.getMainColors();
        final int mainColorsSize = mainColors.size();
        final int hints = inWallpaperColors.getColorHints();
        final boolean supportsDarkText = (hints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        final boolean generatedFromBitmap = (hints & WallpaperColors.HINT_FROM_BITMAP) != 0;

        if (mainColorsSize == 0) {
            return false;
        }

        // Decide what's the best color to use.
        // We have 2 options:
        // • Just pick the primary color
        // • Filter out blacklisted colors. This is useful when palette is generated
        //   automatically from a bitmap.
        Color bestColor = null;
        final float[] hsl = new float[3];
        for (int i = 0; i < mainColorsSize; i++) {
            final Color color = mainColors.get(i);
            final int colorValue = color.toArgb();
            ColorUtils.RGBToHSL(Color.red(colorValue), Color.green(colorValue),
                    Color.blue(colorValue), hsl);

            // Stop when we find a color that meets our criteria
            if (!generatedFromBitmap || !isBlacklisted(hsl)) {
                bestColor = color;
                break;
            }
        }

        // Fail if not found
        if (bestColor == null) {
            return false;
        }

        // Tonal is not really a sort, it takes a color from the extracted
        // palette and finds a best fit amongst a collection of pre-defined
        // palettes. The best fit is tweaked to be closer to the source color
        // and replaces the original palette.
        int colorValue = bestColor.toArgb();
        ColorUtils.RGBToHSL(Color.red(colorValue), Color.green(colorValue), Color.blue(colorValue),
                hsl);

        // The Android HSL definition requires the hue to go from 0 to 360 but
        // the Material Tonal Palette defines hues from 0 to 1.
        hsl[0] /= 360f;

        // Find the palette that contains the closest color
        TonalPalette palette = findTonalPalette(hsl[0], hsl[1]);
        if (palette == null) {
            Log.w(TAG, "Could not find a tonal palette!");
            return false;
        }

        // Figure out what's the main color index in the optimal palette
        int fitIndex = bestFit(palette, hsl[0], hsl[1], hsl[2]);
        if (fitIndex == -1) {
            Log.w(TAG, "Could not find best fit!");
            return false;
        }

        // Generate the 10 colors palette by offsetting each one of them
        float[] h = fit(palette.h, hsl[0], fitIndex,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        float[] s = fit(palette.s, hsl[1], fitIndex, 0.0f, 1.0f);
        float[] l = fit(palette.l, hsl[2], fitIndex, 0.0f, 1.0f);

        if (DEBUG) {
            StringBuilder builder = new StringBuilder("Tonal Palette - index: " + fitIndex +
                    ". Main color: " + Integer.toHexString(getColorInt(fitIndex, h, s, l)) +
                    "\nColors: ");

            for (int i=0; i < h.length; i++) {
                builder.append(Integer.toHexString(getColorInt(i, h, s, l)));
                if (i < h.length - 1) {
                    builder.append(", ");
                }
            }
            Log.d(TAG, builder.toString());
        }

        int primaryIndex = fitIndex;
        int mainColor = getColorInt(primaryIndex, h, s, l);

        // We might want use the fallback in case the extracted color is brighter than our
        // light fallback or darker than our dark fallback.
        ColorUtils.colorToHSL(mainColor, mTmpHSL);
        final float mainLuminosity = mTmpHSL[2];
        ColorUtils.colorToHSL(MAIN_COLOR_LIGHT, mTmpHSL);
        final float lightLuminosity = mTmpHSL[2];
        if (mainLuminosity > lightLuminosity) {
            return false;
        }
        ColorUtils.colorToHSL(MAIN_COLOR_DARK, mTmpHSL);
        final float darkLuminosity = mTmpHSL[2];
        if (mainLuminosity < darkLuminosity) {
            return false;
        }

        // Normal colors:
        // best fit + a 2 colors offset
        outColorsNormal.setMainColor(mainColor);
        int secondaryIndex = primaryIndex + (primaryIndex >= 2 ? -2 : 2);
        outColorsNormal.setSecondaryColor(getColorInt(secondaryIndex, h, s, l));

        // Dark colors:
        // Stops at 4th color, only lighter if dark text is supported
        if (supportsDarkText) {
            primaryIndex = h.length - 1;
        } else if (fitIndex < 2) {
            primaryIndex = 0;
        } else {
            primaryIndex = Math.min(fitIndex, 3);
        }
        secondaryIndex = primaryIndex + (primaryIndex >= 2 ? -2 : 2);
        outColorsDark.setMainColor(getColorInt(primaryIndex, h, s, l));
        outColorsDark.setSecondaryColor(getColorInt(secondaryIndex, h, s, l));

        // Extra Dark:
        // Stay close to dark colors until dark text is supported
        if (supportsDarkText) {
            primaryIndex = h.length - 1;
        } else if (fitIndex < 2) {
            primaryIndex = 0;
        } else {
            primaryIndex = 2;
        }
        secondaryIndex = primaryIndex + (primaryIndex >= 2 ? -2 : 2);
        outColorsExtraDark.setMainColor(getColorInt(primaryIndex, h, s, l));
        outColorsExtraDark.setSecondaryColor(getColorInt(secondaryIndex, h, s, l));

        outColorsNormal.setSupportsDarkText(supportsDarkText);
        outColorsDark.setSupportsDarkText(supportsDarkText);
        outColorsExtraDark.setSupportsDarkText(supportsDarkText);

        if (DEBUG) {
            Log.d(TAG, "Gradients: \n\tNormal " + outColorsNormal + "\n\tDark " + outColorsDark
                    + "\n\tExtra dark: " + outColorsExtraDark);
        }

        return true;
    }

    private void applyFallback(@Nullable WallpaperColors inWallpaperColors,
            GradientColors outColorsNormal, GradientColors outColorsDark,
            GradientColors outColorsExtraDark) {
        applyFallback(inWallpaperColors, outColorsNormal);
        applyFallback(inWallpaperColors, outColorsDark);
        applyFallback(inWallpaperColors, outColorsExtraDark);
    }

    /**
     * Sets the gradient to the light or dark fallbacks based on the current wallpaper colors.
     *
     * @param inWallpaperColors Colors to read.
     * @param outGradientColors Destination.
     */
    public static void applyFallback(@Nullable WallpaperColors inWallpaperColors,
            @NonNull GradientColors outGradientColors) {
        boolean light = inWallpaperColors != null
                && (inWallpaperColors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT)
                != 0;
        int innerColor = light ? MAIN_COLOR_LIGHT : MAIN_COLOR_DARK;
        int outerColor = light ? SECONDARY_COLOR_LIGHT : SECONDARY_COLOR_DARK;

        outGradientColors.setMainColor(innerColor);
        outGradientColors.setSecondaryColor(outerColor);
        outGradientColors.setSupportsDarkText(light);
    }

    private int getColorInt(int fitIndex, float[] h, float[] s, float[] l) {
        mTmpHSL[0] = fract(h[fitIndex]) * 360.0f;
        mTmpHSL[1] = s[fitIndex];
        mTmpHSL[2] = l[fitIndex];
        return ColorUtils.HSLToColor(mTmpHSL);
    }

    /**
     * Checks if a given color exists in the blacklist
     * @param hsl float array with 3 components (H 0..360, S 0..1 and L 0..1)
     * @return true if color should be avoided
     */
    private boolean isBlacklisted(float[] hsl) {
        for (int i = mBlacklistedColors.size() - 1; i >= 0; i--) {
            ColorRange badRange = mBlacklistedColors.get(i);
            if (badRange.containsColor(hsl[0], hsl[1], hsl[2])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Offsets all colors by a delta, clamping values that go beyond what's
     * supported on the color space.
     * @param data what you want to fit
     * @param v how big should be the offset
     * @param index which index to calculate the delta against
     * @param min minimum accepted value (clamp)
     * @param max maximum accepted value (clamp)
     * @return new shifted palette
     */
    private static float[] fit(float[] data, float v, int index, float min, float max) {
        float[] fitData = new float[data.length];
        float delta = v - data[index];

        for (int i = 0; i < data.length; i++) {
            fitData[i] = MathUtils.constrain(data[i] + delta, min, max);
        }

        return fitData;
    }

    /**
     * Finds the closest color in a palette, given another HSL color
     *
     * @param palette where to search
     * @param h hue
     * @param s saturation
     * @param l lightness
     * @return closest index or -1 if palette is empty.
     */
    private static int bestFit(@NonNull TonalPalette palette, float h, float s, float l) {
        int minErrorIndex = -1;
        float minError = Float.POSITIVE_INFINITY;

        for (int i = 0; i < palette.h.length; i++) {
            float error =
                    FIT_WEIGHT_H * Math.abs(h - palette.h[i])
                            + FIT_WEIGHT_S * Math.abs(s - palette.s[i])
                            + FIT_WEIGHT_L * Math.abs(l - palette.l[i]);
            if (error < minError) {
                minError = error;
                minErrorIndex = i;
            }
        }

        return minErrorIndex;
    }

    @VisibleForTesting
    public List<ColorRange> getBlacklistedColors() {
        return mBlacklistedColors;
    }

    @Nullable
    private TonalPalette findTonalPalette(float h, float s) {
        // Fallback to a grey palette if the color is too desaturated.
        // This avoids hue shifts.
        if (s < 0.05f) {
            return mGreyPalette;
        }

        TonalPalette best = null;
        float error = Float.POSITIVE_INFINITY;

        final int tonalPalettesCount = mTonalPalettes.size();
        for (int i = 0; i < tonalPalettesCount; i++) {
            final TonalPalette candidate = mTonalPalettes.get(i);

            if (h >= candidate.minHue && h <= candidate.maxHue) {
                best = candidate;
                break;
            }

            if (candidate.maxHue > 1.0f && h >= 0.0f && h <= fract(candidate.maxHue)) {
                best = candidate;
                break;
            }

            if (candidate.minHue < 0.0f && h >= fract(candidate.minHue) && h <= 1.0f) {
                best = candidate;
                break;
            }

            if (h <= candidate.minHue && candidate.minHue - h < error) {
                best = candidate;
                error = candidate.minHue - h;
            } else if (h >= candidate.maxHue && h - candidate.maxHue < error) {
                best = candidate;
                error = h - candidate.maxHue;
            } else if (candidate.maxHue > 1.0f && h >= fract(candidate.maxHue)
                    && h - fract(candidate.maxHue) < error) {
                best = candidate;
                error = h - fract(candidate.maxHue);
            } else if (candidate.minHue < 0.0f && h <= fract(candidate.minHue)
                    && fract(candidate.minHue) - h < error) {
                best = candidate;
                error = fract(candidate.minHue) - h;
            }
        }

        return best;
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }

    static class TonalPalette {
        final float[] h;
        final float[] s;
        final float[] l;
        final float minHue;
        final float maxHue;

        TonalPalette(float[] h, float[] s, float[] l) {
            if (h.length != s.length || s.length != l.length) {
                throw new IllegalArgumentException("All arrays should have the same size. h: "
                        + Arrays.toString(h) + " s: " + Arrays.toString(s) + " l: "
                        + Arrays.toString(l));
            }
            this.h = h;
            this.s = s;
            this.l = l;

            float minHue = Float.POSITIVE_INFINITY;
            float maxHue = Float.NEGATIVE_INFINITY;

            for (float v : h) {
                minHue = Math.min(v, minHue);
                maxHue = Math.max(v, maxHue);
            }

            this.minHue = minHue;
            this.maxHue = maxHue;
        }
    }

    /**
     * Representation of an HSL color range.
     * <ul>
     * <li>hsl[0] is Hue [0 .. 360)</li>
     * <li>hsl[1] is Saturation [0...1]</li>
     * <li>hsl[2] is Lightness [0...1]</li>
     * </ul>
     */
    @VisibleForTesting
    public static class ColorRange {
        private Range<Float> mHue;
        private Range<Float> mSaturation;
        private Range<Float> mLightness;

        public ColorRange(Range<Float> hue, Range<Float> saturation, Range<Float> lightness) {
            mHue = hue;
            mSaturation = saturation;
            mLightness = lightness;
        }

        public boolean containsColor(float h, float s, float l) {
            if (!mHue.contains(h)) {
                return false;
            } else if (!mSaturation.contains(s)) {
                return false;
            } else if (!mLightness.contains(l)) {
                return false;
            }
            return true;
        }

        public float[] getCenter() {
            return new float[] {
                    mHue.getLower() + (mHue.getUpper() - mHue.getLower()) / 2f,
                    mSaturation.getLower() + (mSaturation.getUpper() - mSaturation.getLower()) / 2f,
                    mLightness.getLower() + (mLightness.getUpper() - mLightness.getLower()) / 2f
            };
        }

        @Override
        public String toString() {
            return String.format("H: %s, S: %s, L %s", mHue, mSaturation, mLightness);
        }
    }

    @VisibleForTesting
    public static class ConfigParser {
        private final ArrayList<TonalPalette> mTonalPalettes;
        private final ArrayList<ColorRange> mBlacklistedColors;

        public ConfigParser(Context context) {
            mTonalPalettes = new ArrayList<>();
            mBlacklistedColors = new ArrayList<>();

            // Load all palettes and the blacklist from an XML.
            try {
                XmlPullParser parser = context.getResources().getXml(R.xml.color_extraction);
                int eventType = parser.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_DOCUMENT ||
                            eventType == XmlPullParser.END_TAG) {
                        // just skip
                    } else if (eventType == XmlPullParser.START_TAG) {
                        String tagName = parser.getName();
                        if (tagName.equals("palettes")) {
                            parsePalettes(parser);
                        } else if (tagName.equals("blacklist")) {
                            parseBlacklist(parser);
                        }
                    } else {
                        throw new XmlPullParserException("Invalid XML event " + eventType + " - "
                                + parser.getName(), parser, null);
                    }
                    eventType = parser.next();
                }
            } catch (XmlPullParserException | IOException e) {
                throw new RuntimeException(e);
            }
        }

        public ArrayList<TonalPalette> getTonalPalettes() {
            return mTonalPalettes;
        }

        public ArrayList<ColorRange> getBlacklistedColors() {
            return mBlacklistedColors;
        }

        private void parseBlacklist(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            parser.require(XmlPullParser.START_TAG, null, "blacklist");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                // Starts by looking for the entry tag
                if (name.equals("range")) {
                    mBlacklistedColors.add(readRange(parser));
                    parser.next();
                } else {
                    throw new XmlPullParserException("Invalid tag: " + name, parser, null);
                }
            }
        }

        private ColorRange readRange(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            parser.require(XmlPullParser.START_TAG, null, "range");
            float[] h = readFloatArray(parser.getAttributeValue(null, "h"));
            float[] s = readFloatArray(parser.getAttributeValue(null, "s"));
            float[] l = readFloatArray(parser.getAttributeValue(null, "l"));

            if (h == null || s == null || l == null) {
                throw new XmlPullParserException("Incomplete range tag.", parser, null);
            }

            return new ColorRange(new Range<>(h[0], h[1]), new Range<>(s[0], s[1]),
                    new Range<>(l[0], l[1]));
        }

        private void parsePalettes(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            parser.require(XmlPullParser.START_TAG, null, "palettes");
            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                // Starts by looking for the entry tag
                if (name.equals("palette")) {
                    mTonalPalettes.add(readPalette(parser));
                    parser.next();
                } else {
                    throw new XmlPullParserException("Invalid tag: " + name);
                }
            }
        }

        private TonalPalette readPalette(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            parser.require(XmlPullParser.START_TAG, null, "palette");

            float[] h = readFloatArray(parser.getAttributeValue(null, "h"));
            float[] s = readFloatArray(parser.getAttributeValue(null, "s"));
            float[] l = readFloatArray(parser.getAttributeValue(null, "l"));

            if (h == null || s == null || l == null) {
                throw new XmlPullParserException("Incomplete range tag.", parser, null);
            }

            return new TonalPalette(h, s, l);
        }

        private float[] readFloatArray(String attributeValue)
                throws IOException, XmlPullParserException {
            String[] tokens = attributeValue.replaceAll(" ", "").replaceAll("\n", "").split(",");
            float[] numbers = new float[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                numbers[i] = Float.parseFloat(tokens[i]);
            }
            return numbers;
        }
    }
}