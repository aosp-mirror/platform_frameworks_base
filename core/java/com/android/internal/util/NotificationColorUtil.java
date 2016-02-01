/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.ColorInt;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.VectorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TextAppearanceSpan;
import android.util.Log;
import android.util.Pair;

import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Helper class to process legacy (Holo) notifications to make them look like material notifications.
 *
 * @hide
 */
public class NotificationColorUtil {

    private static final String TAG = "NotificationColorUtil";
    private static final boolean DEBUG = false;

    private static final Object sLock = new Object();
    private static NotificationColorUtil sInstance;

    private final ImageUtils mImageUtils = new ImageUtils();
    private final WeakHashMap<Bitmap, Pair<Boolean, Integer>> mGrayscaleBitmapCache =
            new WeakHashMap<Bitmap, Pair<Boolean, Integer>>();

    private final int mGrayscaleIconMaxSize; // @dimen/notification_large_icon_width (64dp)

    public static NotificationColorUtil getInstance(Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new NotificationColorUtil(context);
            }
            return sInstance;
        }
    }

    private NotificationColorUtil(Context context) {
        mGrayscaleIconMaxSize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_large_icon_width);
    }

    /**
     * Checks whether a Bitmap is a small grayscale icon.
     * Grayscale here means "very close to a perfect gray"; icon means "no larger than 64dp".
     *
     * @param bitmap The bitmap to test.
     * @return True if the bitmap is grayscale; false if it is color or too large to examine.
     */
    public boolean isGrayscaleIcon(Bitmap bitmap) {
        // quick test: reject large bitmaps
        if (bitmap.getWidth() > mGrayscaleIconMaxSize
                || bitmap.getHeight() > mGrayscaleIconMaxSize) {
            return false;
        }

        synchronized (sLock) {
            Pair<Boolean, Integer> cached = mGrayscaleBitmapCache.get(bitmap);
            if (cached != null) {
                if (cached.second == bitmap.getGenerationId()) {
                    return cached.first;
                }
            }
        }
        boolean result;
        int generationId;
        synchronized (mImageUtils) {
            result = mImageUtils.isGrayscale(bitmap);

            // generationId and the check whether the Bitmap is grayscale can't be read atomically
            // here. However, since the thread is in the process of posting the notification, we can
            // assume that it doesn't modify the bitmap while we are checking the pixels.
            generationId = bitmap.getGenerationId();
        }
        synchronized (sLock) {
            mGrayscaleBitmapCache.put(bitmap, Pair.create(result, generationId));
        }
        return result;
    }

    /**
     * Checks whether a Drawable is a small grayscale icon.
     * Grayscale here means "very close to a perfect gray"; icon means "no larger than 64dp".
     *
     * @param d The drawable to test.
     * @return True if the bitmap is grayscale; false if it is color or too large to examine.
     */
    public boolean isGrayscaleIcon(Drawable d) {
        if (d == null) {
            return false;
        } else if (d instanceof BitmapDrawable) {
            BitmapDrawable bd = (BitmapDrawable) d;
            return bd.getBitmap() != null && isGrayscaleIcon(bd.getBitmap());
        } else if (d instanceof AnimationDrawable) {
            AnimationDrawable ad = (AnimationDrawable) d;
            int count = ad.getNumberOfFrames();
            return count > 0 && isGrayscaleIcon(ad.getFrame(0));
        } else if (d instanceof VectorDrawable) {
            // We just assume you're doing the right thing if using vectors
            return true;
        } else {
            return false;
        }
    }

    public boolean isGrayscaleIcon(Context context, Icon icon) {
        if (icon == null) {
            return false;
        }
        switch (icon.getType()) {
            case Icon.TYPE_BITMAP:
                return isGrayscaleIcon(icon.getBitmap());
            case Icon.TYPE_RESOURCE:
                return isGrayscaleIcon(context, icon.getResId());
            default:
                return false;
        }
    }

    /**
     * Checks whether a drawable with a resoure id is a small grayscale icon.
     * Grayscale here means "very close to a perfect gray"; icon means "no larger than 64dp".
     *
     * @param context The context to load the drawable from.
     * @return True if the bitmap is grayscale; false if it is color or too large to examine.
     */
    public boolean isGrayscaleIcon(Context context, int drawableResId) {
        if (drawableResId != 0) {
            try {
                return isGrayscaleIcon(context.getDrawable(drawableResId));
            } catch (Resources.NotFoundException ex) {
                Log.e(TAG, "Drawable not found: " + drawableResId);
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * Inverts all the grayscale colors set by {@link android.text.style.TextAppearanceSpan}s on
     * the text.
     *
     * @param charSequence The text to process.
     * @return The color inverted text.
     */
    public CharSequence invertCharSequenceColors(CharSequence charSequence) {
        if (charSequence instanceof Spanned) {
            Spanned ss = (Spanned) charSequence;
            Object[] spans = ss.getSpans(0, ss.length(), Object.class);
            SpannableStringBuilder builder = new SpannableStringBuilder(ss.toString());
            for (Object span : spans) {
                Object resultSpan = span;
                if (span instanceof TextAppearanceSpan) {
                    resultSpan = processTextAppearanceSpan((TextAppearanceSpan) span);
                }
                builder.setSpan(resultSpan, ss.getSpanStart(span), ss.getSpanEnd(span),
                        ss.getSpanFlags(span));
            }
            return builder;
        }
        return charSequence;
    }

    private TextAppearanceSpan processTextAppearanceSpan(TextAppearanceSpan span) {
        ColorStateList colorStateList = span.getTextColor();
        if (colorStateList != null) {
            int[] colors = colorStateList.getColors();
            boolean changed = false;
            for (int i = 0; i < colors.length; i++) {
                if (ImageUtils.isGrayscale(colors[i])) {

                    // Allocate a new array so we don't change the colors in the old color state
                    // list.
                    if (!changed) {
                        colors = Arrays.copyOf(colors, colors.length);
                    }
                    colors[i] = processColor(colors[i]);
                    changed = true;
                }
            }
            if (changed) {
                return new TextAppearanceSpan(
                        span.getFamily(), span.getTextStyle(), span.getTextSize(),
                        new ColorStateList(colorStateList.getStates(), colors),
                        span.getLinkTextColor());
            }
        }
        return span;
    }

    private int processColor(int color) {
        return Color.argb(Color.alpha(color),
                255 - Color.red(color),
                255 - Color.green(color),
                255 - Color.blue(color));
    }

    /**
     * Finds a suitable color such that there's enough contrast.
     *
     * @param color the color to start searching from.
     * @param other the color to ensure contrast against. Assumed to be lighter than {@param color}
     * @param findFg if true, we assume {@param color} is a foreground, otherwise a background.
     * @param minRatio the minimum contrast ratio required.
     * @return a color with the same hue as {@param color}, potentially darkened to meet the
     *          contrast ratio.
     */
    private static int findContrastColor(int color, int other, boolean findFg, double minRatio) {
        int fg = findFg ? color : other;
        int bg = findFg ? other : color;
        if (ColorUtilsFromCompat.calculateContrast(fg, bg) >= minRatio) {
            return color;
        }

        double[] lab = new double[3];
        ColorUtilsFromCompat.colorToLAB(findFg ? fg : bg, lab);

        double low = 0, high = lab[0];
        final double a = lab[1], b = lab[2];
        for (int i = 0; i < 15 && high - low > 0.00001; i++) {
            final double l = (low + high) / 2;
            if (findFg) {
                fg = ColorUtilsFromCompat.LABToColor(l, a, b);
            } else {
                bg = ColorUtilsFromCompat.LABToColor(l, a, b);
            }
            if (ColorUtilsFromCompat.calculateContrast(fg, bg) > minRatio) {
                low = l;
            } else {
                high = l;
            }
        }
        return ColorUtilsFromCompat.LABToColor(low, a, b);
    }

    /**
     * Finds a text color with sufficient contrast over bg that has the same hue as the original
     * color, assuming it is for large text.
     */
    private static int ensureLargeTextContrast(int color, int bg) {
        return findContrastColor(color, bg, true, 3);
    }

    /**
     * Finds a text color with sufficient contrast over bg that has the same hue as the original
     * color.
     */
    private static int ensureTextContrast(int color, int bg) {
        return findContrastColor(color, bg, true, 4.5);
    }

    /** Finds a background color for a text view with given text color and hint text color, that
     * has the same hue as the original color.
     */
    public static int ensureTextBackgroundColor(int color, int textColor, int hintColor) {
        color = findContrastColor(color, hintColor, false, 3.0);
        return findContrastColor(color, textColor, false, 4.5);
    }

    private static String contrastChange(int colorOld, int colorNew, int bg) {
        return String.format("from %.2f:1 to %.2f:1",
                ColorUtilsFromCompat.calculateContrast(colorOld, bg),
                ColorUtilsFromCompat.calculateContrast(colorNew, bg));
    }

    /**
     * Resolves {@param color} to an actual color if it is {@link Notification#COLOR_DEFAULT}
     */
    public static int resolveColor(Context context, int color) {
        if (color == Notification.COLOR_DEFAULT) {
            return context.getColor(com.android.internal.R.color.notification_icon_default_color);
        }
        return color;
    }

    /**
     * Resolves a Notification's color such that it has enough contrast to be used as the
     * color for the Notification's action and header text.
     *
     * @param notificationColor the color of the notification or {@link Notification#COLOR_DEFAULT}
     * @return a color of the same hue with enough contrast against the backgrounds.
     */
    public static int resolveContrastColor(Context context, int notificationColor) {
        final int resolvedColor = resolveColor(context, notificationColor);

        final int actionBg = context.getColor(
                com.android.internal.R.color.notification_action_list);
        final int notiBg = context.getColor(
                com.android.internal.R.color.notification_material_background_color);

        int color = resolvedColor;
        color = NotificationColorUtil.ensureLargeTextContrast(color, actionBg);
        color = NotificationColorUtil.ensureTextContrast(color, notiBg);

        if (color != resolvedColor) {
            if (DEBUG){
                Log.w(TAG, String.format(
                        "Enhanced contrast of notification for %s %s (over action)"
                                + " and %s (over background) by changing #%s to %s",
                        context.getPackageName(),
                        NotificationColorUtil.contrastChange(resolvedColor, color, actionBg),
                        NotificationColorUtil.contrastChange(resolvedColor, color, notiBg),
                        Integer.toHexString(resolvedColor), Integer.toHexString(color)));
            }
        }
        return color;
    }

    /**
     * Framework copy of functions needed from android.support.v4.graphics.ColorUtils.
     */
    private static class ColorUtilsFromCompat {
        private static final double XYZ_WHITE_REFERENCE_X = 95.047;
        private static final double XYZ_WHITE_REFERENCE_Y = 100;
        private static final double XYZ_WHITE_REFERENCE_Z = 108.883;
        private static final double XYZ_EPSILON = 0.008856;
        private static final double XYZ_KAPPA = 903.3;

        private static final int MIN_ALPHA_SEARCH_MAX_ITERATIONS = 10;
        private static final int MIN_ALPHA_SEARCH_PRECISION = 1;

        private static final ThreadLocal<double[]> TEMP_ARRAY = new ThreadLocal<>();

        private ColorUtilsFromCompat() {}

        /**
         * Composite two potentially translucent colors over each other and returns the result.
         */
        public static int compositeColors(@ColorInt int foreground, @ColorInt int background) {
            int bgAlpha = Color.alpha(background);
            int fgAlpha = Color.alpha(foreground);
            int a = compositeAlpha(fgAlpha, bgAlpha);

            int r = compositeComponent(Color.red(foreground), fgAlpha,
                    Color.red(background), bgAlpha, a);
            int g = compositeComponent(Color.green(foreground), fgAlpha,
                    Color.green(background), bgAlpha, a);
            int b = compositeComponent(Color.blue(foreground), fgAlpha,
                    Color.blue(background), bgAlpha, a);

            return Color.argb(a, r, g, b);
        }

        private static int compositeAlpha(int foregroundAlpha, int backgroundAlpha) {
            return 0xFF - (((0xFF - backgroundAlpha) * (0xFF - foregroundAlpha)) / 0xFF);
        }

        private static int compositeComponent(int fgC, int fgA, int bgC, int bgA, int a) {
            if (a == 0) return 0;
            return ((0xFF * fgC * fgA) + (bgC * bgA * (0xFF - fgA))) / (a * 0xFF);
        }

        /**
         * Returns the luminance of a color as a float between {@code 0.0} and {@code 1.0}.
         * <p>Defined as the Y component in the XYZ representation of {@code color}.</p>
         */
        @FloatRange(from = 0.0, to = 1.0)
        public static double calculateLuminance(@ColorInt int color) {
            final double[] result = getTempDouble3Array();
            colorToXYZ(color, result);
            // Luminance is the Y component
            return result[1] / 100;
        }

        /**
         * Returns the contrast ratio between {@code foreground} and {@code background}.
         * {@code background} must be opaque.
         * <p>
         * Formula defined
         * <a href="http://www.w3.org/TR/2008/REC-WCAG20-20081211/#contrast-ratiodef">here</a>.
         */
        public static double calculateContrast(@ColorInt int foreground, @ColorInt int background) {
            if (Color.alpha(background) != 255) {
                throw new IllegalArgumentException("background can not be translucent: #"
                        + Integer.toHexString(background));
            }
            if (Color.alpha(foreground) < 255) {
                // If the foreground is translucent, composite the foreground over the background
                foreground = compositeColors(foreground, background);
            }

            final double luminance1 = calculateLuminance(foreground) + 0.05;
            final double luminance2 = calculateLuminance(background) + 0.05;

            // Now return the lighter luminance divided by the darker luminance
            return Math.max(luminance1, luminance2) / Math.min(luminance1, luminance2);
        }

        /**
         * Convert the ARGB color to its CIE Lab representative components.
         *
         * @param color  the ARGB color to convert. The alpha component is ignored
         * @param outLab 3-element array which holds the resulting LAB components
         */
        public static void colorToLAB(@ColorInt int color, @NonNull double[] outLab) {
            RGBToLAB(Color.red(color), Color.green(color), Color.blue(color), outLab);
        }

        /**
         * Convert RGB components to its CIE Lab representative components.
         *
         * <ul>
         * <li>outLab[0] is L [0 ...1)</li>
         * <li>outLab[1] is a [-128...127)</li>
         * <li>outLab[2] is b [-128...127)</li>
         * </ul>
         *
         * @param r      red component value [0..255]
         * @param g      green component value [0..255]
         * @param b      blue component value [0..255]
         * @param outLab 3-element array which holds the resulting LAB components
         */
        public static void RGBToLAB(@IntRange(from = 0x0, to = 0xFF) int r,
                @IntRange(from = 0x0, to = 0xFF) int g, @IntRange(from = 0x0, to = 0xFF) int b,
                @NonNull double[] outLab) {
            // First we convert RGB to XYZ
            RGBToXYZ(r, g, b, outLab);
            // outLab now contains XYZ
            XYZToLAB(outLab[0], outLab[1], outLab[2], outLab);
            // outLab now contains LAB representation
        }

        /**
         * Convert the ARGB color to it's CIE XYZ representative components.
         *
         * <p>The resulting XYZ representation will use the D65 illuminant and the CIE
         * 2° Standard Observer (1931).</p>
         *
         * <ul>
         * <li>outXyz[0] is X [0 ...95.047)</li>
         * <li>outXyz[1] is Y [0...100)</li>
         * <li>outXyz[2] is Z [0...108.883)</li>
         * </ul>
         *
         * @param color  the ARGB color to convert. The alpha component is ignored
         * @param outXyz 3-element array which holds the resulting LAB components
         */
        public static void colorToXYZ(@ColorInt int color, @NonNull double[] outXyz) {
            RGBToXYZ(Color.red(color), Color.green(color), Color.blue(color), outXyz);
        }

        /**
         * Convert RGB components to it's CIE XYZ representative components.
         *
         * <p>The resulting XYZ representation will use the D65 illuminant and the CIE
         * 2° Standard Observer (1931).</p>
         *
         * <ul>
         * <li>outXyz[0] is X [0 ...95.047)</li>
         * <li>outXyz[1] is Y [0...100)</li>
         * <li>outXyz[2] is Z [0...108.883)</li>
         * </ul>
         *
         * @param r      red component value [0..255]
         * @param g      green component value [0..255]
         * @param b      blue component value [0..255]
         * @param outXyz 3-element array which holds the resulting XYZ components
         */
        public static void RGBToXYZ(@IntRange(from = 0x0, to = 0xFF) int r,
                @IntRange(from = 0x0, to = 0xFF) int g, @IntRange(from = 0x0, to = 0xFF) int b,
                @NonNull double[] outXyz) {
            if (outXyz.length != 3) {
                throw new IllegalArgumentException("outXyz must have a length of 3.");
            }

            double sr = r / 255.0;
            sr = sr < 0.04045 ? sr / 12.92 : Math.pow((sr + 0.055) / 1.055, 2.4);
            double sg = g / 255.0;
            sg = sg < 0.04045 ? sg / 12.92 : Math.pow((sg + 0.055) / 1.055, 2.4);
            double sb = b / 255.0;
            sb = sb < 0.04045 ? sb / 12.92 : Math.pow((sb + 0.055) / 1.055, 2.4);

            outXyz[0] = 100 * (sr * 0.4124 + sg * 0.3576 + sb * 0.1805);
            outXyz[1] = 100 * (sr * 0.2126 + sg * 0.7152 + sb * 0.0722);
            outXyz[2] = 100 * (sr * 0.0193 + sg * 0.1192 + sb * 0.9505);
        }

        /**
         * Converts a color from CIE XYZ to CIE Lab representation.
         *
         * <p>This method expects the XYZ representation to use the D65 illuminant and the CIE
         * 2° Standard Observer (1931).</p>
         *
         * <ul>
         * <li>outLab[0] is L [0 ...1)</li>
         * <li>outLab[1] is a [-128...127)</li>
         * <li>outLab[2] is b [-128...127)</li>
         * </ul>
         *
         * @param x      X component value [0...95.047)
         * @param y      Y component value [0...100)
         * @param z      Z component value [0...108.883)
         * @param outLab 3-element array which holds the resulting Lab components
         */
        public static void XYZToLAB(@FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_X) double x,
                @FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_Y) double y,
                @FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_Z) double z,
                @NonNull double[] outLab) {
            if (outLab.length != 3) {
                throw new IllegalArgumentException("outLab must have a length of 3.");
            }
            x = pivotXyzComponent(x / XYZ_WHITE_REFERENCE_X);
            y = pivotXyzComponent(y / XYZ_WHITE_REFERENCE_Y);
            z = pivotXyzComponent(z / XYZ_WHITE_REFERENCE_Z);
            outLab[0] = Math.max(0, 116 * y - 16);
            outLab[1] = 500 * (x - y);
            outLab[2] = 200 * (y - z);
        }

        /**
         * Converts a color from CIE Lab to CIE XYZ representation.
         *
         * <p>The resulting XYZ representation will use the D65 illuminant and the CIE
         * 2° Standard Observer (1931).</p>
         *
         * <ul>
         * <li>outXyz[0] is X [0 ...95.047)</li>
         * <li>outXyz[1] is Y [0...100)</li>
         * <li>outXyz[2] is Z [0...108.883)</li>
         * </ul>
         *
         * @param l      L component value [0...100)
         * @param a      A component value [-128...127)
         * @param b      B component value [-128...127)
         * @param outXyz 3-element array which holds the resulting XYZ components
         */
        public static void LABToXYZ(@FloatRange(from = 0f, to = 100) final double l,
                @FloatRange(from = -128, to = 127) final double a,
                @FloatRange(from = -128, to = 127) final double b,
                @NonNull double[] outXyz) {
            final double fy = (l + 16) / 116;
            final double fx = a / 500 + fy;
            final double fz = fy - b / 200;

            double tmp = Math.pow(fx, 3);
            final double xr = tmp > XYZ_EPSILON ? tmp : (116 * fx - 16) / XYZ_KAPPA;
            final double yr = l > XYZ_KAPPA * XYZ_EPSILON ? Math.pow(fy, 3) : l / XYZ_KAPPA;

            tmp = Math.pow(fz, 3);
            final double zr = tmp > XYZ_EPSILON ? tmp : (116 * fz - 16) / XYZ_KAPPA;

            outXyz[0] = xr * XYZ_WHITE_REFERENCE_X;
            outXyz[1] = yr * XYZ_WHITE_REFERENCE_Y;
            outXyz[2] = zr * XYZ_WHITE_REFERENCE_Z;
        }

        /**
         * Converts a color from CIE XYZ to its RGB representation.
         *
         * <p>This method expects the XYZ representation to use the D65 illuminant and the CIE
         * 2° Standard Observer (1931).</p>
         *
         * @param x X component value [0...95.047)
         * @param y Y component value [0...100)
         * @param z Z component value [0...108.883)
         * @return int containing the RGB representation
         */
        @ColorInt
        public static int XYZToColor(@FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_X) double x,
                @FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_Y) double y,
                @FloatRange(from = 0f, to = XYZ_WHITE_REFERENCE_Z) double z) {
            double r = (x * 3.2406 + y * -1.5372 + z * -0.4986) / 100;
            double g = (x * -0.9689 + y * 1.8758 + z * 0.0415) / 100;
            double b = (x * 0.0557 + y * -0.2040 + z * 1.0570) / 100;

            r = r > 0.0031308 ? 1.055 * Math.pow(r, 1 / 2.4) - 0.055 : 12.92 * r;
            g = g > 0.0031308 ? 1.055 * Math.pow(g, 1 / 2.4) - 0.055 : 12.92 * g;
            b = b > 0.0031308 ? 1.055 * Math.pow(b, 1 / 2.4) - 0.055 : 12.92 * b;

            return Color.rgb(
                    constrain((int) Math.round(r * 255), 0, 255),
                    constrain((int) Math.round(g * 255), 0, 255),
                    constrain((int) Math.round(b * 255), 0, 255));
        }

        /**
         * Converts a color from CIE Lab to its RGB representation.
         *
         * @param l L component value [0...100]
         * @param a A component value [-128...127]
         * @param b B component value [-128...127]
         * @return int containing the RGB representation
         */
        @ColorInt
        public static int LABToColor(@FloatRange(from = 0f, to = 100) final double l,
                @FloatRange(from = -128, to = 127) final double a,
                @FloatRange(from = -128, to = 127) final double b) {
            final double[] result = getTempDouble3Array();
            LABToXYZ(l, a, b, result);
            return XYZToColor(result[0], result[1], result[2]);
        }

        private static int constrain(int amount, int low, int high) {
            return amount < low ? low : (amount > high ? high : amount);
        }

        private static double pivotXyzComponent(double component) {
            return component > XYZ_EPSILON
                    ? Math.pow(component, 1 / 3.0)
                    : (XYZ_KAPPA * component + 16) / 116;
        }

        private static double[] getTempDouble3Array() {
            double[] result = TEMP_ARRAY.get();
            if (result == null) {
                result = new double[3];
                TEMP_ARRAY.set(result);
            }
            return result;
        }

    }
}
