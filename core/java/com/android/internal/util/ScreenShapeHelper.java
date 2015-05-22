package com.android.internal.util;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewRootImpl;

import com.android.internal.R;

/**
 * @hide
 */
public class ScreenShapeHelper {
    private static final boolean IS_EMULATOR = Build.HARDWARE.contains("goldfish");

    /**
     * Return the bottom pixel window outset of a window given its style attributes.
     * @return An outset dimension in pixels or 0 if no outset should be applied.
     */
    public static int getWindowOutsetBottomPx(Resources resources) {
        if (IS_EMULATOR) {
            return SystemProperties.getInt(ViewRootImpl.PROPERTY_EMULATOR_WIN_OUTSET_BOTTOM_PX, 0);
        } else {
            return resources.getInteger(com.android.internal.R.integer.config_windowOutsetBottom);
        }
    }
}
