/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.domain.model

import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/**
 * A data wrapper class for [MobileIconGroup]. One lingering nuance of this pipeline is its
 * dependency on MobileMappings for its lookup from NetworkType -> NetworkTypeIcon. And because
 * MobileMappings is a static map of (netType, icon) that knows nothing of `carrierId`, we need the
 * concept of a "default" or "overridden" icon type.
 *
 * Until we can remove that dependency on MobileMappings, we should just allow for the composition
 * of overriding an icon id using the lookup defined in [MobileIconCarrierIdOverrides]. By using the
 * [overrideIcon] method defined below, we can create any arbitrarily overridden network type icon.
 */
sealed interface NetworkTypeIconModel : Diffable<NetworkTypeIconModel> {
    val contentDescription: Int
    val iconId: Int
    val name: String

    data class DefaultIcon(
        val iconGroup: MobileIconGroup,
    ) : NetworkTypeIconModel {
        override val contentDescription = iconGroup.dataContentDescription
        override val iconId = iconGroup.dataType
        override val name = iconGroup.name

        override fun logDiffs(prevVal: NetworkTypeIconModel, row: TableRowLogger) {
            if (prevVal !is DefaultIcon || prevVal.name != name) {
                row.logChange(COL_NETWORK_ICON, name)
            }
        }
    }

    data class OverriddenIcon(
        val iconGroup: MobileIconGroup,
        override val iconId: Int,
    ) : NetworkTypeIconModel {
        override val contentDescription = iconGroup.dataContentDescription
        override val name = iconGroup.name

        override fun logDiffs(prevVal: NetworkTypeIconModel, row: TableRowLogger) {
            if (prevVal !is OverriddenIcon || prevVal.name != name || prevVal.iconId != iconId) {
                row.logChange(COL_NETWORK_ICON, "Ovrd($name)")
            }
        }
    }

    companion object {
        const val COL_NETWORK_ICON = "networkTypeIcon"
    }
}
