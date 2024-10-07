/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

/**
 * CompatibilityInfo class keeps the information about the screen compatibility mode that the
 * application is running under.
 * 
 *  {@hide} 
 */
@RavenwoodKeepWholeClass
public class CompatibilityInfo implements Parcelable {
    /** default compatibility info object for compatible applications */
    @UnsupportedAppUsage
    public static final CompatibilityInfo DEFAULT_COMPATIBILITY_INFO = new CompatibilityInfo() {
    };

    /**
     * This is the number of pixels we would like to have along the
     * short axis of an app that needs to run on a normal size screen.
     */
    public static final int DEFAULT_NORMAL_SHORT_DIMENSION = 320;

    /**
     * This is the maximum aspect ratio we will allow while keeping
     * applications in a compatible screen size.
     */
    public static final float MAXIMUM_ASPECT_RATIO = (854f/480f);

    /**
     *  A compatibility flags
     */
    private final int mCompatibilityFlags;
    
    /**
     * A flag mask to tell if the application needs scaling (when mApplicationScale != 1.0f)
     * {@see compatibilityFlag}
     */
    private static final int SCALING_REQUIRED = 1; 

    /**
     * Application must always run in compatibility mode?
     */
    private static final int ALWAYS_NEEDS_COMPAT = 2;

    /**
     * Application never should run in compatibility mode?
     */
    private static final int NEVER_NEEDS_COMPAT = 4;

    /**
     * Set if the application needs to run in screen size compatibility mode.
     */
    private static final int NEEDS_SCREEN_COMPAT = 8;

    /**
     * Set if the application needs to run in with compat resources.
     */
    private static final int NEEDS_COMPAT_RES = 16;

    /**
     * Set if the application needs to be forcibly downscaled
     */
    private static final int HAS_OVERRIDE_SCALING = 32;

    /**
     * The effective screen density we have selected for this application.
     */
    public final int applicationDensity;

    /**
     * Application's scale.
     */
    @UnsupportedAppUsage
    public final float applicationScale;

    /**
     * Application's inverted scale.
     */
    public final float applicationInvertedScale;

    /**
     * Application's density scale.
     *
     * <p>In most cases this is equal to {@link #applicationScale}, but in some cases e.g.
     * Automotive the requirement is to just scale the density and keep the resolution the same.
     * This is used for artificially making apps look zoomed in to compensate for the user distance
     * from the screen.
     */
    public final float applicationDensityScale;

    /**
     * Application's density inverted scale.
     */
    public final float applicationDensityInvertedScale;

    /** The process level override inverted scale. See {@link #HAS_OVERRIDE_SCALING}. */
    private static float sOverrideInvertedScale = 1f;

    /** The process level override inverted density scale. See {@link #HAS_OVERRIDE_SCALING}. */
    private static float sOverrideDensityInvertScale = 1f;

    @UnsupportedAppUsage
    @Deprecated
    public CompatibilityInfo(ApplicationInfo appInfo, int screenLayout, int sw,
            boolean forceCompat) {
        this(appInfo, screenLayout, sw, forceCompat, 1f);
    }

    public CompatibilityInfo(ApplicationInfo appInfo, int screenLayout, int sw,
            boolean forceCompat, float scaleFactor) {
        this(appInfo, screenLayout, sw, forceCompat, scaleFactor, scaleFactor);
    }

    public CompatibilityInfo(ApplicationInfo appInfo, int screenLayout, int sw,
            boolean forceCompat, float scaleFactor, float densityScaleFactor) {
        int compatFlags = 0;

        if (appInfo.targetSdkVersion < VERSION_CODES.O) {
            compatFlags |= NEEDS_COMPAT_RES;
        }
        if (scaleFactor != 1f || densityScaleFactor != 1f) {
            applicationScale = scaleFactor;
            applicationInvertedScale = 1f / scaleFactor;
            applicationDensityScale = densityScaleFactor;
            applicationDensityInvertedScale = 1f / densityScaleFactor;
            applicationDensity = (int) ((DisplayMetrics.DENSITY_DEVICE_STABLE
                    * applicationDensityInvertedScale) + .5f);
            mCompatibilityFlags = NEVER_NEEDS_COMPAT | HAS_OVERRIDE_SCALING;
            // Override scale has the highest priority. So ignore other compatibility attributes.
            return;
        }
        if (appInfo.requiresSmallestWidthDp != 0 || appInfo.compatibleWidthLimitDp != 0
                || appInfo.largestWidthLimitDp != 0) {
            // New style screen requirements spec.
            int required = appInfo.requiresSmallestWidthDp != 0
                    ? appInfo.requiresSmallestWidthDp
                    : appInfo.compatibleWidthLimitDp;
            if (required == 0) {
                required = appInfo.largestWidthLimitDp;
            }
            int compat = appInfo.compatibleWidthLimitDp != 0
                    ? appInfo.compatibleWidthLimitDp : required;
            if (compat < required)  {
                compat = required;
            }
            int largest = appInfo.largestWidthLimitDp;

            if (required > DEFAULT_NORMAL_SHORT_DIMENSION) {
                // For now -- if they require a size larger than the only
                // size we can do in compatibility mode, then don't ever
                // allow the app to go in to compat mode.  Trying to run
                // it at a smaller size it can handle will make it far more
                // broken than running at a larger size than it wants or
                // thinks it can handle.
                compatFlags |= NEVER_NEEDS_COMPAT;
            } else if (largest != 0 && sw > largest) {
                // If the screen size is larger than the largest size the
                // app thinks it can work with, then always force it in to
                // compatibility mode.
                compatFlags |= NEEDS_SCREEN_COMPAT | ALWAYS_NEEDS_COMPAT;
            } else if (compat >= sw) {
                // The screen size is something the app says it was designed
                // for, so never do compatibility mode.
                compatFlags |= NEVER_NEEDS_COMPAT;
            } else if (forceCompat) {
                // The app may work better with or without compatibility mode.
                // Let the user decide.
                compatFlags |= NEEDS_SCREEN_COMPAT;
            }

            // Modern apps always support densities.
            applicationDensity = DisplayMetrics.DENSITY_DEVICE;
            applicationScale = 1.0f;
            applicationInvertedScale = 1.0f;
            applicationDensityScale = 1.0f;
            applicationDensityInvertedScale = 1.0f;
        } else {
            /**
             * Has the application said that its UI is expandable?  Based on the
             * <supports-screen> android:expandible in the manifest.
             */
            final int EXPANDABLE = 2;

            /**
             * Has the application said that its UI supports large screens?  Based on the
             * <supports-screen> android:largeScreens in the manifest.
             */
            final int LARGE_SCREENS = 8;

            /**
             * Has the application said that its UI supports xlarge screens?  Based on the
             * <supports-screen> android:xlargeScreens in the manifest.
             */
            final int XLARGE_SCREENS = 32;

            int sizeInfo = 0;

            // We can't rely on the application always setting
            // FLAG_RESIZEABLE_FOR_SCREENS so will compute it based on various input.
            boolean anyResizeable = false;

            if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
                sizeInfo |= LARGE_SCREENS;
                anyResizeable = true;
                if (!forceCompat) {
                    // If we aren't forcing the app into compatibility mode, then
                    // assume if it supports large screens that we should allow it
                    // to use the full space of an xlarge screen as well.
                    sizeInfo |= XLARGE_SCREENS | EXPANDABLE;
                }
            }
            if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS) != 0) {
                anyResizeable = true;
                if (!forceCompat) {
                    sizeInfo |= XLARGE_SCREENS | EXPANDABLE;
                }
            }
            if ((appInfo.flags & ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS) != 0) {
                anyResizeable = true;
                sizeInfo |= EXPANDABLE;
            }

            if (forceCompat) {
                // If we are forcing compatibility mode, then ignore an app that
                // just says it is resizable for screens.  We'll only have it fill
                // the screen if it explicitly says it supports the screen size we
                // are running in.
                sizeInfo &= ~EXPANDABLE;
            }

            compatFlags |= NEEDS_SCREEN_COMPAT;
            switch (screenLayout&Configuration.SCREENLAYOUT_SIZE_MASK) {
                case Configuration.SCREENLAYOUT_SIZE_XLARGE:
                    if ((sizeInfo&XLARGE_SCREENS) != 0) {
                        compatFlags &= ~NEEDS_SCREEN_COMPAT;
                    }
                    if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS) != 0) {
                        compatFlags |= NEVER_NEEDS_COMPAT;
                    }
                    break;
                case Configuration.SCREENLAYOUT_SIZE_LARGE:
                    if ((sizeInfo&LARGE_SCREENS) != 0) {
                        compatFlags &= ~NEEDS_SCREEN_COMPAT;
                    }
                    if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
                        compatFlags |= NEVER_NEEDS_COMPAT;
                    }
                    break;
            }

            if ((screenLayout&Configuration.SCREENLAYOUT_COMPAT_NEEDED) != 0) {
                if ((sizeInfo&EXPANDABLE) != 0) {
                    compatFlags &= ~NEEDS_SCREEN_COMPAT;
                } else if (!anyResizeable) {
                    compatFlags |= ALWAYS_NEEDS_COMPAT;
                }
            } else {
                compatFlags &= ~NEEDS_SCREEN_COMPAT;
                compatFlags |= NEVER_NEEDS_COMPAT;
            }

            if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES) != 0) {
                applicationDensity = DisplayMetrics.DENSITY_DEVICE;
                applicationScale = 1.0f;
                applicationInvertedScale = 1.0f;
                applicationDensityScale = 1.0f;
                applicationDensityInvertedScale = 1.0f;
            } else {
                applicationDensity = DisplayMetrics.DENSITY_DEFAULT;
                applicationScale = DisplayMetrics.DENSITY_DEVICE
                        / (float) DisplayMetrics.DENSITY_DEFAULT;
                applicationInvertedScale = 1.0f / applicationScale;
                applicationDensityScale = DisplayMetrics.DENSITY_DEVICE
                        / (float) DisplayMetrics.DENSITY_DEFAULT;
                applicationDensityInvertedScale = 1f / applicationDensityScale;
                compatFlags |= SCALING_REQUIRED;
            }
        }

        mCompatibilityFlags = compatFlags;
    }

    private CompatibilityInfo(int compFlags,
            int dens, float scale, float invertedScale) {
        mCompatibilityFlags = compFlags;
        applicationDensity = dens;
        applicationScale = scale;
        applicationInvertedScale = invertedScale;
        applicationDensityScale = (float) DisplayMetrics.DENSITY_DEVICE_STABLE / dens;
        applicationDensityInvertedScale = 1f / applicationDensityScale;
    }

    @UnsupportedAppUsage
    private CompatibilityInfo() {
        this(NEVER_NEEDS_COMPAT, DisplayMetrics.DENSITY_DEVICE,
                1.0f,
                1.0f);
    }

    /**
     * @return true if the scaling is required
     */
    @UnsupportedAppUsage
    public boolean isScalingRequired() {
        return (mCompatibilityFlags & SCALING_REQUIRED) != 0;
    }

    /** Returns {@code true} if {@link #sOverrideInvertedScale} should be set. */
    public boolean hasOverrideScaling() {
        return (mCompatibilityFlags & HAS_OVERRIDE_SCALING) != 0;
    }

    @UnsupportedAppUsage
    public boolean supportsScreen() {
        return (mCompatibilityFlags&NEEDS_SCREEN_COMPAT) == 0;
    }
    
    public boolean neverSupportsScreen() {
        return (mCompatibilityFlags&ALWAYS_NEEDS_COMPAT) != 0;
    }

    public boolean alwaysSupportsScreen() {
        return (mCompatibilityFlags&NEVER_NEEDS_COMPAT) != 0;
    }

    public boolean needsCompatResources() {
        return (mCompatibilityFlags&NEEDS_COMPAT_RES) != 0;
    }

    /**
     * Returns the translator which translates the coordinates in compatibility mode.
     * @param params the window's parameter
     */
    @UnsupportedAppUsage
    public Translator getTranslator() {
        return (mCompatibilityFlags & SCALING_REQUIRED) != 0 ? new Translator() : null;
    }

    /**
     * A helper object to translate the screen and window coordinates back and forth.
     * @hide
     */
    public class Translator {
        @UnsupportedAppUsage
        final public float applicationScale;
        @UnsupportedAppUsage
        final public float applicationInvertedScale;
        
        private Rect mContentInsetsBuffer = null;
        private Rect mVisibleInsetsBuffer = null;
        private Region mTouchableAreaBuffer = null;
        
        Translator(float applicationScale, float applicationInvertedScale) {
            this.applicationScale = applicationScale;
            this.applicationInvertedScale = applicationInvertedScale;
        }

        Translator() {
            this(CompatibilityInfo.this.applicationScale,
                    CompatibilityInfo.this.applicationInvertedScale);
        }

        /**
         * Translate the region in window to screen.
         */
        @UnsupportedAppUsage
        public void translateRegionInWindowToScreen(Region transparentRegion) {
            transparentRegion.scale(applicationScale);
        }

        /**
         * Apply translation to the canvas that is necessary to draw the content.
         */
        @UnsupportedAppUsage
        public void translateCanvas(Canvas canvas) {
            if (applicationScale == 1.5f) {
                /*  When we scale for compatibility, we can put our stretched
                    bitmaps and ninepatches on exacty 1/2 pixel boundaries,
                    which can give us inconsistent drawing due to imperfect
                    float precision in the graphics engine's inverse matrix.
                 
                    As a work-around, we translate by a tiny amount to avoid
                    landing on exact pixel centers and boundaries, giving us
                    the slop we need to draw consistently.
                 
                    This constant is meant to resolve to 1/255 after it is
                    scaled by 1.5 (applicationScale). Note, this is just a guess
                    as to what is small enough not to create its own artifacts,
                    and big enough to avoid the precision problems. Feel free
                    to experiment with smaller values as you choose.
                 */
                final float tinyOffset = 2.0f / (3 * 255);
                canvas.translate(tinyOffset, tinyOffset);
            }
            canvas.scale(applicationScale, applicationScale);
        }

        /**
         * Translate the motion event captured on screen to the application's window.
         */
        @UnsupportedAppUsage
        public void translateEventInScreenToAppWindow(MotionEvent event) {
            event.scale(applicationInvertedScale);
        }

        /**
         * Translate the window's layout parameter, from application's view to
         * Screen's view.
         */
        @UnsupportedAppUsage
        public void translateWindowLayout(WindowManager.LayoutParams params) {
            params.scale(applicationScale);
        }

        /**
         * Translate a length in application's window to screen.
         */
        public float translateLengthInAppWindowToScreen(float length) {
            return length * applicationScale;
        }

        /**
         * Translate a Rect in application's window to screen.
         */
        @UnsupportedAppUsage
        public void translateRectInAppWindowToScreen(Rect rect) {
            rect.scale(applicationScale);
        }

        /**
         * Translate a Rect in screen coordinates into the app window's coordinates.
         */
        @UnsupportedAppUsage
        public void translateRectInScreenToAppWindow(@Nullable Rect rect) {
            if (rect == null) {
                return;
            }
            rect.scale(applicationInvertedScale);
        }

        /**
         * Translate an {@link InsetsState} in screen coordinates into the app window's coordinates.
         */
        public void translateInsetsStateInScreenToAppWindow(InsetsState state) {
            state.scale(applicationInvertedScale);
        }

        /**
         * Translate {@link InsetsSourceControl}s in screen coordinates into the app window's
         * coordinates.
         */
        public void translateSourceControlsInScreenToAppWindow(InsetsSourceControl[] controls) {
            if (controls == null) {
                return;
            }
            final float scale = applicationInvertedScale;
            if (scale == 1f) {
                return;
            }
            for (InsetsSourceControl control : controls) {
                if (control == null) {
                    continue;
                }
                final Insets hint = control.getInsetsHint();
                control.setInsetsHint(
                        (int) (scale * hint.left),
                        (int) (scale * hint.top),
                        (int) (scale * hint.right),
                        (int) (scale * hint.bottom));
            }
        }

        /**
         * Translate a Point in screen coordinates into the app window's coordinates.
         */
        public void translatePointInScreenToAppWindow(PointF point) {
            final float scale = applicationInvertedScale;
            if (scale != 1.0f) {
                point.x *= scale;
                point.y *= scale;
            }
        }

        /**
         * Translate the location of the sub window.
         * @param params
         */
        public void translateLayoutParamsInAppWindowToScreen(LayoutParams params) {
            params.scale(applicationScale);
        }

        /**
         * Translate the content insets in application window to Screen. This uses
         * the internal buffer for content insets to avoid extra object allocation.
         */
        @UnsupportedAppUsage
        public Rect getTranslatedContentInsets(Rect contentInsets) {
            if (mContentInsetsBuffer == null) mContentInsetsBuffer = new Rect();
            mContentInsetsBuffer.set(contentInsets);
            translateRectInAppWindowToScreen(mContentInsetsBuffer);
            return mContentInsetsBuffer;
        }

        /**
         * Translate the visible insets in application window to Screen. This uses
         * the internal buffer for visible insets to avoid extra object allocation.
         */
        public Rect getTranslatedVisibleInsets(Rect visibleInsets) {
            if (mVisibleInsetsBuffer == null) mVisibleInsetsBuffer = new Rect();
            mVisibleInsetsBuffer.set(visibleInsets);
            translateRectInAppWindowToScreen(mVisibleInsetsBuffer);
            return mVisibleInsetsBuffer;
        }

        /**
         * Translate the touchable area in application window to Screen. This uses
         * the internal buffer for touchable area to avoid extra object allocation.
         */
        public Region getTranslatedTouchableArea(Region touchableArea) {
            if (mTouchableAreaBuffer == null) mTouchableAreaBuffer = new Region();
            mTouchableAreaBuffer.set(touchableArea);
            mTouchableAreaBuffer.scale(applicationScale);
            return mTouchableAreaBuffer;
        }
    }

    /** Applies the compatibility adjustment to the display metrics. */
    public void applyDisplayMetricsIfNeeded(DisplayMetrics inoutDm, boolean applyToSize) {
        if (hasOverrideScale()) {
            scaleDisplayMetrics(sOverrideInvertedScale, sOverrideDensityInvertScale, inoutDm,
                    applyToSize);
            return;
        }
        if (!equals(DEFAULT_COMPATIBILITY_INFO)) {
            applyToDisplayMetrics(inoutDm);
        }
    }

    public void applyToDisplayMetrics(DisplayMetrics inoutDm) {
        if (hasOverrideScale()) return;
        if (!supportsScreen()) {
            // This is a larger screen device and the app is not
            // compatible with large screens, so diddle it.
            CompatibilityInfo.computeCompatibleScaling(inoutDm, inoutDm);
        } else {
            inoutDm.widthPixels = inoutDm.noncompatWidthPixels;
            inoutDm.heightPixels = inoutDm.noncompatHeightPixels;
        }

        if (isScalingRequired()) {
            scaleDisplayMetrics(applicationInvertedScale, applicationDensityInvertedScale, inoutDm,
                    true /* applyToSize */);
        }
    }

    /** Scales the density of the given display metrics. */
    private static void scaleDisplayMetrics(float invertScale, float densityInvertScale,
            DisplayMetrics inoutDm, boolean applyToSize) {
        inoutDm.density = inoutDm.noncompatDensity * densityInvertScale;
        inoutDm.densityDpi = (int) ((inoutDm.noncompatDensityDpi
                * densityInvertScale) + .5f);
        // Note: since this is changing the scaledDensity, you might think we also need to change
        // inoutDm.fontScaleConverter to accurately calculate non-linear font scaling. But we're not
        // going to do that, for a couple of reasons (see b/265695259 for details):
        // 1. The first case is only for apps targeting SDK < 4. These ancient apps will just have
        //    to live with linear font scaling. We don't want to make anything more unpredictable.
        // 2. The second case where this is called is for scaling down games. But it is called in
        //    two situations:
        //    a. When from ResourcesImpl.updateConfiguration(), we will set the fontScaleConverter
        //       *after* this method is called. That's the only place where the app will actually
        //       use the DisplayMetrics for scaling fonts in its resources.
        //    b. Sometime later by WindowManager in onResume or other windowing events. In this case
        //       the DisplayMetrics object is never used by the app/resources, so it's ok if
        //       fontScaleConverter is null because it's not being used to scale fonts anyway.
        inoutDm.scaledDensity = inoutDm.noncompatScaledDensity * densityInvertScale;
        inoutDm.xdpi = inoutDm.noncompatXdpi * densityInvertScale;
        inoutDm.ydpi = inoutDm.noncompatYdpi * densityInvertScale;
        if (applyToSize) {
            inoutDm.widthPixels = (int) (inoutDm.widthPixels * invertScale + 0.5f);
            inoutDm.heightPixels = (int) (inoutDm.heightPixels * invertScale + 0.5f);
        }
    }

    public void applyToConfiguration(int displayDensity, Configuration inoutConfig) {
        if (hasOverrideScale()) return;
        if (!supportsScreen()) {
            // This is a larger screen device and the app is not
            // compatible with large screens, so we are forcing it to
            // run as if the screen is normal size.
            inoutConfig.screenLayout =
                    (inoutConfig.screenLayout&~Configuration.SCREENLAYOUT_SIZE_MASK)
                    | Configuration.SCREENLAYOUT_SIZE_NORMAL;
            inoutConfig.screenWidthDp = inoutConfig.compatScreenWidthDp;
            inoutConfig.screenHeightDp = inoutConfig.compatScreenHeightDp;
            inoutConfig.smallestScreenWidthDp = inoutConfig.compatSmallestScreenWidthDp;
        }
        inoutConfig.densityDpi = displayDensity;
        if (isScalingRequired()) {
            scaleConfiguration(applicationInvertedScale, applicationDensityInvertedScale,
                    inoutConfig);
        }
    }

    /** Scales the density and bounds of the given configuration. */
    public static void scaleConfiguration(float invertScale, Configuration inoutConfig) {
        scaleConfiguration(invertScale, invertScale, inoutConfig);
    }

    /** Scales the density and bounds of the given configuration. */
    public static void scaleConfiguration(float invertScale, float densityInvertScale,
            Configuration inoutConfig) {
        inoutConfig.densityDpi = (int) ((inoutConfig.densityDpi
                * densityInvertScale) + .5f);
        inoutConfig.windowConfiguration.scale(invertScale);
    }

    /** @see #sOverrideInvertedScale */
    public static void applyOverrideScaleIfNeeded(Configuration config) {
        if (!hasOverrideScale()) return;
        scaleConfiguration(sOverrideInvertedScale, sOverrideDensityInvertScale, config);
    }

    /** @see #sOverrideInvertedScale */
    public static void applyOverrideScaleIfNeeded(MergedConfiguration mergedConfig) {
        if (!hasOverrideScale()) return;
        scaleConfiguration(sOverrideInvertedScale, sOverrideDensityInvertScale,
                mergedConfig.getGlobalConfiguration());
        scaleConfiguration(sOverrideInvertedScale, sOverrideDensityInvertScale,
                mergedConfig.getOverrideConfiguration());
        scaleConfiguration(sOverrideInvertedScale, sOverrideDensityInvertScale,
                mergedConfig.getMergedConfiguration());
    }

    /** Returns {@code true} if this process is in a environment with override scale. */
    private static boolean hasOverrideScale() {
        return sOverrideInvertedScale != 1f || sOverrideDensityInvertScale != 1f;
    }

    /** @see #sOverrideInvertedScale */
    public static void setOverrideInvertedScale(float invertScale) {
        setOverrideInvertedScale(invertScale, invertScale);
    }

    /** @see #sOverrideInvertedScale */
    public static void setOverrideInvertedScale(float invertScale, float densityInvertScale) {
        sOverrideInvertedScale = invertScale;
        sOverrideDensityInvertScale = densityInvertScale;
    }

    /** @see #sOverrideInvertedScale */
    public static float getOverrideInvertedScale() {
        return sOverrideInvertedScale;
    }

    /** @see #sOverrideDensityInvertScale */
    public static float getOverrideDensityInvertedScale() {
        return sOverrideDensityInvertScale;
    }

    /**
     * Compute the frame Rect for applications runs under compatibility mode.
     *
     * @param dm the display metrics used to compute the frame size.
     * @param outDm If non-null the width and height will be set to their scaled values.
     * @return Returns the scaling factor for the window.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static float computeCompatibleScaling(DisplayMetrics dm, DisplayMetrics outDm) {
        final int width = dm.noncompatWidthPixels;
        final int height = dm.noncompatHeightPixels;
        int shortSize, longSize;
        if (width < height) {
            shortSize = width;
            longSize = height;
        } else {
            shortSize = height;
            longSize = width;
        }
        int newShortSize = (int)(DEFAULT_NORMAL_SHORT_DIMENSION * dm.density + 0.5f);
        float aspect = ((float)longSize) / shortSize;
        if (aspect > MAXIMUM_ASPECT_RATIO) {
            aspect = MAXIMUM_ASPECT_RATIO;
        }
        int newLongSize = (int)(newShortSize * aspect + 0.5f);
        int newWidth, newHeight;
        if (width < height) {
            newWidth = newShortSize;
            newHeight = newLongSize;
        } else {
            newWidth = newLongSize;
            newHeight = newShortSize;
        }

        float sw = width/(float)newWidth;
        float sh = height/(float)newHeight;
        float scale = sw < sh ? sw : sh;
        if (scale < 1) {
            scale = 1;
        }

        if (outDm != null) {
            outDm.widthPixels = newWidth;
            outDm.heightPixels = newHeight;
        }

        return scale;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        try {
            CompatibilityInfo oc = (CompatibilityInfo)o;
            if (mCompatibilityFlags != oc.mCompatibilityFlags) return false;
            if (applicationDensity != oc.applicationDensity) return false;
            if (applicationScale != oc.applicationScale) return false;
            if (applicationInvertedScale != oc.applicationInvertedScale) return false;
            if (applicationDensityScale != oc.applicationDensityScale) return false;
            if (applicationDensityInvertedScale != oc.applicationDensityInvertedScale) return false;
            return true;
        } catch (ClassCastException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{");
        sb.append(applicationDensity);
        sb.append("dpi");
        if (isScalingRequired()) {
            sb.append(" ");
            sb.append(applicationScale);
            sb.append("x");
        }
        if (hasOverrideScaling()) {
            sb.append(" overrideInvScale=");
            sb.append(applicationInvertedScale);
            sb.append(" overrideDensityInvScale=");
            sb.append(applicationDensityInvertedScale);
        }
        if (!supportsScreen()) {
            sb.append(" resizing");
        }
        if (neverSupportsScreen()) {
            sb.append(" never-compat");
        }
        if (alwaysSupportsScreen()) {
            sb.append(" always-compat");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mCompatibilityFlags;
        result = 31 * result + applicationDensity;
        result = 31 * result + Float.floatToIntBits(applicationScale);
        result = 31 * result + Float.floatToIntBits(applicationInvertedScale);
        result = 31 * result + Float.floatToIntBits(applicationDensityScale);
        result = 31 * result + Float.floatToIntBits(applicationDensityInvertedScale);
        return result;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCompatibilityFlags);
        dest.writeInt(applicationDensity);
        dest.writeFloat(applicationScale);
        dest.writeFloat(applicationInvertedScale);
        dest.writeFloat(applicationDensityScale);
        dest.writeFloat(applicationDensityInvertedScale);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final @android.annotation.NonNull Parcelable.Creator<CompatibilityInfo> CREATOR
            = new Parcelable.Creator<CompatibilityInfo>() {
        @Override
        public CompatibilityInfo createFromParcel(Parcel source) {
            return new CompatibilityInfo(source);
        }

        @Override
        public CompatibilityInfo[] newArray(int size) {
            return new CompatibilityInfo[size];
        }
    };

    private CompatibilityInfo(Parcel source) {
        mCompatibilityFlags = source.readInt();
        applicationDensity = source.readInt();
        applicationScale = source.readFloat();
        applicationInvertedScale = source.readFloat();
        applicationDensityScale = source.readFloat();
        applicationDensityInvertedScale = source.readFloat();
    }

    /**
     * A data class for holding scale factor for width, height, and density.
     */
    public static final class CompatScale {

        public final float mScaleFactor;
        public final float mDensityScaleFactor;

        public CompatScale(float scaleFactor) {
            this(scaleFactor, scaleFactor);
        }

        public CompatScale(float scaleFactor, float densityScaleFactor) {
            mScaleFactor = scaleFactor;
            mDensityScaleFactor = densityScaleFactor;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CompatScale)) {
                return false;
            }
            try {
                CompatScale oc = (CompatScale) o;
                if (mScaleFactor != oc.mScaleFactor) return false;
                if (mDensityScaleFactor != oc.mDensityScaleFactor) return false;
                return true;
            } catch (ClassCastException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("mScaleFactor= ");
            sb.append(mScaleFactor);
            sb.append(" mDensityScaleFactor= ");
            sb.append(mDensityScaleFactor);
            return sb.toString();
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + Float.floatToIntBits(mScaleFactor);
            result = 31 * result + Float.floatToIntBits(mDensityScaleFactor);
            return result;
        }
    }
}
