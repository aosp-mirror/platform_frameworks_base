/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.res.Resources
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.widget.Switch
import androidx.compose.runtime.Immutable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tileimpl.SubtitleArrayMapping
import com.android.systemui.res.R
import java.util.function.Supplier

@Immutable
data class TileUiState(
    val label: String,
    val secondaryLabel: String,
    val state: Int,
    val handlesSecondaryClick: Boolean,
    val icon: Supplier<QSTile.Icon?>,
    val accessibilityUiState: AccessibilityUiState,
)

data class AccessibilityUiState(
    val contentDescription: String,
    val stateDescription: String,
    val accessibilityRole: Role,
    val toggleableState: ToggleableState? = null,
    val clickLabel: String? = null,
)

fun QSTile.State.toUiState(resources: Resources): TileUiState {
    val accessibilityRole =
        if (expandedAccessibilityClassName == Switch::class.java.name && !handlesSecondaryClick) {
            Role.Switch
        } else {
            Role.Button
        }
    // State handling and description
    val stateDescription = StringBuilder()
    val stateText =
        if (accessibilityRole == Role.Switch || state == Tile.STATE_UNAVAILABLE) {
            getStateText(resources)
        } else {
            ""
        }
    val secondaryLabel = getSecondaryLabel(stateText)
    if (!TextUtils.isEmpty(stateText)) {
        stateDescription.append(stateText)
    }
    if (disabledByPolicy && state != Tile.STATE_UNAVAILABLE) {
        stateDescription.append(", ")
        stateDescription.append(getUnavailableText(spec, resources))
    }
    if (
        !TextUtils.isEmpty(this.stateDescription) &&
            !stateDescription.contains(this.stateDescription!!)
    ) {
        stateDescription.append(", ")
        stateDescription.append(this.stateDescription)
    }
    val toggleableState =
        if (accessibilityRole == Role.Switch || handlesSecondaryClick) {
            ToggleableState(state == Tile.STATE_ACTIVE)
        } else {
            null
        }
    return TileUiState(
        label = label?.toString() ?: "",
        secondaryLabel = secondaryLabel?.toString() ?: "",
        state = if (disabledByPolicy) Tile.STATE_UNAVAILABLE else state,
        handlesSecondaryClick = handlesSecondaryClick,
        icon = icon?.let { Supplier { icon } } ?: iconSupplier ?: Supplier { null },
        AccessibilityUiState(
            contentDescription?.toString() ?: "",
            stateDescription.toString(),
            accessibilityRole,
            toggleableState,
            resources
                .getString(R.string.accessibility_tile_disabled_by_policy_action_description)
                .takeIf { disabledByPolicy },
        ),
    )
}

private fun QSTile.State.getStateText(resources: Resources): CharSequence {
    val arrayResId = SubtitleArrayMapping.getSubtitleId(spec)
    val array = resources.getStringArray(arrayResId)
    return array[state]
}

private fun getUnavailableText(spec: String?, resources: Resources): String {
    val arrayResId = SubtitleArrayMapping.getSubtitleId(spec)
    return resources.getStringArray(arrayResId)[Tile.STATE_UNAVAILABLE]
}
