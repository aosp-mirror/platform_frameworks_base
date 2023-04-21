/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** All flagging methods related to the new status bar pipeline (see b/238425913). */
@SysUISingleton
class StatusBarPipelineFlags
@Inject
constructor(
    context: Context,
    private val featureFlags: FeatureFlags,
) {
    private val mobileSlot = context.getString(com.android.internal.R.string.status_bar_mobile)
    private val wifiSlot = context.getString(com.android.internal.R.string.status_bar_wifi)

    /** True if we should display the mobile icons using the new status bar data pipeline. */
    fun useNewMobileIcons(): Boolean = featureFlags.isEnabled(Flags.NEW_STATUS_BAR_MOBILE_ICONS)

    /**
     * True if we should run the new mobile icons backend to get the logging.
     *
     * Does *not* affect whether we render the mobile icons using the new backend data. See
     * [useNewMobileIcons] for that.
     */
    fun runNewMobileIconsBackend(): Boolean =
        featureFlags.isEnabled(Flags.NEW_STATUS_BAR_MOBILE_ICONS_BACKEND) || useNewMobileIcons()

    /** True if we should display the wifi icon using the new status bar data pipeline. */
    fun useNewWifiIcon(): Boolean = featureFlags.isEnabled(Flags.NEW_STATUS_BAR_WIFI_ICON)

    /**
     * True if we should run the new wifi icon backend to get the logging.
     *
     * Does *not* affect whether we render the wifi icon using the new backend data. See
     * [useNewWifiIcon] for that.
     */
    fun runNewWifiIconBackend(): Boolean =
        featureFlags.isEnabled(Flags.NEW_STATUS_BAR_WIFI_ICON_BACKEND) || useNewWifiIcon()

    /**
     * Returns true if we should apply some coloring to the icons that were rendered with the new
     * pipeline to help with debugging.
     */
    fun useDebugColoring(): Boolean =
        featureFlags.isEnabled(Flags.NEW_STATUS_BAR_ICONS_DEBUG_COLORING)

    /**
     * For convenience in the StatusBarIconController, we want to gate some actions based on slot
     * name and the flag together.
     *
     * @return true if this icon is controlled by any of the status bar pipeline flags
     */
    fun isIconControlledByFlags(slotName: String): Boolean =
        slotName == wifiSlot && useNewWifiIcon() || slotName == mobileSlot && useNewMobileIcons()
}
