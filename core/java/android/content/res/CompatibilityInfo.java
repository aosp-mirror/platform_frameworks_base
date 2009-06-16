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
import android.util.DisplayMetrics;
import android.view.Gravity;

/**
 * CompatibilityInfo class keeps the information about compatibility mode that the application is
 * running under.
 * 
 *  {@hide} 
 */
public class CompatibilityInfo {
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
     * Application's scale.
     */
    public final float mApplicationScale;

    /**
     * Application's inverted scale.
     */
    public final float mApplicationInvertedScale;
    
    /**
     * A boolean flag to indicates that the application can expand over the original size.
     * The flag is set to true if
     * 1) Application declares its expandable in manifest file using <expandable /> or
     * 2) The screen size is same as (320 x 480) * density. 
     */
    public boolean mExpandable;

    /**
     * A expandable flag in the configuration.
     */
    public final boolean mConfiguredExpandable;
    
    /**
     * A boolean flag to tell if the application needs scaling (when mApplicationScale != 1.0f)
     */
    public final boolean mScalingRequired;

    public CompatibilityInfo(ApplicationInfo appInfo) {
        mExpandable = mConfiguredExpandable = appInfo.expandable;
        
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
            mApplicationScale = packageDensityScale;
        } else {
            mApplicationScale = DisplayMetrics.DEVICE_DENSITY / (float) DisplayMetrics.DEFAULT_DENSITY;
        }
        mApplicationInvertedScale = 1.0f / mApplicationScale;
        mScalingRequired = mApplicationScale != 1.0f;
    }

    private CompatibilityInfo() {
        mApplicationScale = mApplicationInvertedScale = 1.0f;
        mExpandable = mConfiguredExpandable = true;
        mScalingRequired = false;
    }

    @Override
    public String toString() {
        return "CompatibilityInfo{scale=" + mApplicationScale +
                ", expandable=" + mExpandable + "}"; 
    }
}
