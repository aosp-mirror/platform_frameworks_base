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

package android.util;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.*;


/**
 * A structure describing general information about a display, such as its
 * size, density, and font scaling.
 * <p>To access the DisplayMetrics members, initialize an object like this:</p>
 * <pre> DisplayMetrics metrics = new DisplayMetrics();
 * getWindowManager().getDefaultDisplay().getMetrics(metrics);</pre>
 */
public class DisplayMetrics {
    /**
     * Standard quantized DPI for low-density screens.
     */
    public static final int DENSITY_LOW = 120;

    /**
     * Standard quantized DPI for medium-density screens.
     */
    public static final int DENSITY_MEDIUM = 160;

    /**
     * Standard quantized DPI for high-density screens.
     */
    public static final int DENSITY_HIGH = 240;

    /**
     * The reference density used throughout the system.
     */
    public static final int DENSITY_DEFAULT = DENSITY_MEDIUM;

    /**
     * The device's density.
     * @hide becase eventually this should be able to change while
     * running, so shouldn't be a constant.
     */
    public static final int DENSITY_DEVICE = getDeviceDensity();

    /**
     * The absolute width of the display in pixels.
     */
    public int widthPixels;
    /**
     * The absolute height of the display in pixels.
     */
    public int heightPixels;
    /**
     * The logical density of the display.  This is a scaling factor for the
     * Density Independent Pixel unit, where one DIP is one pixel on an
     * approximately 160 dpi screen (for example a 240x320, 1.5"x2" screen), 
     * providing the baseline of the system's display. Thus on a 160dpi screen 
     * this density value will be 1; on a 120 dpi screen it would be .75; etc.
     *  
     * <p>This value does not exactly follow the real screen size (as given by 
     * {@link #xdpi} and {@link #ydpi}, but rather is used to scale the size of
     * the overall UI in steps based on gross changes in the display dpi.  For 
     * example, a 240x320 screen will have a density of 1 even if its width is 
     * 1.8", 1.3", etc. However, if the screen resolution is increased to 
     * 320x480 but the screen size remained 1.5"x2" then the density would be 
     * increased (probably to 1.5).
     *
     * @see #DENSITY_DEFAULT
     */
    public float density;
    /**
     * The screen density expressed as dots-per-inch.  May be either
     * {@link #DENSITY_LOW}, {@link #DENSITY_MEDIUM}, or {@link #DENSITY_HIGH}.
     */
    public int densityDpi;
    /**
     * A scaling factor for fonts displayed on the display.  This is the same
     * as {@link #density}, except that it may be adjusted in smaller
     * increments at runtime based on a user preference for the font size.
     */
    public float scaledDensity;
    /**
     * The exact physical pixels per inch of the screen in the X dimension.
     */
    public float xdpi;
    /**
     * The exact physical pixels per inch of the screen in the Y dimension.
     */
    public float ydpi;

    public DisplayMetrics() {
    }
    
    public void setTo(DisplayMetrics o) {
        widthPixels = o.widthPixels;
        heightPixels = o.heightPixels;
        density = o.density;
        densityDpi = o.densityDpi;
        scaledDensity = o.scaledDensity;
        xdpi = o.xdpi;
        ydpi = o.ydpi;
    }
    
    public void setToDefaults() {
        widthPixels = 0;
        heightPixels = 0;
        density = DENSITY_DEVICE / (float) DENSITY_DEFAULT;
        densityDpi = DENSITY_DEVICE;
        scaledDensity = density;
        xdpi = DENSITY_DEVICE;
        ydpi = DENSITY_DEVICE;
    }

    /**
     * Update the display metrics based on the compatibility info and orientation
     * NOTE: DO NOT EXPOSE THIS API!  It is introducing a circular dependency
     * with the higher-level android.res package.
     * {@hide}
     */
    public void updateMetrics(CompatibilityInfo compatibilityInfo, int orientation,
            int screenLayout) {
        boolean expandable = compatibilityInfo.isConfiguredExpandable();
        boolean largeScreens = compatibilityInfo.isConfiguredLargeScreens();
        
        // Note: this assume that configuration is updated before calling
        // updateMetrics method.
        if (!expandable) {
            if ((screenLayout&Configuration.SCREENLAYOUT_COMPAT_NEEDED) == 0) {
                expandable = true;
                // the current screen size is compatible with non-resizing apps.
                compatibilityInfo.setExpandable(true);
            } else {
                compatibilityInfo.setExpandable(false);
            }
        }
        if (!largeScreens) {
            if ((screenLayout&Configuration.SCREENLAYOUT_SIZE_MASK)
                    != Configuration.SCREENLAYOUT_SIZE_LARGE) {
                largeScreens = true;
                // the current screen size is not large.
                compatibilityInfo.setLargeScreens(true);
            } else {
                compatibilityInfo.setLargeScreens(false);
            }
        }
        
        if (!expandable || !largeScreens) {
            // This is a larger screen device and the app is not 
            // compatible with large screens, so diddle it.
            
            // Figure out the compatibility width and height of the screen.
            int defaultWidth;
            int defaultHeight;
            switch (orientation) {
                case Configuration.ORIENTATION_LANDSCAPE: {
                    defaultWidth = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_HEIGHT * density +
                            0.5f);
                    defaultHeight = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_WIDTH * density +
                            0.5f);
                    break;
                }
                case Configuration.ORIENTATION_PORTRAIT:
                case Configuration.ORIENTATION_SQUARE:
                default: {
                    defaultWidth = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_WIDTH * density +
                            0.5f);
                    defaultHeight = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_HEIGHT * density +
                            0.5f);
                    break;
                }
                case Configuration.ORIENTATION_UNDEFINED: {
                    // don't change
                    return;
                }
            }
            
            if (defaultWidth < widthPixels) {
                // content/window's x offset in original pixels
                widthPixels = defaultWidth;
            }
            if (defaultHeight < heightPixels) {
                heightPixels = defaultHeight;
            }
        }
        
        if (compatibilityInfo.isScalingRequired()) {
            float invertedRatio = compatibilityInfo.applicationInvertedScale;
            density *= invertedRatio;
            densityDpi = (int)((density*DisplayMetrics.DENSITY_DEFAULT)+.5f);
            scaledDensity *= invertedRatio;
            xdpi *= invertedRatio;
            ydpi *= invertedRatio;
            widthPixels = (int) (widthPixels * invertedRatio + 0.5f);
            heightPixels = (int) (heightPixels * invertedRatio + 0.5f);
        }
    }

    @Override
    public String toString() {
        return "DisplayMetrics{density=" + density + ", width=" + widthPixels +
            ", height=" + heightPixels + ", scaledDensity=" + scaledDensity +
            ", xdpi=" + xdpi + ", ydpi=" + ydpi + "}";
    }

    private static int getDeviceDensity() {
        // qemu.sf.lcd_density can be used to override ro.sf.lcd_density
        // when running in the emulator, allowing for dynamic configurations.
        // The reason for this is that ro.sf.lcd_density is write-once and is
        // set by the init process when it parses build.prop before anything else.
        return SystemProperties.getInt("qemu.sf.lcd_density",
                SystemProperties.getInt("ro.sf.lcd_density", DENSITY_DEFAULT));
    }
}
