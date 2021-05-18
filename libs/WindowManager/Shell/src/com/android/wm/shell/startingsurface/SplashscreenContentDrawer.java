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

import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ActivityInfo;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.View;
import android.window.SplashScreenView;

import com.android.internal.R;
import com.android.internal.graphics.palette.Palette;
import com.android.internal.graphics.palette.Quantizer;
import com.android.internal.graphics.palette.VariationalKMeansQuantizer;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.common.TransactionPool;

import java.util.List;
import java.util.function.Consumer;

/**
 * Util class to create the view for a splash screen content.
 * Everything execute in this class should be post to mSplashscreenWorkerHandler.
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
    private static final float NO_BACKGROUND_SCALE = 192f / 160;
    private final Context mContext;
    private final IconProvider mIconProvider;

    private int mIconSize;
    private int mDefaultIconSize;
    private int mBrandingImageWidth;
    private int mBrandingImageHeight;
    private int mMainWindowShiftLength;
    private final TransactionPool mTransactionPool;
    private final SplashScreenWindowAttrs mTmpAttrs = new SplashScreenWindowAttrs();
    private final Handler mSplashscreenWorkerHandler;

    SplashscreenContentDrawer(Context context, TransactionPool pool) {
        mContext = context;
        mIconProvider = new IconProvider(context);
        mTransactionPool = pool;

        // Initialize Splashscreen worker thread
        // TODO(b/185288910) move it into WMShellConcurrencyModule and provide an executor to make
        //  it easier to test stuff that happens on that thread later.
        final HandlerThread shellSplashscreenWorkerThread =
                new HandlerThread("wmshell.splashworker", THREAD_PRIORITY_TOP_APP_BOOST);
        shellSplashscreenWorkerThread.start();
        mSplashscreenWorkerHandler = shellSplashscreenWorkerThread.getThreadHandler();
    }

    /**
     * Create a SplashScreenView object.
     *
     * In order to speed up the splash screen view to show on first frame, preparing the
     * view on background thread so the view and the drawable can be create and pre-draw in
     * parallel.
     *
     * @param emptyView Create a splash screen view without icon on it.
     * @param consumer Receiving the SplashScreenView object, which will also be executed
     *                 on splash screen thread. Note that the view can be null if failed.
     */
    void createContentView(Context context, boolean emptyView, ActivityInfo info, int taskId,
            Consumer<SplashScreenView> consumer) {
        mSplashscreenWorkerHandler.post(() -> {
            SplashScreenView contentView;
            try {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "makeSplashScreenContentView");
                contentView = makeSplashScreenContentView(context, info, emptyView);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            } catch (RuntimeException e) {
                Slog.w(TAG, "failed creating starting window content at taskId: "
                        + taskId, e);
                contentView = null;
            }
            consumer.accept(contentView);
        });
    }

    private void updateDensity() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.starting_surface_icon_size);
        mDefaultIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.starting_surface_default_icon_size);
        mBrandingImageWidth = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_brand_image_width);
        mBrandingImageHeight = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_brand_image_height);
        mMainWindowShiftLength = mContext.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.starting_surface_exit_animation_window_shift_length);
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

    private @ColorInt int peekWindowBGColor(Context context) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "peekWindowBGColor");
        final Drawable themeBGDrawable;
        if (mTmpAttrs.mWindowBgColor != 0) {
            themeBGDrawable = new ColorDrawable(mTmpAttrs.mWindowBgColor);
        } else if (mTmpAttrs.mWindowBgResId != 0) {
            themeBGDrawable = context.getDrawable(mTmpAttrs.mWindowBgResId);
        } else {
            themeBGDrawable = createDefaultBackgroundDrawable();
            Slog.w(TAG, "Window background does not exist, using " + themeBGDrawable);
        }
        final int estimatedWindowBGColor = estimateWindowBGColor(themeBGDrawable);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        return estimatedWindowBGColor;
    }

    private int estimateWindowBGColor(Drawable themeBGDrawable) {
        final DrawableColorTester themeBGTester =
                new DrawableColorTester(themeBGDrawable, true /* filterTransparent */);
        if (themeBGTester.nonTransparentRatio() == 0) {
            // the window background is transparent, unable to draw
            Slog.w(TAG, "Window background is transparent, fill background with black color");
            return getSystemBGColor();
        } else {
            return themeBGTester.getDominateColor();
        }
    }

    private SplashScreenView makeSplashScreenContentView(Context context, ActivityInfo ai,
            boolean emptyView) {
        updateDensity();

        getWindowAttrs(context, mTmpAttrs);
        final StartingWindowViewBuilder builder = new StartingWindowViewBuilder();
        final int themeBGColor = peekWindowBGColor(context);
        // TODO (b/173975965) Tracking the performance on improved splash screen.
        return builder
                .setContext(context)
                .setWindowBGColor(themeBGColor)
                .makeEmptyView(emptyView)
                .setActivityInfo(ai)
                .build();
    }

    private static void getWindowAttrs(Context context, SplashScreenWindowAttrs attrs) {
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
        attrs.mIconBgColor = typedArray.getColor(
                R.styleable.Window_windowSplashScreenIconBackgroundColor, Color.TRANSPARENT);
        typedArray.recycle();
        if (DEBUG) {
            Slog.d(TAG, "window attributes color: "
                    + Integer.toHexString(attrs.mWindowBgColor)
                    + " icon " + attrs.mReplaceIcon + " duration " + attrs.mAnimationDuration
                    + " brandImage " + attrs.mBrandingImage);
        }
    }

    private static class SplashScreenWindowAttrs {
        private int mWindowBgResId = 0;
        private int mWindowBgColor = Color.TRANSPARENT;
        private Drawable mReplaceIcon = null;
        private Drawable mBrandingImage = null;
        private int mIconBgColor = Color.TRANSPARENT;
        private int mAnimationDuration = 0;
    }

    private class StartingWindowViewBuilder {
        private ActivityInfo mActivityInfo;
        private Context mContext;
        private boolean mEmptyView;

        // result
        private boolean mBuildComplete = false;
        private SplashScreenView mCachedResult;
        private int mThemeColor;
        private Drawable mFinalIconDrawable;
        private int mFinalIconSize = mIconSize;

        StartingWindowViewBuilder setWindowBGColor(@ColorInt int background) {
            mThemeColor = background;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder makeEmptyView(boolean empty) {
            mEmptyView = empty;
            mBuildComplete = false;
            return this;
        }

        StartingWindowViewBuilder setActivityInfo(ActivityInfo ai) {
            mActivityInfo = ai;
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
            if (mContext == null || mActivityInfo == null) {
                Slog.e(TAG, "Unable to create StartingWindowView, lack of materials!");
                return null;
            }

            Drawable iconDrawable;
            final int animationDuration;
            if (mEmptyView) {
                // empty splash screen case
                animationDuration = 0;
                mFinalIconSize = 0;
            } else if (mTmpAttrs.mReplaceIcon != null) {
                // replaced icon, don't process
                iconDrawable = mTmpAttrs.mReplaceIcon;
                animationDuration = mTmpAttrs.mAnimationDuration;
                createIconDrawable(iconDrawable);
            } else {
                final float iconScale = (float) mIconSize / (float) mDefaultIconSize;
                final int densityDpi = mContext.getResources().getDisplayMetrics().densityDpi;
                final int scaledIconDpi =
                        (int) (0.5f + iconScale * densityDpi * NO_BACKGROUND_SCALE);
                iconDrawable = mIconProvider.getIcon(mActivityInfo, scaledIconDpi);
                if (iconDrawable == null) {
                    iconDrawable = mContext.getPackageManager().getDefaultActivityIcon();
                }
                if (!processAdaptiveIcon(iconDrawable)) {
                    if (DEBUG) {
                        Slog.d(TAG, "The icon is not an AdaptiveIconDrawable");
                    }
                    // TODO process legacy icon(bitmap)
                    createIconDrawable(iconDrawable);
                }
                animationDuration = 0;
            }

            mCachedResult = fillViewWithIcon(mFinalIconSize, mFinalIconDrawable, animationDuration);
            mBuildComplete = true;
            return mCachedResult;
        }

        private void createIconDrawable(Drawable iconDrawable) {
            mFinalIconDrawable = SplashscreenIconDrawableFactory.makeIconDrawable(
                    mTmpAttrs.mIconBgColor != Color.TRANSPARENT
                            ? mTmpAttrs.mIconBgColor : mThemeColor,
                    iconDrawable, mDefaultIconSize, mFinalIconSize, mSplashscreenWorkerHandler);
        }

        private boolean processAdaptiveIcon(Drawable iconDrawable) {
            if (!(iconDrawable instanceof AdaptiveIconDrawable)) {
                return false;
            }

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "processAdaptiveIcon");
            final AdaptiveIconDrawable adaptiveIconDrawable =
                    (AdaptiveIconDrawable) iconDrawable;
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
            // D. Didn't specify icon background color.
            if (!backComplex && mTmpAttrs.mIconBgColor == Color.TRANSPARENT
                    && (isRgbSimilarInHsv(mThemeColor, backMainColor)
                    || (backIconTester.isGrayscale()
                    && !isRgbSimilarInHsv(mThemeColor, foreMainColor)))) {
                if (DEBUG) {
                    Slog.d(TAG, "makeSplashScreenContentView: choose fg icon");
                }
                // Reference AdaptiveIcon description, outer is 108 and inner is 72, so we
                // scale by 192/160 if we only draw adaptiveIcon's foreground.
                final float noBgScale =
                        foreIconTester.nonTransparentRatio() < ENLARGE_FOREGROUND_ICON_THRESHOLD
                                ? NO_BACKGROUND_SCALE : 1f;
                // Using AdaptiveIconDrawable here can help keep the shape consistent with the
                // current settings.
                mFinalIconSize = (int) (0.5f + mIconSize * noBgScale);
                createIconDrawable(iconForeground);
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "makeSplashScreenContentView: draw whole icon");
                }
                createIconDrawable(iconDrawable);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return true;
        }

        private SplashScreenView fillViewWithIcon(int iconSize, Drawable iconDrawable,
                int animationDuration) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "fillViewWithIcon");
            final SplashScreenView.Builder builder = new SplashScreenView.Builder(mContext);
            builder.setBackgroundColor(mThemeColor);
            if (iconDrawable != null) {
                builder.setIconSize(iconSize)
                        .setIconBackground(mTmpAttrs.mIconBgColor)
                        .setCenterViewDrawable(iconDrawable)
                        .setAnimationDurationMillis(animationDuration);
            }
            if (mTmpAttrs.mBrandingImage != null) {
                builder.setBrandingDrawable(mTmpAttrs.mBrandingImage, mBrandingImageWidth,
                        mBrandingImageHeight);
            }
            final SplashScreenView splashScreenView = builder.build();
            if (DEBUG) {
                Slog.d(TAG, "fillViewWithIcon surfaceWindowView " + splashScreenView);
            }
            if (mEmptyView) {
                splashScreenView.setNotCopyable();
                splashScreenView.setRevealAnimationSupported(false);
            }
            splashScreenView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    SplashScreenView.applySystemBarsContrastColor(v.getWindowInsetsController(),
                            splashScreenView.getInitBackgroundColor());
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return splashScreenView;
        }
    }

    private static boolean isRgbSimilarInHsv(int a, int b) {
        if (a == b) {
            return true;
        }
        final float lumA = Color.luminance(a);
        final float lumB = Color.luminance(b);
        final float contrastRatio = lumA > lumB
                ? (lumA + 0.05f) / (lumB + 0.05f) : (lumB + 0.05f) / (lumA + 0.05f);
        if (DEBUG) {
            Slog.d(TAG, "isRgbSimilarInHsv a: " + Integer.toHexString(a)
                    + " b " + Integer.toHexString(b) + " contrast ratio: " + contrastRatio);
        }
        if (contrastRatio < 2) {
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
        final double squareH = Math.pow(normalizeH, 2);
        final double squareS = Math.pow(aHsv[1] - bHsv[1], 2);
        final double squareV = Math.pow(aHsv[2] - bHsv[2], 2);
        final double square = squareH + squareS + squareV;
        final double mean = square / 3;
        final double root = Math.sqrt(mean);
        if (DEBUG) {
            Slog.d(TAG, "hsvDiff " + minAngle
                    + " ah " + aHsv[0] + " bh " + bHsv[0]
                    + " as " + aHsv[1] + " bs " + bHsv[1]
                    + " av " + aHsv[2] + " bv " + bHsv[2]
                    + " sqH " + squareH + " sqS " + squareS + " sqV " + squareV
                    + " root " + root);
        }
        return root < 0.1;
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
         * For ColorDrawable only. There will be only one color so don't spend too much resource for
         * it.
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
         * For any other Drawable except ColorDrawable. This will use the Palette API to check the
         * color information and use a quantizer to filter out transparent colors when needed.
         */
        private static class ComplexDrawableTester implements ColorTester {
            private static final int MAX_BITMAP_SIZE = 40;
            private final Palette mPalette;
            private final boolean mFilterTransparent;
            private static final TransparentFilterQuantizer TRANSPARENT_FILTER_QUANTIZER =
                    new TransparentFilterQuantizer();

            ComplexDrawableTester(Drawable drawable, boolean filterTransparent) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "ComplexDrawableTester");
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

                final Palette.Builder builder;
                // The Palette API will ignore Alpha, so it cannot handle transparent pixels, but
                // sometimes we will need this information to know if this Drawable object is
                // transparent.
                mFilterTransparent = filterTransparent;
                if (mFilterTransparent) {
                    builder = new Palette.Builder(bitmap, TRANSPARENT_FILTER_QUANTIZER)
                            .maximumColorCount(5);
                } else {
                    builder = new Palette.Builder(bitmap, null)
                            .maximumColorCount(5);
                }
                mPalette = builder.generate();
                bitmap.recycle();
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
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
                    return mainSwatch.getInt();
                }
                return Color.BLACK;
            }

            @Override
            public boolean isGrayscale() {
                final List<Palette.Swatch> swatches = mPalette.getSwatches();
                if (swatches != null) {
                    for (int i = swatches.size() - 1; i >= 0; i--) {
                        Palette.Swatch swatch = swatches.get(i);
                        if (!isGrayscaleColor(swatch.getInt())) {
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
                public void quantize(final int[] pixels, final int maxColors) {
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
                        mInnerQuantizer.quantize(pixels, maxColors);
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
                    mInnerQuantizer.quantize(samplePixels, maxColors);
                }

                @Override
                public List<Palette.Swatch> getQuantizedColors() {
                    return mInnerQuantizer.getQuantizedColors();
                }
            }
        }
    }

    /**
     * Create and play the default exit animation for splash screen view.
     */
    void applyExitAnimation(SplashScreenView view, SurfaceControl leash,
            Rect frame, Runnable finishCallback) {
        final SplashScreenExitAnimation animation = new SplashScreenExitAnimation(mContext, view,
                leash, frame, mMainWindowShiftLength, mTransactionPool, finishCallback);
        animation.startAnimations();
    }
}
