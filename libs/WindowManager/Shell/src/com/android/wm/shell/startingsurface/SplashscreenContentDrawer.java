/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.startingsurface;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.util.Slog;
import android.view.Window;
import android.window.SplashScreenView;

import com.android.internal.R;
import com.android.internal.graphics.palette.Palette;
import com.android.internal.graphics.palette.Quantizer;
import com.android.internal.graphics.palette.VariationalKMeansQuantizer;

import java.util.List;

/**
 * Util class to create the view for a splash screen content.
 * @hide
 */
public class SplashscreenContentDrawer {
    private static final String TAG = StartingSurfaceDrawer.TAG;
    private static final boolean DEBUG = StartingSurfaceDrawer.DEBUG_SPLASH_SCREEN;

    // The acceptable area ratio of foreground_icon_area/background_icon_area, if there is an
    // icon which it's non-transparent foreground area is similar to it's background area, then
    // do not enlarge the foreground drawable.
    // For example, an icon with the foreground 108*108 opaque pixels and it's background
    // also 108*108 pixels, then do not enlarge this icon if only need to show foreground icon.
    private static final float ENLARGE_FOREGROUND_ICON_THRESHOLD = (72f * 72f) / (108f * 108f);
    private final Context mContext;
    private final int mMaxIconAnimationDuration;

    private int mIconSize;
    private int mBrandingImageWidth;
    private int mBrandingImageHeight;

    SplashscreenContentDrawer(Context context, int maxIconAnimationDuration) {
        mContext = context;
        mMaxIconAnimationDuration = maxIconAnimationDuration;
    }

    private void updateDensity() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_icon_size);
        mBrandingImageWidth = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_brand_image_width);
        mBrandingImageHeight = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_brand_image_height);
    }

    private int getSystemBGColor() {
        final Context systemContext = ActivityThread.currentApplication();
        if (systemContext == null) {
            Slog.e(TAG, "System context does not exist!");
            return Color.BLACK;
        }
        final Resources res = systemContext.getResources();
        return res.getColor(com.android.wm.shell.R.color.splash_window_background_default);
    }

    private Drawable createDefaultBackgroundDrawable() {
        return new ColorDrawable(getSystemBGColor());
    }

    SplashScreenView makeSplashScreenContentView(Window win, Context context, int iconRes,
            int splashscreenContentResId) {
        updateDensity();
        // splash screen content will be deprecated after S.
        final SplashScreenView ssc =
                makeSplashscreenContentDrawable(win, context, splashscreenContentResId);
        if (ssc != null) {
            return ssc;
        }

        final SplashScreenWindowAttrs attrs =
                SplashScreenWindowAttrs.createWindowAttrs(context);
        final StartingWindowViewBuilder builder = new StartingWindowViewBuilder();
        final Drawable themeBGDrawable;

        if (attrs.mWindowBgColor != 0) {
            themeBGDrawable = new ColorDrawable(attrs.mWindowBgColor);
        } else if (attrs.mWindowBgResId != 0) {
            themeBGDrawable = context.getDrawable(attrs.mWindowBgResId);
        } else {
            Slog.w(TAG, "Window background not exist!");
            themeBGDrawable = createDefaultBackgroundDrawable();
        }
        final int animationDuration;
        final Drawable iconDrawable;
        if (attrs.mReplaceIcon != null) {
            iconDrawable = attrs.mReplaceIcon;
            animationDuration = Math.max(0,
                    Math.min(attrs.mAnimationDuration, mMaxIconAnimationDuration));
        } else {
            iconDrawable = iconRes != 0 ? context.getDrawable(iconRes)
                    : context.getPackageManager().getDefaultActivityIcon();
            animationDuration = 0;
        }
        // TODO (b/173975965) Tracking the performance on improved splash screen.
        return builder
                .setWindow(win)
                .setContext(context)
                .setThemeDrawable(themeBGDrawable)
                .setIconDrawable(iconDrawable)
                .setIconAnimationDuration(animationDuration)
                .setBrandingDrawable(attrs.mBrandingImage).build();
    }

    private static class SplashScreenWindowAttrs {
        private int mWindowBgResId = 0;
        private int mWindowBgColor = Color.TRANSPARENT;
        private Drawable mReplaceIcon = null;
        private Drawable mBrandingImage = null;
        private int mAnimationDuration = 0;

        static SplashScreenWindowAttrs createWindowAttrs(Context context) {
            final SplashScreenWindowAttrs attrs = new SplashScreenWindowAttrs();
            final TypedArray typedArray = context.obtainStyledAttributes(
                    com.android.internal.R.styleable.Window);
            attrs.mWindowBgResId = typedArray.getResourceId(R.styleable.Window_windowBackground, 0);
            attrs.mWindowBgColor = typedArray.getColor(
                    R.styleable.Window_windowSplashScreenBackground, Color.TRANSPARENT);
            attrs.mReplaceIcon = typedArray.getDrawable(
                    R.styleable.Window_windowSplashScreenAnimatedIcon);
            attrs.mAnimationDuration = typedArray.getInt(
                    R.styleable.Window_windowSplashScreenAnimationDuration, 0);
            attrs.mBrandingImage = typedArray.getDrawable(
                    R.styleable.Window_windowSplashScreenBrandingImage);
            typedArray.recycle();
            if (DEBUG) {
                Slog.d(TAG, "window attributes color: "
                        + Integer.toHexString(attrs.mWindowBgColor)
                        + " icon " + attrs.mReplaceIcon + " duration " + attrs.mAnimationDuration
                        + " brandImage " + attrs.mBrandingImage);
            }
            return attrs;
        }
    }

    private class StartingWindowViewBuilder {
        private Drawable mThemeBGDrawable;
        private Drawable mIconDrawable;
        private Window mWindow;
        private int mIconAnimationDuration;
        private Context mContext;
        private Drawable mBrandingDrawable;

        // result
        private boolean mBuildComplete = false;
        private SplashScreenView mCachedResult;
        private int mThemeColor;
        private Drawable mFinalIconDrawable;
        private float mScale = 1f;

        StartingWindowViewBuilder setThemeDrawable(Drawable background) {
            mThemeBGDrawable = background;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setIconDrawable(Drawable iconDrawable) {
            mIconDrawable = iconDrawable;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setWindow(Window window) {
            mWindow = window;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setIconAnimationDuration(int iconAnimationDuration) {
            mIconAnimationDuration = iconAnimationDuration;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setBrandingDrawable(Drawable branding) {
            mBrandingDrawable = branding;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setContext(Context context) {
            mContext = context;
            mBuildComplete = false;
            return this;
        }

        SplashScreenView build() {
            if (mBuildComplete) {
                return mCachedResult;
            }
            if (mWindow == null || mContext == null) {
                Slog.e(TAG, "Unable to create StartingWindowView, lack of materials!");
                return null;
            }
            if (mThemeBGDrawable == null) {
                Slog.w(TAG, "Theme Background Drawable is null, forget to set Theme Drawable?");
                mThemeBGDrawable = createDefaultBackgroundDrawable();
            }
            processThemeColor();
            if (!processAdaptiveIcon() && mIconDrawable != null) {
                if (DEBUG) {
                    Slog.d(TAG, "The icon is not an AdaptiveIconDrawable");
                }
                mFinalIconDrawable = mIconDrawable;
            }
            final int iconSize = mFinalIconDrawable != null ? (int) (mIconSize * mScale) : 0;
            mCachedResult = fillViewWithIcon(mWindow, mContext, iconSize, mFinalIconDrawable);
            mBuildComplete = true;
            return mCachedResult;
        }

        private void processThemeColor() {
            final DrawableColorTester themeBGTester =
                    new DrawableColorTester(mThemeBGDrawable, true /* filterTransparent */);
            if (themeBGTester.nonTransparentRatio() == 0) {
                // the window background is transparent, unable to draw
                Slog.w(TAG, "Window background is transparent, fill background with black color");
                mThemeColor = getSystemBGColor();
            } else {
                mThemeColor = themeBGTester.getDominateColor();
            }
        }

        private boolean processAdaptiveIcon() {
            if (!(mIconDrawable instanceof AdaptiveIconDrawable)) {
                return false;
            }

            final AdaptiveIconDrawable adaptiveIconDrawable = (AdaptiveIconDrawable) mIconDrawable;
            final DrawableColorTester backIconTester =
                    new DrawableColorTester(adaptiveIconDrawable.getBackground());

            final Drawable iconForeground = adaptiveIconDrawable.getForeground();
            final DrawableColorTester foreIconTester =
                    new DrawableColorTester(iconForeground, true /* filterTransparent */);

            final boolean foreComplex = foreIconTester.isComplexColor();
            final int foreMainColor = foreIconTester.getDominateColor();

            if (DEBUG) {
                Slog.d(TAG, "foreground complex color? " + foreComplex + " main color: "
                        + Integer.toHexString(foreMainColor));
            }
            final boolean backComplex = backIconTester.isComplexColor();
            final int backMainColor = backIconTester.getDominateColor();
            if (DEBUG) {
                Slog.d(TAG, "background complex color? " + backComplex + " main color: "
                        + Integer.toHexString(backMainColor));
                Slog.d(TAG, "theme color? " + Integer.toHexString(mThemeColor));
            }

            // Only draw the foreground of AdaptiveIcon to the splash screen if below condition
            // meet:
            // A. The background of the adaptive icon is not complicated. If it is complicated,
            // it may contain some information, and
            // B. The background of the adaptive icon is similar to the theme color, or
            // C. The background of the adaptive icon is grayscale, and the foreground of the
            // adaptive icon forms a certain contrast with the theme color.
            if (!backComplex && (isRgbSimilarInHsv(mThemeColor, backMainColor)
                    || (backIconTester.isGrayscale()
                    && !isRgbSimilarInHsv(mThemeColor, foreMainColor)))) {
                if (DEBUG) {
                    Slog.d(TAG, "makeSplashScreenContentView: choose fg icon");
                }
                // Using AdaptiveIconDrawable here can help keep the shape consistent with the
                // current settings.
                mFinalIconDrawable = new AdaptiveIconDrawable(
                        new ColorDrawable(mThemeColor), iconForeground);
                // Reference AdaptiveIcon description, outer is 108 and inner is 72, so we
                // should enlarge the size 108/72 if we only draw adaptiveIcon's foreground.
                if (foreIconTester.nonTransparentRatio() < ENLARGE_FOREGROUND_ICON_THRESHOLD) {
                    mScale = 1.5f;
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "makeSplashScreenContentView: draw whole icon");
                }
                mFinalIconDrawable = adaptiveIconDrawable;
            }
            return true;
        }

        private SplashScreenView fillViewWithIcon(Window win, Context context,
                int iconSize, Drawable iconDrawable) {
            final SplashScreenView.Builder builder = new SplashScreenView.Builder(context);
            builder.setIconSize(iconSize).setBackgroundColor(mThemeColor);
            if (iconDrawable != null) {
                builder.setCenterViewDrawable(iconDrawable);
            }
            builder.setAnimationDuration(mIconAnimationDuration);
            if (mBrandingDrawable != null) {
                builder.setBrandingDrawable(mBrandingDrawable, mBrandingImageWidth,
                        mBrandingImageHeight);
            }
            final SplashScreenView splashScreenView = builder.build();
            if (DEBUG) {
                Slog.d(TAG, "fillViewWithIcon surfaceWindowView " + splashScreenView);
            }
            win.setContentView(splashScreenView);
            splashScreenView.cacheRootWindow(win);
            splashScreenView.makeSystemUIColorsTransparent();
            return splashScreenView;
        }
    }

    private static boolean isRgbSimilarInHsv(int a, int b) {
        if (a == b) {
            return true;
        }
        final float[] aHsv = new float[3];
        final float[] bHsv = new float[3];
        Color.colorToHSV(a, aHsv);
        Color.colorToHSV(b, bHsv);
        // Minimum degree of the hue between two colors, the result range is 0-180.
        int minAngle = (int) Math.abs(aHsv[0] - bHsv[0]);
        minAngle = (minAngle + 180) % 360 - 180;

        // Calculate the difference between two colors based on the HSV dimensions.
        final float normalizeH = minAngle / 180f;
        final double square =  Math.pow(normalizeH, 2)
                + Math.pow(aHsv[1] - bHsv[1], 2)
                + Math.pow(aHsv[2] - bHsv[2], 2);
        final double mean = square / 3;
        final double root = Math.sqrt(mean);
        if (DEBUG) {
            Slog.d(TAG, "hsvDiff " + minAngle + " a: " + Integer.toHexString(a)
                    + " b " + Integer.toHexString(b) + " ah " + aHsv[0] + " bh " + bHsv[0]
                    + " root " + root);
        }
        return root < 0.1;
    }

    private static SplashScreenView makeSplashscreenContentDrawable(Window win,
            Context ctx, int splashscreenContentResId) {
        // doesn't support windowSplashscreenContent after S
        // TODO add an allowlist to skip some packages if needed
        final int targetSdkVersion = ctx.getApplicationInfo().targetSdkVersion;
        if (DEBUG) {
            Slog.d(TAG, "target sdk for package: " + targetSdkVersion);
        }
        if (targetSdkVersion >= Build.VERSION_CODES.S) {
            return null;
        }
        if (splashscreenContentResId == 0) {
            return null;
        }
        final Drawable drawable = ctx.getDrawable(splashscreenContentResId);
        if (drawable == null) {
            return null;
        }
        SplashScreenView view = new SplashScreenView(ctx);
        view.setNotCopyable();
        view.setBackground(drawable);
        win.setContentView(view);
        return view;
    }

    private static class DrawableColorTester {
        private final ColorTester mColorChecker;

        DrawableColorTester(Drawable drawable) {
            this(drawable, false /* filterTransparent */);
        }

        DrawableColorTester(Drawable drawable, boolean filterTransparent) {
            // Some applications use LayerDrawable for their windowBackground. To ensure that we
            // only get the real background, so that the color is not affected by the alpha of the
            // upper layer, try to get the lower layer here. This can also speed up the calculation.
            if (drawable instanceof LayerDrawable) {
                LayerDrawable layerDrawable = (LayerDrawable) drawable;
                if (layerDrawable.getNumberOfLayers() > 0) {
                    if (DEBUG) {
                        Slog.d(TAG, "replace drawable with bottom layer drawable");
                    }
                    drawable = layerDrawable.getDrawable(0);
                }
            }
            mColorChecker = drawable instanceof ColorDrawable
                    ? new SingleColorTester((ColorDrawable) drawable)
                    : new ComplexDrawableTester(drawable, filterTransparent);
        }

        public float nonTransparentRatio() {
            return mColorChecker.nonTransparentRatio();
        }

        public boolean isComplexColor() {
            return mColorChecker.isComplexColor();
        }

        public int getDominateColor() {
            return mColorChecker.getDominantColor();
        }

        public boolean isGrayscale() {
            return mColorChecker.isGrayscale();
        }

        /**
         * A help class to check the color information from a Drawable.
         */
        private interface ColorTester {
            float nonTransparentRatio();
            boolean isComplexColor();
            int getDominantColor();
            boolean isGrayscale();
        }

        private static boolean isGrayscaleColor(int color) {
            final int red = Color.red(color);
            final int green = Color.green(color);
            final int blue = Color.blue(color);
            return red == green && green == blue;
        }

        /**
         * For ColorDrawable only.
         * There will be only one color so don't spend too much resource for it.
         */
        private static class SingleColorTester implements ColorTester {
            private final ColorDrawable mColorDrawable;

            SingleColorTester(@NonNull ColorDrawable drawable) {
                mColorDrawable = drawable;
            }

            @Override
            public float nonTransparentRatio() {
                final int alpha = mColorDrawable.getAlpha();
                return (float) (alpha / 255);
            }

            @Override
            public boolean isComplexColor() {
                return false;
            }

            @Override
            public int getDominantColor() {
                return mColorDrawable.getColor();
            }

            @Override
            public boolean isGrayscale() {
                return isGrayscaleColor(mColorDrawable.getColor());
            }
        }

        /**
         * For any other Drawable except ColorDrawable.
         * This will use the Palette API to check the color information and use a quantizer to
         * filter out transparent colors when needed.
         */
        private static class ComplexDrawableTester implements ColorTester {
            private static final int MAX_BITMAP_SIZE = 40;
            private final Palette mPalette;
            private final boolean mFilterTransparent;
            private static final TransparentFilterQuantizer TRANSPARENT_FILTER_QUANTIZER =
                    new TransparentFilterQuantizer();

            ComplexDrawableTester(Drawable drawable, boolean filterTransparent) {
                final Rect initialBounds = drawable.copyBounds();
                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();

                // Some drawables do not have intrinsic dimensions
                if (width <= 0 || height <= 0) {
                    width = MAX_BITMAP_SIZE;
                    height = MAX_BITMAP_SIZE;
                } else {
                    width = Math.min(width, MAX_BITMAP_SIZE);
                    height = Math.min(height, MAX_BITMAP_SIZE);
                }

                final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final Canvas bmpCanvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(bmpCanvas);
                // restore to original bounds
                drawable.setBounds(initialBounds);

                final Palette.Builder builder = new Palette.Builder(bitmap)
                        .maximumColorCount(5).clearFilters();
                // The Palette API will ignore Alpha, so it cannot handle transparent pixels, but
                // sometimes we will need this information to know if this Drawable object is
                // transparent.
                mFilterTransparent = filterTransparent;
                if (mFilterTransparent) {
                    builder.setQuantizer(TRANSPARENT_FILTER_QUANTIZER);
                }
                mPalette = builder.generate();
                bitmap.recycle();
            }

            @Override
            public float nonTransparentRatio() {
                return mFilterTransparent ? TRANSPARENT_FILTER_QUANTIZER.mNonTransparentRatio : 1;
            }

            @Override
            public boolean isComplexColor() {
                return mPalette.getSwatches().size() > 1;
            }

            @Override
            public int getDominantColor() {
                final Palette.Swatch mainSwatch = mPalette.getDominantSwatch();
                if (mainSwatch != null) {
                    return mainSwatch.getRgb();
                }
                return Color.BLACK;
            }

            @Override
            public boolean isGrayscale() {
                final List<Palette.Swatch> swatches = mPalette.getSwatches();
                if (swatches != null) {
                    for (int i = swatches.size() - 1; i >= 0; i--) {
                        Palette.Swatch swatch = swatches.get(i);
                        if (!isGrayscaleColor(swatch.getRgb())) {
                            return false;
                        }
                    }
                }
                return true;
            }

            private static class TransparentFilterQuantizer implements Quantizer {
                private static final int NON_TRANSPARENT = 0xFF000000;
                private final Quantizer mInnerQuantizer = new VariationalKMeansQuantizer();
                private float mNonTransparentRatio;
                @Override
                public void quantize(final int[] pixels, final int maxColors,
                        final Palette.Filter[] filters) {
                    mNonTransparentRatio = 0;
                    int realSize = 0;
                    for (int i = pixels.length - 1; i > 0; i--) {
                        if ((pixels[i] & NON_TRANSPARENT) != 0) {
                            realSize++;
                        }
                    }
                    if (realSize == 0) {
                        if (DEBUG) {
                            Slog.d(TAG, "quantize: this is pure transparent image");
                        }
                        mInnerQuantizer.quantize(pixels, maxColors, filters);
                        return;
                    }
                    mNonTransparentRatio = (float) realSize / pixels.length;
                    final int[] samplePixels = new int[realSize];
                    int rowIndex = 0;
                    for (int i = pixels.length - 1; i > 0; i--) {
                        if ((pixels[i] & NON_TRANSPARENT) == NON_TRANSPARENT) {
                            samplePixels[rowIndex] = pixels[i];
                            rowIndex++;
                        }
                    }
                    mInnerQuantizer.quantize(samplePixels, maxColors, filters);
                }

                @Override
                public List<Palette.Swatch> getQuantizedColors() {
                    return mInnerQuantizer.getQuantizedColors();
                }
            }
        }
    }
}
