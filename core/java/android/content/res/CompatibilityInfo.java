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
    public static final CompatibilityInfo DEFAULT_COMPATIBILITY_INFO = new CompatibilityInfo(); 

    /**
     * The default width of the screen in portrait mode. 
     */
    public static final int DEFAULT_PORTRAIT_WIDTH = 320;

    /**
     * The default height of the screen in portrait mode. 
     */    
    public static final int DEFAULT_PORTRAIT_HEIGHT = 480;

    /**
     * The x-shift mode that controls the position of the content or the window under
     * compatibility mode.
     * {@see getTranslator}
     * {@see Translator#mShiftMode}
     */
    private static final int X_SHIFT_NONE = 0;
    private static final int X_SHIFT_CONTENT = 1;
    private static final int X_SHIFT_AND_CLIP_CONTENT = 2;
    private static final int X_SHIFT_WINDOW = 3;


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
     * 1) Application declares its expandable in manifest file using <expandable /> or
     * 2) The screen size is same as (320 x 480) * density. 
     * {@see compatibilityFlag}
     */
    private static final int EXPANDABLE = 2;
    
    /**
     * A flag mask to tell if the application is configured to be expandable. This differs
     * from EXPANDABLE in that the application that is not expandable will be 
     * marked as expandable if it runs in (320x 480) * density screen size.
     */
    private static final int CONFIGURED_EXPANDABLE = 4; 

    private static final int SCALING_EXPANDABLE_MASK = SCALING_REQUIRED | EXPANDABLE;

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
    
    /**
     * Window size in Compatibility Mode, in real pixels. This is updated by
     * {@link DisplayMetrics#updateMetrics}.
     */
    private int mWidth;
    private int mHeight;
    
    /**
     * The x offset to center the window content. In X_SHIFT_WINDOW mode, the offset is added
     * to the window's layout. In X_SHIFT_CONTENT/X_SHIFT_AND_CLIP_CONTENT mode, the offset
     * is used to translate the Canvas.
     */
    private int mXOffset;

    public CompatibilityInfo(ApplicationInfo appInfo) {
        appFlags = appInfo.flags;
        
        if ((appInfo.flags & ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
            mCompatibilityFlags = EXPANDABLE | CONFIGURED_EXPANDABLE;
        }
        
        float packageDensityScale = -1.0f;
        if (appInfo.supportsDensities != null) {
            int minDiff = Integer.MAX_VALUE;
            for (int density : appInfo.supportsDensities) {
                if (density == ApplicationInfo.ANY_DENSITY) { 
                    packageDensityScale = 1.0f;
                    break;
                }
                int tmpDiff = Math.abs(DisplayMetrics.DEVICE_DENSITY - density);
                if (tmpDiff == 0) {
                    packageDensityScale = 1.0f;
                    break;
                }
                // prefer higher density (appScale>1.0), unless that's only option.
                if (tmpDiff < minDiff && packageDensityScale < 1.0f) {
                    packageDensityScale = DisplayMetrics.DEVICE_DENSITY / (float) density;
                    minDiff = tmpDiff;
                }
            }
        }
        if (packageDensityScale > 0.0f) {
            applicationScale = packageDensityScale;
        } else {
            applicationScale =
                    DisplayMetrics.DEVICE_DENSITY / (float) DisplayMetrics.DEFAULT_DENSITY;
        }
        applicationInvertedScale = 1.0f / applicationScale;
        if (applicationScale != 1.0f) {
            mCompatibilityFlags |= SCALING_REQUIRED;
        }
    }

    private CompatibilityInfo(int appFlags, int compFlags, float scale, float invertedScale) {
        this.appFlags = appFlags;
        mCompatibilityFlags = compFlags;
        applicationScale = scale;
        applicationInvertedScale = invertedScale;
    }

    private CompatibilityInfo() {
        this(ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS
                | ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS
                | ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS,
                EXPANDABLE | CONFIGURED_EXPANDABLE,
                1.0f,
                1.0f);
    }

    /**
     * Returns the copy of this instance.
     */
    public CompatibilityInfo copy() {
        CompatibilityInfo info = new CompatibilityInfo(appFlags, mCompatibilityFlags,
                applicationScale, applicationInvertedScale);
        info.setVisibleRect(mXOffset, mWidth, mHeight);
        return info;
    }
 
    /**
     * Sets the application's visible rect in compatibility mode.
     * @param xOffset the application's x offset that is added to center the content.
     * @param widthPixels the application's width in real pixels on the screen.
     * @param heightPixels the application's height in real pixels on the screen.
     */
    public void setVisibleRect(int xOffset, int widthPixels, int heightPixels) {
        this.mXOffset = xOffset; 
        mWidth = widthPixels;
        mHeight = heightPixels;
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
     * @return true if the application is configured to be expandable.
     */
    public boolean isConfiguredExpandable() {
        return (mCompatibilityFlags & CompatibilityInfo.CONFIGURED_EXPANDABLE) != 0;
    }

    /**
     * @return true if the scaling is required
     */
    public boolean isScalingRequired() {
        return (mCompatibilityFlags & SCALING_REQUIRED) != 0;
    }
    
    @Override
    public String toString() {
        return "CompatibilityInfo{scale=" + applicationScale +
                ", compatibility flag=" + mCompatibilityFlags + "}"; 
    }

    /**
     * Returns the translator which can translate the coordinates of the window.
     * There are five different types of Translator.
     * 
     * 1) {@link CompatibilityInfo#X_SHIFT_AND_CLIP_CONTENT}
     *   Shift and clip the content of the window at drawing time. Used for activities'
     *   main window (with no gravity).
     * 2) {@link CompatibilityInfo#X_SHIFT_CONTENT}
     *   Shift the content of the window at drawing time. Used for windows that is created by
     *   an application and expected to be aligned with the application window.
     * 3) {@link CompatibilityInfo#X_SHIFT_WINDOW}
     *   Create the window with adjusted x- coordinates. This is typically used 
     *   in popup window, where it has to be placed relative to main window.
     * 4) {@link CompatibilityInfo#X_SHIFT_NONE}
     *   No adjustment required, such as dialog.
     * 5) Same as X_SHIFT_WINDOW, but no scaling. This is used by {@link SurfaceView}, which
     *  does not require scaling, but its window's location has to be adjusted.
     * 
     * @param params the window's parameter
     */
    public Translator getTranslator(WindowManager.LayoutParams params) {
        if ( (mCompatibilityFlags & CompatibilityInfo.SCALING_EXPANDABLE_MASK)
                == CompatibilityInfo.EXPANDABLE) {
            if (DBG) Log.d(TAG, "no translation required");
            return null;
        }
        
        if ((mCompatibilityFlags & CompatibilityInfo.EXPANDABLE) == 0) {
            if ((params.flags & WindowManager.LayoutParams.FLAG_NO_COMPATIBILITY_SCALING) != 0) {
                if (DBG) Log.d(TAG, "translation for surface view selected");
                return new Translator(X_SHIFT_WINDOW, false, 1.0f, 1.0f);
            } else {
                int shiftMode;
                if (params.gravity == Gravity.NO_GRAVITY) {
                    // For Regular Application window
                    shiftMode = X_SHIFT_AND_CLIP_CONTENT;
                    if (DBG) Log.d(TAG, "shift and clip translator");
                } else if (params.width == WindowManager.LayoutParams.FILL_PARENT) {
                    // For Regular Application window
                    shiftMode = X_SHIFT_CONTENT;
                    if (DBG) Log.d(TAG, "shift content translator");
                } else if ((params.gravity & Gravity.LEFT) != 0 && params.x > 0) {
                    shiftMode = X_SHIFT_WINDOW;
                    if (DBG) Log.d(TAG, "shift window translator");
                } else {
                    shiftMode = X_SHIFT_NONE;
                    if (DBG) Log.d(TAG, "no content/window translator");
                }
                return new Translator(shiftMode);
            }
        } else if (isScalingRequired()) {
            return new Translator();
        } else {
            return null;
        }
    }

    /**
     * A helper object to translate the screen and window coordinates back and forth.
     * @hide
     */
    public class Translator {
        final private int mShiftMode;
        final public boolean scalingRequired;
        final public float applicationScale;
        final public float applicationInvertedScale;
        
        private Rect mContentInsetsBuffer = null;
        private Rect mVisibleInsets = null;
        
        Translator(int shiftMode, boolean scalingRequired, float applicationScale,
                float applicationInvertedScale) {
            mShiftMode = shiftMode;
            this.scalingRequired = scalingRequired;
            this.applicationScale = applicationScale;
            this.applicationInvertedScale = applicationInvertedScale;
        }

        Translator(int shiftMode) {
            this(shiftMode,
                    isScalingRequired(),
                    CompatibilityInfo.this.applicationScale,
                    CompatibilityInfo.this.applicationInvertedScale);
        }
        
        Translator() {
            this(X_SHIFT_NONE);
        }

        /**
         * Translate the screen rect to the application frame.
         */
        public void translateRectInScreenToAppWinFrame(Rect rect) {
            if (rect.isEmpty()) return; // skip if the window size is empty.
            switch (mShiftMode) {
                case X_SHIFT_AND_CLIP_CONTENT:
                    rect.intersect(0, 0, mWidth, mHeight);
                    break;
                case X_SHIFT_CONTENT:
                    rect.intersect(0, 0, mWidth + mXOffset, mHeight);
                    break;
                case X_SHIFT_WINDOW:
                case X_SHIFT_NONE:
                    break;
            }
            if (scalingRequired) {
                rect.scale(applicationInvertedScale);
            }
        }

        /**
         * Translate the region in window to screen. 
         */
        public void translateRegionInWindowToScreen(Region transparentRegion) {
            switch (mShiftMode) {
                case X_SHIFT_AND_CLIP_CONTENT:
                case X_SHIFT_CONTENT:
                    transparentRegion.scale(applicationScale);
                    transparentRegion.translate(mXOffset, 0);
                    break;
                case X_SHIFT_WINDOW:
                case X_SHIFT_NONE:
                    transparentRegion.scale(applicationScale);
            }
        }

        /**
         * Apply translation to the canvas that is necessary to draw the content.
         */
        public void translateCanvas(Canvas canvas) {
            if (mShiftMode == X_SHIFT_CONTENT ||
                    mShiftMode == X_SHIFT_AND_CLIP_CONTENT) {
                // TODO: clear outside when rotation is changed.

                // Translate x-offset only when the content is shifted.
                canvas.translate(mXOffset, 0);
            }
            if (scalingRequired) {
                canvas.scale(applicationScale, applicationScale);
            }
        }

        /**
         * Translate the motion event captured on screen to the application's window.
         */
        public void translateEventInScreenToAppWindow(MotionEvent event) {
            if (mShiftMode == X_SHIFT_CONTENT ||
                    mShiftMode == X_SHIFT_AND_CLIP_CONTENT) {
                event.translate(-mXOffset, 0);
            }
            if (scalingRequired) {
                event.scale(applicationInvertedScale);
            }
        }

        /**
         * Translate the window's layout parameter, from application's view to
         * Screen's view.
         */
        public void translateWindowLayout(WindowManager.LayoutParams params) {
            switch (mShiftMode) {
                case X_SHIFT_NONE:
                case X_SHIFT_AND_CLIP_CONTENT:
                case X_SHIFT_CONTENT:
                    params.scale(applicationScale);
                    break;
                case X_SHIFT_WINDOW:
                    params.scale(applicationScale);
                    params.x += mXOffset;
                    break;
            }
        }
        
        /**
         * Translate a Rect in application's window to screen.
         */
        public void translateRectInAppWindowToScreen(Rect rect) {
            // TODO Auto-generated method stub
            if (scalingRequired) {
                rect.scale(applicationScale);
            }
            switch(mShiftMode) {
                case X_SHIFT_NONE:
                case X_SHIFT_WINDOW:
                    break;
                case X_SHIFT_CONTENT:
                case X_SHIFT_AND_CLIP_CONTENT:
                    rect.offset(mXOffset, 0);
                    break;
            }
        }
 
        /**
         * Translate a Rect in screen coordinates into the app window's coordinates.
         */
        public void translateRectInScreenToAppWindow(Rect rect) {
            switch (mShiftMode) {
                case X_SHIFT_NONE:
                case X_SHIFT_WINDOW:
                    break;
                case X_SHIFT_CONTENT: {
                    rect.intersects(mXOffset, 0, rect.right, rect.bottom);
                    int dx = Math.min(mXOffset, rect.left);
                    rect.offset(-dx, 0);
                    break;
                }
                case X_SHIFT_AND_CLIP_CONTENT: {
                    rect.intersects(mXOffset, 0, mWidth + mXOffset, mHeight);
                    int dx = Math.min(mXOffset, rect.left);
                    rect.offset(-dx, 0);
                    break;
                }
            }
            if (scalingRequired) {
                rect.scale(applicationInvertedScale);
            }
        }

        /**
         * Translate the location of the sub window.
         * @param params
         */
        public void translateLayoutParamsInAppWindowToScreen(LayoutParams params) {
            if (scalingRequired) {
                params.scale(applicationScale);
            }
            switch (mShiftMode) {
                // the window location on these mode does not require adjustmenet.
                case X_SHIFT_NONE:
                case X_SHIFT_WINDOW:
                    break;
                case X_SHIFT_CONTENT:
                case X_SHIFT_AND_CLIP_CONTENT:
                    params.x += mXOffset;
                    break;
            }
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
            if (mVisibleInsets == null) mVisibleInsets = new Rect();
            mVisibleInsets.set(visibleInsets);
            translateRectInAppWindowToScreen(mVisibleInsets);
            return mVisibleInsets;
        }
    }
}
