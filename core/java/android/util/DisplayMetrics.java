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
 */
public class DisplayMetrics {
    /**
     * The reference density used throughout the system.
     * 
     * @hide Pending API council approval
     */
    public static final int DEFAULT_DENSITY = 160;

    /**
     * The device's density.
     * @hide
     */
    public static final int DEVICE_DENSITY = getDeviceDensity();

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
     * this density value will be 1; on a 106 dpi screen it would be .75; etc.
     *  
     * <p>This value does not exactly follow the real screen size (as given by 
     * {@link #xdpi} and {@link #ydpi}, but rather is used to scale the size of
     * the overall UI in steps based on gross changes in the display dpi.  For 
     * example, a 240x320 screen will have a density of 1 even if its width is 
     * 1.8", 1.3", etc. However, if the screen resolution is increased to 
     * 320x480 but the screen size remained 1.5"x2" then the density would be 
     * increased (probably to 1.5).
     *
     * @see #DEFAULT_DENSITY
     */
    public float density;
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
        scaledDensity = o.scaledDensity;
        xdpi = o.xdpi;
        ydpi = o.ydpi;
    }
    
    public void setToDefaults() {
        widthPixels = 0;
        heightPixels = 0;
        density = DEVICE_DENSITY / (float) DEFAULT_DENSITY;
        scaledDensity = density;
        xdpi = DEVICE_DENSITY;
        ydpi = DEVICE_DENSITY;
    }

    /**
     * Update the display metrics based on the compatibility info and orientation
     * NOTE: DO NOT EXPOSE THIS API!  It is introducing a circular dependency
     * with the higher-level android.res package.
     * {@hide}
     */
    public void updateMetrics(CompatibilityInfo compatibilityInfo, int orientation,
            int screenLayout) {
        int xOffset = 0;
        if (!compatibilityInfo.isConfiguredExpandable()) {
            // Note: this assume that configuration is updated before calling
            // updateMetrics method.
            if (screenLayout == Configuration.SCREENLAYOUT_LARGE) {
                // This is a large screen device and the app is not 
                // compatible with large screens, to diddle it.
                
                compatibilityInfo.setExpandable(false);
                // Figure out the compatibility width and height of the screen.
                int defaultWidth;
                int defaultHeight;
                switch (orientation) {
                    case Configuration.ORIENTATION_LANDSCAPE: {
                        defaultWidth = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_HEIGHT * density);
                        defaultHeight = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_WIDTH * density);
                        break;
                    }
                    case Configuration.ORIENTATION_PORTRAIT:
                    case Configuration.ORIENTATION_SQUARE:
                    default: {
                        defaultWidth = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_WIDTH * density);
                        defaultHeight = (int)(CompatibilityInfo.DEFAULT_PORTRAIT_HEIGHT * density);
                        break;
                    }
                    case Configuration.ORIENTATION_UNDEFINED: {
                        // don't change
                        return;
                    }
                }
                
                if (defaultWidth < widthPixels) {
                    // content/window's x offset in original pixels
                    xOffset = ((widthPixels - defaultWidth) / 2);
                    widthPixels = defaultWidth;
                }
                if (defaultHeight < heightPixels) {
                    heightPixels = defaultHeight;
                }
                
            } else {
                // the screen size is same as expected size. make it expandable
                compatibilityInfo.setExpandable(true);
            }
        }
        compatibilityInfo.setVisibleRect(xOffset, widthPixels, heightPixels);
        if (compatibilityInfo.isScalingRequired()) {
            float invertedRatio = compatibilityInfo.applicationInvertedScale;
            density *= invertedRatio;
            scaledDensity *= invertedRatio;
            xdpi *= invertedRatio;
            ydpi *= invertedRatio;
            widthPixels *= invertedRatio;
            heightPixels *= invertedRatio;
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
                SystemProperties.getInt("ro.sf.lcd_density", DEFAULT_DENSITY));
    }
}
