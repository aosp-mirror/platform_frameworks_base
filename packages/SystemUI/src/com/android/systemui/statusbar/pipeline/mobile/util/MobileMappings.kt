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

import android.telephony.Annotation.NetworkType
import android.telephony.TelephonyDisplayInfo
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.MobileMappings.Config
import javax.inject.Inject

/**
 * [MobileMappings] owns the logic on creating the map from [TelephonyDisplayInfo] to
 * [MobileIconGroup]. It creates that hash map and also manages the creation of lookup keys. This
 * interface allows us to proxy those calls to the static java methods in SettingsLib and also fake
 * them out in tests
 */
interface MobileMappingsProxy {
    fun mapIconSets(config: Config): Map<String, MobileIconGroup>
    fun getDefaultIcons(config: Config): MobileIconGroup
    fun getIconKey(displayInfo: TelephonyDisplayInfo): String
    fun toIconKey(@NetworkType networkType: Int): String
    fun toIconKeyOverride(@NetworkType networkType: Int): String
}

/** Injectable wrapper class for [MobileMappings] */
class MobileMappingsProxyImpl @Inject constructor() : MobileMappingsProxy {
    override fun mapIconSets(config: Config): Map<String, MobileIconGroup> =
        MobileMappings.mapIconSets(config)

    override fun getDefaultIcons(config: Config): MobileIconGroup =
        MobileMappings.getDefaultIcons(config)

    override fun getIconKey(displayInfo: TelephonyDisplayInfo): String =
        MobileMappings.getIconKey(displayInfo)

    override fun toIconKey(@NetworkType networkType: Int): String =
        MobileMappings.toIconKey(networkType)

    override fun toIconKeyOverride(networkType: Int): String =
        MobileMappings.toDisplayIconKey(networkType)
}
