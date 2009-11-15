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

import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

/**
 * CompatibilityInfo class keeps the information about compatibility mode that the application is
 * running under.
 * 
 *  {@hide} 
 */
public class CompatibilityInfo {
    private static final boolean DBG = false;
    private static final String TAG = "CompatibilityInfo";
    
    /** default compatibility info object for compatible applications */
    public static final CompatibilityInfo DEFAULT_COMPATIBILITY_INFO = new CompatibilityInfo() {
        @Override
        public void setExpandable(boolean expandable) {
            throw new UnsupportedOperationException("trying to change default compatibility info");
        }
    };

    /**
     * The default width of the screen in portrait mode. 
     */
    public static final int DEFAULT_PORTRAIT_WIDTH = 320;

    /**
     * The default height of the screen in portrait mode. 
     */    
    public static final int DEFAULT_PORTRAIT_HEIGHT = 480;

    /**
     *  A compatibility flags
     */
    private int mCompatibilityFlags;
    
    /**
     * A flag mask to tell if the application needs scaling (when mApplicationScale != 1.0f)
     * {@see compatibilityFlag}
     */
    private static final int SCALING_REQUIRED = 1; 

    /**
     * A flag mask to indicates that the application can expand over the original size.
     * The flag is set to true if
     * 1) Application declares its expandable in manifest file using <supports-screens> or
     * 2) Configuration.SCREENLAYOUT_COMPAT_NEEDED is not set
     * {@see compatibilityFlag}
     */
    private static final int EXPANDABLE = 2;
    
    /**
     * A flag mask to tell if the application is configured to be expandable. This differs
     * from EXPANDABLE in that the application that is not expandable will be 
     * marked as expandable if Configuration.SCREENLAYOUT_COMPAT_NEEDED is not set.
     */
    private static final int CONFIGURED_EXPANDABLE = 4; 

    /**
     * A flag mask to indicates that the application supports large screens.
     * The flag is set to true if
     * 1) Application declares it supports large screens in manifest file using <supports-screens> or
     * 2) The screen size is not large
     * {@see compatibilityFlag}
     */
    private static final int LARGE_SCREENS = 8;
    
    /**
     * A flag mask to tell if the application supports large screens. This differs
     * from LARGE_SCREENS in that the application that does not support large
     * screens will be marked as supporting them if the current screen is not
     * large.
     */
    private static final int CONFIGURED_LARGE_SCREENS = 16; 

    private static final int SCALING_EXPANDABLE_MASK = SCALING_REQUIRED | EXPANDABLE | LARGE_SCREENS;

    /**
     * The effective screen density we have selected for this application.
     */
    public final int applicationDensity;
    
    /**
     * Application's scale.
     */
    public final float applicationScale;

    /**
     * Application's inverted scale.
     */
    public final float applicationInvertedScale;

    /**
     * The flags from ApplicationInfo.
     */
    public final int appFlags;
    
    public CompatibilityInfo(ApplicationInfo appInfo) {
        appFlags = appInfo.flags;
        
        if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
            mCompatibilityFlags |= LARGE_SCREENS | CONFIGURED_LARGE_SCREENS;
        }
        if ((appInfo.flags & ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS) != 0) {
            mCompatibilityFlags |= EXPANDABLE | CONFIGURED_EXPANDABLE;
        }
        
        if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES) != 0) {
            applicationDensity = DisplayMetrics.DENSITY_DEVICE;
            applicationScale = 1.0f;
            applicationInvertedScale = 1.0f;
        } else {
            applicationDensity = DisplayMetrics.DENSITY_DEFAULT;
            applicationScale = DisplayMetrics.DENSITY_DEVICE
                    / (float) DisplayMetrics.DENSITY_DEFAULT;
            applicationInvertedScale = 1.0f / applicationScale;
            mCompatibilityFlags |= SCALING_REQUIRED;
        }
    }

    private CompatibilityInfo(int appFlags, int compFlags,
            int dens, float scale, float invertedScale) {
        this.appFlags = appFlags;
        mCompatibilityFlags = compFlags;
        applicationDensity = dens;
        applicationScale = scale;
        applicationInvertedScale = invertedScale;
    }

    private CompatibilityInfo() {
        this(ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS
                | ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS
                | ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS
                | ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS,
                EXPANDABLE | CONFIGURED_EXPANDABLE,
                DisplayMetrics.DENSITY_DEVICE,
                1.0f,
                1.0f);
    }

    /**
     * Returns the copy of this instance.
     */
    public CompatibilityInfo copy() {
        CompatibilityInfo info = new CompatibilityInfo(appFlags, mCompatibilityFlags,
                applicationDensity, applicationScale, applicationInvertedScale);
        return info;
    }
 
    /**
     * Sets expandable bit in the compatibility flag.
     */
    public void setExpandable(boolean expandable) {
        if (expandable) {
            mCompatibilityFlags |= CompatibilityInfo.EXPANDABLE;
        } else {
            mCompatibilityFlags &= ~CompatibilityInfo.EXPANDABLE;
        }
    }

    /**
     * Sets large screen bit in the compatibility flag.
     */
    public void setLargeScreens(boolean expandable) {
        if (expandable) {
            mCompatibilityFlags |= CompatibilityInfo.LARGE_SCREENS;
        } else {
            mCompatibilityFlags &= ~CompatibilityInfo.LARGE_SCREENS;
        }
    }

    /**
     * @return true if the application is configured to be expandable.
     */
    public boolean isConfiguredExpandable() {
        return (mCompatibilityFlags & CompatibilityInfo.CONFIGURED_EXPANDABLE) != 0;
    }

    /**
     * @return true if the application is configured to be expandable.
     */
    public boolean isConfiguredLargeScreens() {
        return (mCompatibilityFlags & CompatibilityInfo.CONFIGURED_LARGE_SCREENS) != 0;
    }

    /**
     * @return true if the scaling is required
     */
    public boolean isScalingRequired() {
        return (mCompatibilityFlags & SCALING_REQUIRED) != 0;
    }
    
    public boolean supportsScreen() {
        return (mCompatibilityFlags & (EXPANDABLE|LARGE_SCREENS))
                == (EXPANDABLE|LARGE_SCREENS);
    }
    
    @Override
    public String toString() {
        return "CompatibilityInfo{scale=" + applicationScale +
                ", supports screen=" + supportsScreen() + "}";
    }

    /**
     * Returns the translator which translates the coordinates in compatibility mode.
     * @param params the window's parameter
     */
    public Translator getTranslator() {
        return isScalingRequired() ? new Translator() : null;
    }

    /**
     * A helper object to translate the screen and window coordinates back and forth.
     * @hide
     */
    public class Translator {
        final public float applicationScale;
        final public float applicationInvertedScale;
        
        private Rect mContentInsetsBuffer = null;
        private Rect mVisibleInsetsBuffer = null;
        
        Translator(float applicationScale, float applicationInvertedScale) {
            this.applicationScale = applicationScale;
            this.applicationInvertedScale = applicationInvertedScale;
        }

        Translator() {
            this(CompatibilityInfo.this.applicationScale,
                    CompatibilityInfo.this.applicationInvertedScale);
        }

        /**
         * Translate the screen rect to the application frame.
         */
        public void translateRectInScreenToAppWinFrame(Rect rect) {
            rect.scale(applicationInvertedScale);
        }

        /**
         * Translate the region in window to screen. 
         */
        public void translateRegionInWindowToScreen(Region transparentRegion) {
            transparentRegion.scale(applicationScale);
        }

        /**
         * Apply translation to the canvas that is necessary to draw the content.
         */
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
        public void translateEventInScreenToAppWindow(MotionEvent event) {
            event.scale(applicationInvertedScale);
        }

        /**
         * Translate the window's layout parameter, from application's view to
         * Screen's view.
         */
        public void translateWindowLayout(WindowManager.LayoutParams params) {
            params.scale(applicationScale);
        }
        
        /**
         * Translate a Rect in application's window to screen.
         */
        public void translateRectInAppWindowToScreen(Rect rect) {
            rect.scale(applicationScale);
        }
 
        /**
         * Translate a Rect in screen coordinates into the app window's coordinates.
         */
        public void translateRectInScreenToAppWindow(Rect rect) {
            rect.scale(applicationInvertedScale);
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
        public Rect getTranslatedContentInsets(Rect contentInsets) {
            if (mContentInsetsBuffer == null) mContentInsetsBuffer = new Rect();
            mContentInsetsBuffer.set(contentInsets);
            translateRectInAppWindowToScreen(mContentInsetsBuffer);
            return mContentInsetsBuffer;
        }

        /**
         * Translate the visible insets in application window to Screen. This uses
         * the internal buffer for content insets to avoid extra object allocation.
         */
        public Rect getTranslatedVisbileInsets(Rect visibleInsets) {
            if (mVisibleInsetsBuffer == null) mVisibleInsetsBuffer = new Rect();
            mVisibleInsetsBuffer.set(visibleInsets);
            translateRectInAppWindowToScreen(mVisibleInsetsBuffer);
            return mVisibleInsetsBuffer;
        }
    }

    /**
     * Returns the frame Rect for applications runs under compatibility mode.
     *
     * @param dm the display metrics used to compute the frame size.
     * @param orientation the orientation of the screen.
     * @param outRect the output parameter which will contain the result.
     */
    public static void updateCompatibleScreenFrame(DisplayMetrics dm, int orientation,
            Rect outRect) {
        int width = dm.widthPixels;
        int portraitHeight = (int) (DEFAULT_PORTRAIT_HEIGHT * dm.density + 0.5f);
        int portraitWidth = (int) (DEFAULT_PORTRAIT_WIDTH * dm.density + 0.5f);
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            int xOffset = (width - portraitHeight) / 2 ;
            outRect.set(xOffset, 0, xOffset + portraitHeight, portraitWidth);
        } else {
            int xOffset = (width - portraitWidth) / 2 ;
            outRect.set(xOffset, 0, xOffset + portraitWidth, portraitHeight);
        }
    }
}
