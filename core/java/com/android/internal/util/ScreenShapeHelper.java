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
     * @param displayMetrics Display metrics of the current device
     * @param windowStyle Window style attributes for the window.
     * @return An outset dimension in pixels or 0 if no outset should be applied.
     */
    public static int getWindowOutsetBottomPx(DisplayMetrics displayMetrics,
            TypedArray windowStyle) {
        if (IS_EMULATOR) {
            return SystemProperties.getInt(ViewRootImpl.PROPERTY_EMULATOR_WIN_OUTSET_BOTTOM_PX, 0);
        } else if (windowStyle.hasValue(R.styleable.Window_windowOutsetBottom)) {
            TypedValue outsetBottom = new TypedValue();
            windowStyle.getValue(R.styleable.Window_windowOutsetBottom, outsetBottom);
            return (int) outsetBottom.getDimension(displayMetrics);
        }
        return 0;
    }
}
