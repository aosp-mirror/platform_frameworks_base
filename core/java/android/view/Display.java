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

package android.view;

import android.util.DisplayMetrics;

public class Display
{
    /**
     * Specify the default Display
     */
    public static final int DEFAULT_DISPLAY = 0;

    
    /**
     * Use the WindowManager interface to create a Display object.
     * Display gives you access to some information about a particular display
     * connected to the device.
     */
    Display(int display) {
        // initalize the statics when this class is first instansiated. This is
        // done here instead of in the static block because Zygote
        synchronized (mStaticInit) {
            if (!mInitialized) {
                nativeClassInit();
                mInitialized = true;
            }
        }
        mDisplay = display;
        init(display);
    }
    
    /**
     * Returns the index of this display.  This is currently undefined; do
     * not use.
     */
    public int getDisplayId() {
        return mDisplay;
    }

    /**
     * Returns the number of displays connected to the device.  This is
     * currently undefined; do not use.
     */
    native static int getDisplayCount();
    
    /**
     * Returns the raw width of the display, in pixels.  Note that this
     * should <em>not</em> generally be used for computing layouts, since
     * a device will typically have screen decoration (such as a status bar)
     * along the edges of the display that reduce the amount of application
     * space available from the raw size returned here.  This value is
     * adjusted for you based on the current rotation of the display.
     */
    native public int getWidth();
    
    /**
     * Returns the raw height of the display, in pixels.  Note that this
     * should <em>not</em> generally be used for computing layouts, since
     * a device will typically have screen decoration (such as a status bar)
     * along the edges of the display that reduce the amount of application
     * space available from the raw size returned here.  This value is
     * adjusted for you based on the current rotation of the display.
     */
    native public int getHeight();

    /**
     * Returns the rotation of the screen from its "natural" orientation.
     * The returned value may be {@link Surface#ROTATION_0 Surface.ROTATION_0}
     * (no rotation), {@link Surface#ROTATION_90 Surface.ROTATION_90},
     * {@link Surface#ROTATION_180 Surface.ROTATION_180}, or
     * {@link Surface#ROTATION_270 Surface.ROTATION_270}.  For
     * example, if a device has a naturally tall screen, and the user has
     * turned it on its side to go into a landscape orientation, the value
     * returned here may be either {@link Surface#ROTATION_90 Surface.ROTATION_90}
     * or {@link Surface#ROTATION_270 Surface.ROTATION_270} depending on
     * the direction it was turned.  The angle is the rotation of the drawn
     * graphics on the screen, which is the opposite direction of the physical
     * rotation of the device.  For example, if the device is rotated 90
     * degrees counter-clockwise, to compensate rendering will be rotated by
     * 90 degrees clockwise and thus the returned value here will be
     * {@link Surface#ROTATION_90 Surface.ROTATION_90}.
     */
    public int getRotation() {
        return getOrientation();
    }
    
    /**
     * @deprecated use {@link #getRotation}
     * @return orientation of this display.
     */
    @Deprecated native public int getOrientation();

    /**
     * Return the native pixel format of the display.  The returned value
     * may be one of the constants int {@link android.graphics.PixelFormat}.
     */
    public int getPixelFormat() {
        return mPixelFormat;
    }
    
    /**
     * Return the refresh rate of this display in frames per second.
     */
    public float getRefreshRate() {
        return mRefreshRate;
    }
    
    /**
     * Initialize a DisplayMetrics object from this display's data.
     * 
     * @param outMetrics
     */
    public void getMetrics(DisplayMetrics outMetrics) {
        outMetrics.widthPixels  = getWidth();
        outMetrics.heightPixels = getHeight();
        outMetrics.density      = mDensity;
        outMetrics.densityDpi   = (int)((mDensity*DisplayMetrics.DENSITY_DEFAULT)+.5f);
        outMetrics.scaledDensity= outMetrics.density;
        outMetrics.xdpi         = mDpiX;
        outMetrics.ydpi         = mDpiY;
    }

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    native private static void nativeClassInit();
    
    private native void init(int display);

    private int         mDisplay;
    // Following fields are initialized from native code
    private int         mPixelFormat;
    private float       mRefreshRate;
    private float       mDensity;
    private float       mDpiX;
    private float       mDpiY;
    
    private static final Object mStaticInit = new Object();
    private static boolean mInitialized = false;

    /**
     * Returns a display object which uses the metric's width/height instead.
     * @hide
     */
    public static Display createMetricsBasedDisplay(int displayId, DisplayMetrics metrics) {
        return new CompatibleDisplay(displayId, metrics);
    }

    private static class CompatibleDisplay extends Display {
        private final DisplayMetrics mMetrics;

        private CompatibleDisplay(int displayId, DisplayMetrics metrics) {
            super(displayId);
            mMetrics = metrics;
        }

        @Override
        public int getWidth() {
            return mMetrics.widthPixels;
        }

        @Override
        public int getHeight() {
            return mMetrics.heightPixels;
        }
    }
}

