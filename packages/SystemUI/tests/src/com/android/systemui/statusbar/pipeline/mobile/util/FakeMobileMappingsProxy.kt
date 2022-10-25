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

package com.android.systemui.statusbar.pipeline.mobile.util

import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.settingslib.mobile.TelephonyIcons

class FakeMobileMappingsProxy : MobileMappingsProxy {
    private var iconMap = mapOf<String, MobileIconGroup>()
    private var defaultIcons = TelephonyIcons.THREE_G

    fun setIconMap(map: Map<String, MobileIconGroup>) {
        iconMap = map
    }
    override fun mapIconSets(config: Config): Map<String, MobileIconGroup> = iconMap
    fun getIconMap() = iconMap

    fun setDefaultIcons(group: MobileIconGroup) {
        defaultIcons = group
    }
    override fun getDefaultIcons(config: Config): MobileIconGroup = defaultIcons
    fun getDefaultIcons(): MobileIconGroup = defaultIcons

    override fun toIconKey(networkType: Int): String {
        return networkType.toString()
    }

    override fun toIconKeyOverride(networkType: Int): String {
        return toIconKey(networkType) + "_override"
    }
}
