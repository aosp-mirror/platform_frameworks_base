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

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.support.v4.graphics.ColorUtils;
import android.support.v7.graphics.Palette;

import com.android.systemui.R;

import java.util.List;

/**
 * A class the processes media notifications and extracts the right text and background colors.
 */
public class MediaNotificationProcessor {

    /**
     * The fraction below which we select the vibrant instead of the light/dark vibrant color
     */
    private static final float POPULATION_FRACTION_FOR_MORE_VIBRANT = 0.75f;
    private static final float POPULATION_FRACTION_FOR_WHITE_OR_BLACK = 2.5f;
    private static final float BLACK_MAX_LIGHTNESS = 0.08f;
    private static final float WHITE_MIN_LIGHTNESS = 0.92f;
    private static final int RESIZE_BITMAP_AREA = 150 * 150;
    private final ImageGradientColorizer mColorizer;
    private final Context mContext;
    private float[] mFilteredBackgroundHsl = null;
    private Palette.Filter mBlackWhiteFilter = (rgb, hsl) -> !isWhiteOrBlack(hsl);

    /**
     * The context of the notification. This is the app context of the package posting the
     * notification.
     */
    private final Context mPackageContext;
    private boolean mIsLowPriority;

    public MediaNotificationProcessor(Context context, Context packageContext) {
        mContext = context;
        mPackageContext = packageContext;
        mColorizer = new ImageGradientColorizer();
    }

    /**
     * Processes a builder of a media notification and calculates the appropriate colors that should
     * be used.
     *
     * @param notification the notification that is being processed
     * @param builder the recovered builder for the notification. this will be modified
     */
    public void processNotification(Notification notification, Notification.Builder builder) {
        Icon largeIcon = notification.getLargeIcon();
        Bitmap bitmap = null;
        Drawable drawable = null;
        if (largeIcon != null) {
            drawable = largeIcon.loadDrawable(mPackageContext);
            int backgroundColor = 0;
            if (notification.isColorizedMedia()) {
                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();
                int area = width * height;
                if (area > RESIZE_BITMAP_AREA) {
                    double factor = Math.sqrt((float) RESIZE_BITMAP_AREA / area);
                    width = (int) (factor * width);
                    height = (int) (factor * height);
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);

                // for the background we only take the left side of the image to ensure
                // a smooth transition
                Palette.Builder paletteBuilder = Palette.from(bitmap)
                        .setRegion(0, 0, bitmap.getWidth() / 2, bitmap.getHeight())
                        .clearFilters() // we want all colors, red / white / black ones too!
                        .resizeBitmapArea(RESIZE_BITMAP_AREA);
                Palette palette = paletteBuilder.generate();
                backgroundColor = findBackgroundColorAndFilter(palette);
                // we want the full region again
                paletteBuilder.setRegion(0, 0, bitmap.getWidth(), bitmap.getHeight());
                if (mFilteredBackgroundHsl != null) {
                    paletteBuilder.addFilter((rgb, hsl) -> {
                        // at least 10 degrees hue difference
                        float diff = Math.abs(hsl[0] - mFilteredBackgroundHsl[0]);
                        return diff > 10 && diff < 350;
                    });
                }
                paletteBuilder.addFilter(mBlackWhiteFilter);
                palette = paletteBuilder.generate();
                int foregroundColor;
                if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) {
                    Palette.Swatch first = palette.getDarkVibrantSwatch();
                    Palette.Swatch second = palette.getVibrantSwatch();
                    if (first != null && second != null) {
                        int firstPopulation = first.getPopulation();
                        int secondPopulation = second.getPopulation();
                        if (firstPopulation / secondPopulation
                                < POPULATION_FRACTION_FOR_MORE_VIBRANT) {
                            foregroundColor = second.getRgb();
                        } else {
                            foregroundColor = first.getRgb();
                        }
                    } else if (first != null) {
                        foregroundColor = first.getRgb();
                    } else if (second != null) {
                        foregroundColor = second.getRgb();
                    } else {
                        first = palette.getMutedSwatch();
                        second = palette.getDarkMutedSwatch();
                        if (first != null && second != null) {
                            float firstSaturation = first.getHsl()[1];
                            float secondSaturation = second.getHsl()[1];
                            if (firstSaturation > secondSaturation) {
                                foregroundColor = first.getRgb();
                            } else {
                                foregroundColor = second.getRgb();
                            }
                        } else if (first != null) {
                            foregroundColor = first.getRgb();
                        } else if (second != null) {
                            foregroundColor = second.getRgb();
                        } else {
                            foregroundColor = Color.BLACK;
                        }
                    }
                } else {
                    Palette.Swatch first = palette.getLightVibrantSwatch();
                    Palette.Swatch second = palette.getVibrantSwatch();
                    if (first != null && second != null) {
                        int firstPopulation = first.getPopulation();
                        int secondPopulation = second.getPopulation();
                        if (firstPopulation / secondPopulation
                                < POPULATION_FRACTION_FOR_MORE_VIBRANT) {
                            foregroundColor = second.getRgb();
                        } else {
                            foregroundColor = first.getRgb();
                        }
                    } else if (first != null) {
                        foregroundColor = first.getRgb();
                    } else if (second != null) {
                        foregroundColor = second.getRgb();
                    } else {
                        first = palette.getMutedSwatch();
                        second = palette.getLightMutedSwatch();
                        if (first != null && second != null) {
                            float firstSaturation = first.getHsl()[1];
                            float secondSaturation = second.getHsl()[1];
                            if (firstSaturation > secondSaturation) {
                                foregroundColor = first.getRgb();
                            } else {
                                foregroundColor = second.getRgb();
                            }
                        } else if (first != null) {
                            foregroundColor = first.getRgb();
                        } else if (second != null) {
                            foregroundColor = second.getRgb();
                        } else {
                            foregroundColor = Color.WHITE;
                        }
                    }
                }
                builder.setColorPalette(backgroundColor, foregroundColor);
            } else {
                int id = mIsLowPriority
                        ? R.color.notification_material_background_low_priority_color
                        : R.color.notification_material_background_color;
                backgroundColor = mContext.getColor(id);
            }
            Bitmap colorized = mColorizer.colorize(drawable, backgroundColor);
            builder.setLargeIcon(Icon.createWithBitmap(colorized));
        }
    }

    private int findBackgroundColorAndFilter(Palette palette) {
        // by default we use the dominant palette
        Palette.Swatch dominantSwatch = palette.getDominantSwatch();
        if (dominantSwatch == null) {
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return Color.WHITE;
        }

        if (!isWhiteOrBlack(dominantSwatch.getHsl())) {
            mFilteredBackgroundHsl = dominantSwatch.getHsl();
            return dominantSwatch.getRgb();
        }
        // Oh well, we selected black or white. Lets look at the second color!
        List<Palette.Swatch> swatches = palette.getSwatches();
        float highestNonWhitePopulation = -1;
        Palette.Swatch second = null;
        for (Palette.Swatch swatch: swatches) {
            if (swatch != dominantSwatch
                    && swatch.getPopulation() > highestNonWhitePopulation
                    && !isWhiteOrBlack(swatch.getHsl())) {
                second = swatch;
                highestNonWhitePopulation = swatch.getPopulation();
            }
        }
        if (second == null) {
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return dominantSwatch.getRgb();
        }
        if (dominantSwatch.getPopulation() / highestNonWhitePopulation
                > POPULATION_FRACTION_FOR_WHITE_OR_BLACK) {
            // The dominant swatch is very dominant, lets take it!
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return dominantSwatch.getRgb();
        } else {
            mFilteredBackgroundHsl = second.getHsl();
            return second.getRgb();
        }
    }

    private boolean isWhiteOrBlack(float[] hsl) {
        return isBlack(hsl) || isWhite(hsl);
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

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }
}
