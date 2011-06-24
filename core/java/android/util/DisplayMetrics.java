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

import android.os.SystemProperties;


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
     * Standard quantized DPI for 720p TV screens.  Applications should
     * generally not worry about this density, instead targeting
     * {@link #DENSITY_XHIGH} for 1080p TV screens.  For situations where
     * output is needed for a 720p screen, the UI elements can be scaled
     * automatically by the platform.
     */
    public static final int DENSITY_TV = 213;

    /**
     * Standard quantized DPI for high-density screens.
     */
    public static final int DENSITY_HIGH = 240;

    /**
     * Standard quantized DPI for extra-high-density screens.
     */
    public static final int DENSITY_XHIGH = 320;

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

    /**
     * The reported display width prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public int noncompatWidthPixels;
    /**
     * The reported display height prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public int noncompatHeightPixels;
    /**
     * The reported display density prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public float noncompatDensity;
    /**
     * The reported scaled density prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public float noncompatScaledDensity;
    /**
     * The reported display xdpi prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public float noncompatXdpi;
    /**
     * The reported display ydpi prior to any compatibility mode scaling
     * being applied.
     * @hide
     */
    public float noncompatYdpi;

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
        noncompatWidthPixels = o.noncompatWidthPixels;
        noncompatHeightPixels = o.noncompatHeightPixels;
        noncompatDensity = o.noncompatDensity;
        noncompatScaledDensity = o.noncompatScaledDensity;
        noncompatXdpi = o.noncompatXdpi;
        noncompatYdpi = o.noncompatYdpi;
    }
    
    public void setToDefaults() {
        widthPixels = 0;
        heightPixels = 0;
        density = DENSITY_DEVICE / (float) DENSITY_DEFAULT;
        densityDpi = DENSITY_DEVICE;
        scaledDensity = density;
        xdpi = DENSITY_DEVICE;
        ydpi = DENSITY_DEVICE;
        noncompatWidthPixels = 0;
        noncompatHeightPixels = 0;
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
