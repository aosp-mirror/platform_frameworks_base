package com.android.systemui.util

import android.content.res.Resources
import com.android.systemui.R

object LargeScreenUtils {

    /**
     * Returns true if the device should use the split notification shade, based on orientation and
     * screen width.
     */
    @JvmStatic
    fun shouldUseSplitNotificationShade(resources: Resources): Boolean {
        return resources.getBoolean(R.bool.config_use_split_notification_shade)
    }

    /**
     * Returns true if we should use large screen shade header:
     * [com.android.systemui.statusbar.phone.LargeScreenShadeHeaderController]
     * That should be true when we have enough horizontal space to show all info in one row.
     */
    @JvmStatic
    fun shouldUseLargeScreenShadeHeader(resources: Resources): Boolean {
        return resources.getBoolean(R.bool.config_use_large_screen_shade_header)
    }
}