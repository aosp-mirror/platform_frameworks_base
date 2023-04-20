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

package com.android.systemui.statusbar.connectivity

import android.annotation.DrawableRes
import android.content.Context
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl

/**
 * Cache for network type resource IDs.
 *
 * The default framework behavior is to have a statically defined icon per network type. See
 * [MobileIconGroup] for the standard mapping.
 *
 * For the case of carrierId-defined overrides, we want to check [MobileIconCarrierIdOverrides] for
 * an existing icon override, and cache the result of the operation
 */
class NetworkTypeResIdCache(
    private val overrides: MobileIconCarrierIdOverrides = MobileIconCarrierIdOverridesImpl()
) {
    @DrawableRes private var cachedResId: Int = 0
    private var lastCarrierId: Int? = null
    private var lastIconGroup: MobileIconGroup? = null
    private var isOverridden: Boolean = false

    @DrawableRes
    fun get(iconGroup: MobileIconGroup, carrierId: Int, context: Context): Int {
        if (lastCarrierId != carrierId || lastIconGroup != iconGroup) {
            lastCarrierId = carrierId
            lastIconGroup = iconGroup

            val maybeOverride = calculateOverriddenIcon(iconGroup, carrierId, context)
            if (maybeOverride > 0) {
                cachedResId = maybeOverride
                isOverridden = true
            } else {
                cachedResId = iconGroup.dataType
                isOverridden = false
            }
        }

        return cachedResId
    }

    override fun toString(): String {
        return "networkTypeResIdCache={id=$cachedResId, isOverridden=$isOverridden}"
    }

    @DrawableRes
    private fun calculateOverriddenIcon(
        iconGroup: MobileIconGroup,
        carrierId: Int,
        context: Context,
    ): Int {
        val name = iconGroup.name
        if (!overrides.carrierIdEntryExists(carrierId)) {
            return 0
        }

        return overrides.getOverrideFor(carrierId, name, context.resources)
    }
}
