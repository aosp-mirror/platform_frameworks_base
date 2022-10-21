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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** All flagging methods related to the new status bar pipeline (see b/238425913). */
@SysUISingleton
class StatusBarPipelineFlags @Inject constructor(private val featureFlags: FeatureFlags) {
    /** True if we should display the mobile icons using the new status bar data pipeline. */
    fun useNewMobileIcons(): Boolean = featureFlags.isEnabled(Flags.NEW_STATUS_BAR_MOBILE_ICONS)

    /** True if we should display the wifi icon using the new status bar data pipeline. */
    fun useNewWifiIcon(): Boolean = featureFlags.isEnabled(Flags.NEW_STATUS_BAR_WIFI_ICON)

    // TODO(b/238425913): Add flags to only run the mobile backend or wifi backend so we get the
    //   logging without getting the UI effects.

    /**
     * Returns true if we should apply some coloring to the wifi icon that was rendered with the new
     * pipeline to help with debugging.
     */
    // For now, just always apply the debug coloring if we've enabled the new icon.
    fun useWifiDebugColoring(): Boolean = useNewWifiIcon()
}
