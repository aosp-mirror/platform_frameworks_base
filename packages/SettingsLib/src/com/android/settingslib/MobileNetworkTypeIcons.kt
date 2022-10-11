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

package com.android.settingslib

import com.android.settingslib.mobile.TelephonyIcons.ICON_NAME_TO_ICON

/**
 * A utility class to fetch instances of [MobileNetworkTypeIcon] given a
 * [SignalIcon.MobileIconGroup].
 *
 * Use [getNetworkTypeIcon] to fetch the instances.
 */
class MobileNetworkTypeIcons {
    companion object {
        /**
         * A map from a [SignalIcon.MobileIconGroup.name] to an instance of [MobileNetworkTypeIcon],
         * which is the preferred class going forward.
         */
        private val MOBILE_NETWORK_TYPE_ICONS: Map<String, MobileNetworkTypeIcon>

        init {
            // Build up the mapping from the old implementation to the new one.
            val tempMap: MutableMap<String, MobileNetworkTypeIcon> = mutableMapOf()

            ICON_NAME_TO_ICON.forEach { (_, mobileIconGroup) ->
                tempMap[mobileIconGroup.name] = mobileIconGroup.toNetworkTypeIcon()
            }

            MOBILE_NETWORK_TYPE_ICONS = tempMap
        }

        /**
         * A converter function between the old mobile network type icon implementation and the new
         * one. Given an instance of the old class [mobileIconGroup], outputs an instance of the
         * new class [MobileNetworkTypeIcon].
         */
        @JvmStatic
        fun getNetworkTypeIcon(
            mobileIconGroup: SignalIcon.MobileIconGroup
        ): MobileNetworkTypeIcon {
            return MOBILE_NETWORK_TYPE_ICONS[mobileIconGroup.name]
                ?: mobileIconGroup.toNetworkTypeIcon()
        }

        private fun SignalIcon.MobileIconGroup.toNetworkTypeIcon(): MobileNetworkTypeIcon {
            return MobileNetworkTypeIcon(
                name = this.name,
                iconResId = this.dataType,
                contentDescriptionResId = this.dataContentDescription
            )
        }
    }
}
