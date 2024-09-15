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

package com.android.systemui.qs

import android.service.quicksettings.Tile
import android.text.TextUtils
import android.widget.Switch
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.CustomTile
import com.android.systemui.qs.nano.QsTileState
import com.android.systemui.util.nano.ComponentNameProto

fun QSTile.State.toProto(): QsTileState? {
    if (TextUtils.isEmpty(spec)) return null
    val state = QsTileState()
    if (spec.startsWith(CustomTile.PREFIX)) {
        val protoComponentName = ComponentNameProto()
        val tileComponentName = CustomTile.getComponentFromSpec(spec)
        protoComponentName.packageName = tileComponentName.packageName
        protoComponentName.className = tileComponentName.className
        state.componentName = protoComponentName
    } else {
        state.spec = spec
    }
    state.state =
        when (this.state) {
            Tile.STATE_UNAVAILABLE -> QsTileState.UNAVAILABLE
            Tile.STATE_INACTIVE -> QsTileState.INACTIVE
            Tile.STATE_ACTIVE -> QsTileState.ACTIVE
            else -> QsTileState.UNAVAILABLE
        }
    label?.let { state.label = it.toString() }
    secondaryLabel?.let { state.secondaryLabel = it.toString() }
    if (expandedAccessibilityClassName == Switch::class.java.name) {
        state.booleanState = state.state == QsTileState.ACTIVE
    }
    return state
}
