package com.android.internal.util;

import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;
import android.view.ViewRootImpl;

/**
 * @hide
 */
public class ScreenShapeHelper {
    /**
     * Return the bottom pixel window outset of a window given its style attributes.
     * @return An outset dimension in pixels or 0 if no outset should be applied.
     */
    public static int getWindowOutsetBottomPx(Resources resources) {
        if (Build.IS_EMULATOR) {
            return SystemProperties.getInt(ViewRootImpl.PROPERTY_EMULATOR_WIN_OUTSET_BOTTOM_PX, 0);
        } else {
            return resources.getInteger(com.android.internal.R.integer.config_windowOutsetBottom);
        }
    }
}
