/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.custom.palette;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.AsyncTask;
import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.util.TimingLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A helper class to extract prominent colors from an image.
 * <p>
 * A number of colors with different profiles are extracted from the image:
 * <ul>
 *     <li>Vibrant</li>
 *     <li>Vibrant Dark</li>
 *     <li>Vibrant Light</li>
 *     <li>Muted</li>
 *     <li>Muted Dark</li>
 *     <li>Muted Light</li>
 * </ul>
 * These can be retrieved from the appropriate getter method.
 *
 * <p>
 * Instances are created with a {@link Builder} which supports several options to tweak the
 * generated Palette. See that class' documentation for more information.
 * <p>
 * Generation should always be completed on a background thread, ideally the one in
 * which you load your image on. {@link Builder} supports both synchronous and asynchronous
 * generation:
 *
 * <pre>
 * // Synchronous
 * Palette p = Palette.from(bitmap).generate();
 *
 * // Asynchronous
 * Palette.from(bitmap).generate(new PaletteAsyncListener() {
 *     public void onGenerated(Palette p) {
 *         // Use generated instance
 *     }
 * });
 * </pre>
 *
 * @hide
 */
public final class Palette {

    /**
     * Listener to be used with {@link #generateAsync(Bitmap, PaletteAsyncListener)} or
     * {@link #generateAsync(Bitmap, int, PaletteAsyncListener)}
     */
    public interface PaletteAsyncListener {

        /**
         * Called when the {@link Palette} has been generated.
         */
        void onGenerated(Palette palette);
    }

    private static final int DEFAULT_RESIZE_BITMAP_MAX_DIMENSION = 192;
    private static final int DEFAULT_CALCULATE_NUMBER_COLORS = 16;

    private static final float MIN_CONTRAST_TITLE_TEXT = 3.0f;
    private static final float MIN_CONTRAST_BODY_TEXT = 4.5f;

    private static final String LOG_TAG = "Palette";
    private static final boolean LOG_TIMINGS = false;

    /**
     * Start generating a {@link Palette} with the returned {@link Builder} instance.
     */
    public static Builder from(Bitmap bitmap) {
        return new Builder(bitmap);
    }

    /**
     * Generate a {@link Palette} from the pre-generated list of {@link Palette.Swatch} swatches.
     * This is useful for testing, or if you want to resurrect a {@link Palette} instance from a
     * list of swatches. Will return null if the {@code swatches} is null.
     */
    public static Palette from(List<Swatch> swatches) {
        return new Builder(swatches).generate();
    }

    /**
     * @deprecated Use {@link Builder} to generate the Palette.
     */
    @Deprecated
    public static Palette generate(Bitmap bitmap) {
        return from(bitmap).generate();
    }

    /**
     * @deprecated Use {@link Builder} to generate the Palette.
     */
    @Deprecated
    public static Palette generate(Bitmap bitmap, int numColors) {
        return from(bitmap).maximumColorCount(numColors).generate();
    }

    /**
     * @deprecated Use {@link Builder} to generate the Palette.
     */
    @Deprecated
    public static AsyncTask<Bitmap, Void, Palette> generateAsync(
            Bitmap bitmap, PaletteAsyncListener listener) {
        return from(bitmap).generate(listener);
    }

    /**
     * @deprecated Use {@link Builder} to generate the Palette.
     */
    @Deprecated
    public static AsyncTask<Bitmap, Void, Palette> generateAsync(
            final Bitmap bitmap, final int numColors, final PaletteAsyncListener listener) {
        return from(bitmap).maximumColorCount(numColors).generate(listener);
    }

    private final List<Swatch> mSwatches;
    private final Generator mGenerator;

    private Palette(List<Swatch> swatches, Generator generator) {
        mSwatches = swatches;
        mGenerator = generator;
    }

    /**
     * Returns all of the swatches which make up the palette.
     */
    public List<Swatch> getSwatches() {
        return Collections.unmodifiableList(mSwatches);
    }

    /**
     * Returns the most vibrant swatch in the palette. Might be null.
     */
    @Nullable
    public Swatch getVibrantSwatch() {
        return mGenerator.getVibrantSwatch();
    }

    /**
     * Returns a light and vibrant swatch from the palette. Might be null.
     */
    @Nullable
    public Swatch getLightVibrantSwatch() {
        return mGenerator.getLightVibrantSwatch();
    }

    /**
     * Returns a dark and vibrant swatch from the palette. Might be null.
     */
    @Nullable
    public Swatch getDarkVibrantSwatch() {
        return mGenerator.getDarkVibrantSwatch();
    }

    /**
     * Returns a muted swatch from the palette. Might be null.
     */
    @Nullable
    public Swatch getMutedSwatch() {
        return mGenerator.getMutedSwatch();
    }

    /**
     * Returns a muted and light swatch from the palette. Might be null.
     */
    @Nullable
    public Swatch getLightMutedSwatch() {
        return mGenerator.getLightMutedSwatch();
    }

    /**
     * Returns a muted and dark swatch from the palette. Might be null.
     */
    @Nullable
    public Swatch getDarkMutedSwatch() {
        return mGenerator.getDarkMutedSwatch();
    }

    /**
     * Returns the most vibrant color in the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getVibrantColor(@ColorInt int defaultColor) {
        Swatch swatch = getVibrantSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Returns a light and vibrant color from the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getLightVibrantColor(@ColorInt int defaultColor) {
        Swatch swatch = getLightVibrantSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Returns a dark and vibrant color from the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getDarkVibrantColor(@ColorInt int defaultColor) {
        Swatch swatch = getDarkVibrantSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Returns a muted color from the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getMutedColor(@ColorInt int defaultColor) {
        Swatch swatch = getMutedSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Returns a muted and light color from the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getLightMutedColor(@ColorInt int defaultColor) {
        Swatch swatch = getLightMutedSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Returns a muted and dark color from the palette as an RGB packed int.
     *
     * @param defaultColor value to return if the swatch isn't available
     */
    @ColorInt
    public int getDarkMutedColor(@ColorInt int defaultColor) {
        Swatch swatch = getDarkMutedSwatch();
        return swatch != null ? swatch.getRgb() : defaultColor;
    }

    /**
     * Scale the bitmap down so that it's largest dimension is {@code targetMaxDimension}.
     * If {@code bitmap} is smaller than this, then it is returned.
     */
    private static Bitmap scaleBitmapDown(Bitmap bitmap, final int targetMaxDimension) {
        final int maxDimension = Math.max(bitmap.getWidth(), bitmap.getHeight());

        if (maxDimension <= targetMaxDimension) {
            // If the bitmap is small enough already, just return it
            return bitmap;
        }

        final float scaleRatio = targetMaxDimension / (float) maxDimension;
        return Bitmap.createScaledBitmap(bitmap,
                Math.round(bitmap.getWidth() * scaleRatio),
                Math.round(bitmap.getHeight() * scaleRatio),
                false);
    }

    /**
     * Represents a color swatch generated from an image's palette. The RGB color can be retrieved
     * by calling {@link #getRgb()}.
     */
    public static final class Swatch {
        private final int mRed, mGreen, mBlue;
        private final int mRgb;
        private final int mPopulation;

        private boolean mGeneratedTextColors;
        private int mTitleTextColor;
        private int mBodyTextColor;

        private float[] mHsl;

        public Swatch(@ColorInt int color, int population) {
            mRed = Color.red(color);
            mGreen = Color.green(color);
            mBlue = Color.blue(color);
            mRgb = color;
            mPopulation = population;
        }

        Swatch(int red, int green, int blue, int population) {
            mRed = red;
            mGreen = green;
            mBlue = blue;
            mRgb = Color.rgb(red, green, blue);
            mPopulation = population;
        }

        /**
         * @return this swatch's RGB color value
         */
        @ColorInt
        public int getRgb() {
            return mRgb;
        }

        /**
         * Return this swatch's HSL values.
         *     hsv[0] is Hue [0 .. 360)
         *     hsv[1] is Saturation [0...1]
         *     hsv[2] is Lightness [0...1]
         */
        public float[] getHsl() {
            if (mHsl == null) {
                mHsl = new float[3];
                ColorUtils.RGBToHSL(mRed, mGreen, mBlue, mHsl);
            }
            return mHsl;
        }

        /**
         * @return the number of pixels represented by this swatch
         */
        public int getPopulation() {
            return mPopulation;
        }

        /**
         * Returns an appropriate color to use for any 'title' text which is displayed over this
         * {@link Swatch}'s color. This color is guaranteed to have sufficient contrast.
         */
        @ColorInt
        public int getTitleTextColor() {
            ensureTextColorsGenerated();
            return mTitleTextColor;
        }

        /**
         * Returns an appropriate color to use for any 'body' text which is displayed over this
         * {@link Swatch}'s color. This color is guaranteed to have sufficient contrast.
         */
        @ColorInt
        public int getBodyTextColor() {
            ensureTextColorsGenerated();
            return mBodyTextColor;
        }

        private void ensureTextColorsGenerated() {
            if (!mGeneratedTextColors) {
                // First check white, as most colors will be dark
                final int lightBodyAlpha = ColorUtils.calculateMinimumAlpha(
                        Color.WHITE, mRgb, MIN_CONTRAST_BODY_TEXT);
                final int lightTitleAlpha = ColorUtils.calculateMinimumAlpha(
                        Color.WHITE, mRgb, MIN_CONTRAST_TITLE_TEXT);

                if (lightBodyAlpha != -1 && lightTitleAlpha != -1) {
                    // If we found valid light values, use them and return
                    mBodyTextColor = ColorUtils.setAlphaComponent(Color.WHITE, lightBodyAlpha);
                    mTitleTextColor = ColorUtils.setAlphaComponent(Color.WHITE, lightTitleAlpha);
                    mGeneratedTextColors = true;
                    return;
                }

                final int darkBodyAlpha = ColorUtils.calculateMinimumAlpha(
                        Color.BLACK, mRgb, MIN_CONTRAST_BODY_TEXT);
                final int darkTitleAlpha = ColorUtils.calculateMinimumAlpha(
                        Color.BLACK, mRgb, MIN_CONTRAST_TITLE_TEXT);

                if (darkBodyAlpha != -1 && darkBodyAlpha != -1) {
                    // If we found valid dark values, use them and return
                    mBodyTextColor = ColorUtils.setAlphaComponent(Color.BLACK, darkBodyAlpha);
                    mTitleTextColor = ColorUtils.setAlphaComponent(Color.BLACK, darkTitleAlpha);
                    mGeneratedTextColors = true;
                    return;
                }

                // If we reach here then we can not find title and body values which use the same
                // lightness, we need to use mismatched values
                mBodyTextColor = lightBodyAlpha != -1
                        ? ColorUtils.setAlphaComponent(Color.WHITE, lightBodyAlpha)
                        : ColorUtils.setAlphaComponent(Color.BLACK, darkBodyAlpha);
                mTitleTextColor = lightTitleAlpha != -1
                        ? ColorUtils.setAlphaComponent(Color.WHITE, lightTitleAlpha)
                        : ColorUtils.setAlphaComponent(Color.BLACK, darkTitleAlpha);
                mGeneratedTextColors = true;
            }
        }

        @Override
        public String toString() {
            return new StringBuilder(getClass().getSimpleName())
                    .append(" [RGB: #").append(Integer.toHexString(getRgb())).append(']')
                    .append(" [HSL: ").append(Arrays.toString(getHsl())).append(']')
                    .append(" [Population: ").append(mPopulation).append(']')
                    .append(" [Title Text: #").append(Integer.toHexString(getTitleTextColor()))
                    .append(']')
                    .append(" [Body Text: #").append(Integer.toHexString(getBodyTextColor()))
                    .append(']').toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Swatch swatch = (Swatch) o;
            return mPopulation == swatch.mPopulation && mRgb == swatch.mRgb;
        }

        @Override
        public int hashCode() {
            return 31 * mRgb + mPopulation;
        }
    }

    /**
     * Builder class for generating {@link Palette} instances.
     */
    public static final class Builder {
        private List<Swatch> mSwatches;
        private Bitmap mBitmap;
        private int mMaxColors = DEFAULT_CALCULATE_NUMBER_COLORS;
        private int mResizeMaxDimension = DEFAULT_RESIZE_BITMAP_MAX_DIMENSION;
        private final List<Filter> mFilters = new ArrayList<>();

        private Generator mGenerator;

        /**
         * Construct a new {@link Builder} using a source {@link Bitmap}
         */
        public Builder(Bitmap bitmap) {
            this();
            if (bitmap == null || bitmap.isRecycled()) {
                throw new IllegalArgumentException("Bitmap is not valid");
            }
            mBitmap = bitmap;
        }

        /**
         * Construct a new {@link Builder} using a list of {@link Swatch} instances.
         * Typically only used for testing.
         */
        public Builder(List<Swatch> swatches) {
            this();
            if (swatches == null || swatches.isEmpty()) {
                throw new IllegalArgumentException("List of Swatches is not valid");
            }
            mSwatches = swatches;
        }

        private Builder() {
            mFilters.add(DEFAULT_FILTER);
        }

        /**
         * Set the {@link Generator} to use when generating the {@link Palette}. If this is called
         * with {@code null} then the default generator will be used.
         */
        Builder generator(Generator generator) {
            mGenerator = generator;
            return this;
        }

        /**
         * Set the maximum number of colors to use in the quantization step when using a
         * {@link android.graphics.Bitmap} as the source.
         * <p>
         * Good values for depend on the source image type. For landscapes, good values are in
         * the range 10-16. For images which are largely made up of people's faces then this
         * value should be increased to ~24.
         */
        public Builder maximumColorCount(int colors) {
            mMaxColors = colors;
            return this;
        }

        /**
         * Set the resize value when using a {@link android.graphics.Bitmap} as the source.
         * If the bitmap's largest dimension is greater than the value specified, then the bitmap
         * will be resized so that it's largest dimension matches {@code maxDimension}. If the
         * bitmap is smaller or equal, the original is used as-is.
         * <p>
         * This value has a large effect on the processing time. The larger the resized image is,
         * the greater time it will take to generate the palette. The smaller the image is, the
         * more detail is lost in the resulting image and thus less precision for color selection.
         */
        public Builder resizeBitmapSize(int maxDimension) {
            mResizeMaxDimension = maxDimension;
            return this;
        }

        /**
         * Clear all added filters. This includes any default filters added automatically by
         * {@link Palette}.
         */
        public Builder clearFilters() {
            mFilters.clear();
            return this;
        }

        /**
         * Add a filter to be able to have fine grained controlled over the colors which are
         * allowed in the resulting palette.
         *
         * @param filter filter to add.
         */
        public Builder addFilter(Filter filter) {
            if (filter != null) {
                mFilters.add(filter);
            }
            return this;
        }

        /**
         * Generate and return the {@link Palette} synchronously.
         */
        public Palette generate() {
            final TimingLogger logger = LOG_TIMINGS
                    ? new TimingLogger(LOG_TAG, "Generation")
                    : null;

            List<Swatch> swatches;

            if (mBitmap != null) {
                // We have a Bitmap so we need to quantization to reduce the number of colors

                if (mResizeMaxDimension <= 0) {
                    throw new IllegalArgumentException(
                            "Minimum dimension size for resizing should should be >= 1");
                }

                // First we'll scale down the bitmap so it's largest dimension is as specified
                final Bitmap scaledBitmap = scaleBitmapDown(mBitmap, mResizeMaxDimension);

                if (logger != null) {
                    logger.addSplit("Processed Bitmap");
                }

                // Now generate a quantizer from the Bitmap
                final int width = scaledBitmap.getWidth();
                final int height = scaledBitmap.getHeight();
                final int[] pixels = new int[width * height];
                scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                final ColorCutQuantizer quantizer = new ColorCutQuantizer(pixels, mMaxColors,
                        mFilters.isEmpty() ? null : mFilters.toArray(new Filter[0]));

                // If created a new bitmap, recycle it
                if (scaledBitmap != mBitmap) {
                    scaledBitmap.recycle();
                }
                swatches = quantizer.getQuantizedColors();

                if (logger != null) {
                    logger.addSplit("Color quantization completed");
                }
            } else {
                // Else we're using the provided swatches
                swatches = mSwatches;
            }

            // If we haven't been provided with a generator, use the default
            if (mGenerator == null) {
                mGenerator = new DefaultGenerator();
            }

            // Now call let the Generator do it's thing
            mGenerator.generate(swatches);

            if (logger != null) {
                logger.addSplit("Generator.generate() completed");
            }

            // Now create a Palette instance
            Palette p = new Palette(swatches, mGenerator);

            if (logger != null) {
                logger.addSplit("Created Palette");
                logger.dumpToLog();
            }

            return p;
        }

        /**
         * Generate the {@link Palette} asynchronously. The provided listener's
         * {@link PaletteAsyncListener#onGenerated} method will be called with the palette when
         * generated.
         */
        public AsyncTask<Bitmap, Void, Palette> generate(final PaletteAsyncListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener can not be null");
            }

            AsyncTask<Bitmap, Void, Palette> task = new AsyncTask<Bitmap, Void, Palette>() {
                @Override
                protected Palette doInBackground(Bitmap... params) {
                    return generate();
                }

                @Override
                protected void onPostExecute(Palette colorExtractor) {
                    listener.onGenerated(colorExtractor);
                }
            };
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mBitmap);
            return task;
        }
    }

    static abstract class Generator {

        /**
         * This method will be called with the {@link Palette.Swatch} that represent an image.
         * You should process this list so that you have appropriate values when the other methods in
         * class are called.
         * <p>
         * This method will probably be called on a background thread.
         */
        public abstract void generate(List<Palette.Swatch> swatches);

        /**
         * Return the most vibrant {@link Palette.Swatch}
         */
        public Palette.Swatch getVibrantSwatch() {
            return null;
        }

        /**
         * Return a light and vibrant {@link Palette.Swatch}
         */
        public Palette.Swatch getLightVibrantSwatch() {
            return null;
        }

        /**
         * Return a dark and vibrant {@link Palette.Swatch}
         */
        public Palette.Swatch getDarkVibrantSwatch() {
            return null;
        }

        /**
         * Return a muted {@link Palette.Swatch}
         */
        public Palette.Swatch getMutedSwatch() {
            return null;
        }

        /**
         * Return a muted and light {@link Palette.Swatch}
         */
        public Palette.Swatch getLightMutedSwatch() {
            return null;
        }

        /**
         * Return a muted and dark {@link Palette.Swatch}
         */
        public Palette.Swatch getDarkMutedSwatch() {
            return null;
        }
    }

    /**
     * A Filter provides a mechanism for exercising fine-grained control over which colors
     * are valid within a resulting {@link Palette}.
     */
    public interface Filter {
        /**
         * Hook to allow clients to be able filter colors from resulting palette.
         *
         * @param rgb the color in RGB888.
         * @param hsl HSL representation of the color.
         *
         * @return true if the color is allowed, false if not.
         *
         * @see Builder#addFilter(Filter)
         */
        boolean isAllowed(int rgb, float[] hsl);
    }

    /**
     * The default filter.
     */
    private static final Filter DEFAULT_FILTER = new Filter() {
        private static final float BLACK_MAX_LIGHTNESS = 0.05f;
        private static final float WHITE_MIN_LIGHTNESS = 0.95f;

        @Override
        public boolean isAllowed(int rgb, float[] hsl) {
            return !isWhite(hsl) && !isBlack(hsl) && !isNearRedILine(hsl);
        }

        /**
         * @return true if the color represents a color which is close to black.
         */
        private boolean isBlack(float[] hslColor) {
            return hslColor[2] <= BLACK_MAX_LIGHTNESS;
        }

        /**
         * @return true if the color represents a color which is close to white.
         */
        private boolean isWhite(float[] hslColor) {
            return hslColor[2] >= WHITE_MIN_LIGHTNESS;
        }

        /**
         * @return true if the color lies close to the red side of the I line.
         */
        private boolean isNearRedILine(float[] hslColor) {
            return hslColor[0] >= 10f && hslColor[0] <= 37f && hslColor[1] <= 0.82f;
        }
    };
}
