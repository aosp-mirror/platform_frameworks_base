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
     * @return index of this display.
     */
    public int getDisplayId() {
        return mDisplay;
    }

    /**
     * @return the number of displays connected to the device.
     */
    native static int getDisplayCount();
    
    /**
     * @return width of this display in pixels.
     */
    native public int getWidth();
    
    /**
     * @return height of this display in pixels.
     */
    native public int getHeight();

    /**
     * @return orientation of this display.
     */
    native public int getOrientation();

    /**
     * @return pixel format of this display.
     */
    public int getPixelFormat() {
        return mPixelFormat;
    }
    
    /**
     * @return refresh rate of this display in frames per second.
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

