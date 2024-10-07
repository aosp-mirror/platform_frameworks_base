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

package com.android.systemui.qs.tiles.viewmodel

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.service.quicksettings.Tile
import android.view.View
import android.widget.Switch
import com.android.systemui.common.shared.model.Icon
import kotlin.reflect.KClass

/**
 * Represents current a state of the tile to be displayed in on the view. Consider using
 * [QSTileState.build] for better state creation experience and preset default values for certain
 * fields.
 *
 * @param iconRes For when we want to have Loaded icon, but still keep a reference to the resource
 *   id. A use case would be for tests that have to compare animated drawables.
 *
 * // TODO(b/http://b/299909989): Clean up legacy mappings after the transition
 */
data class QSTileState(
    val icon: () -> Icon?,
    val iconRes: Int?,
    val label: CharSequence,
    val activationState: ActivationState,
    val secondaryLabel: CharSequence?,
    val supportedActions: Set<UserAction>,
    val contentDescription: CharSequence?,
    val stateDescription: CharSequence?,
    val sideViewIcon: SideViewIcon,
    val enabledState: EnabledState,
    val expandedAccessibilityClassName: String?,
) {

    companion object {

        fun build(
            resources: Resources,
            theme: Theme,
            config: QSTileUIConfig,
            builder: Builder.() -> Unit
        ): QSTileState {
            val iconDrawable = resources.getDrawable(config.iconRes, theme)
            return build(
                { Icon.Loaded(iconDrawable, null) },
                resources.getString(config.labelRes),
                builder,
            )
        }

        fun build(
            icon: () -> Icon?,
            label: CharSequence,
            builder: Builder.() -> Unit
        ): QSTileState = Builder(icon, label).apply { builder() }.build()
    }

    enum class ActivationState(val legacyState: Int) {
        // An unavailable state indicates that for some reason this tile is not currently available
        // to the user, and will have no click action. The tile's icon will be tinted differently to
        // reflect this state.
        UNAVAILABLE(Tile.STATE_UNAVAILABLE),
        // This represents a tile that is currently active. (e.g. wifi is connected, bluetooth is
        // on, cast is casting). This is the default state.
        ACTIVE(Tile.STATE_ACTIVE),
        // This represents a tile that is currently in a disabled state but is still interactable. A
        // disabled state indicates that the tile is not currently active (e.g. wifi disconnected or
        // bluetooth disabled), but is still interactable by the user to modify this state.
        INACTIVE(Tile.STATE_INACTIVE);

        companion object {
            fun valueOf(legacyState: Int): ActivationState =
                when (legacyState) {
                    Tile.STATE_ACTIVE -> ACTIVE
                    Tile.STATE_INACTIVE -> INACTIVE
                    else -> UNAVAILABLE
                }
        }
    }

    /**
     * Enabled tile behaves as usual where is disabled one is frozen and inactive in its current
     * [ActivationState].
     */
    enum class EnabledState {
        ENABLED,
        DISABLED,
    }

    enum class UserAction {
        CLICK,
        TOGGLE_CLICK,
        LONG_CLICK,
    }

    sealed interface SideViewIcon {
        data class Custom(val icon: Icon) : SideViewIcon

        data object Chevron : SideViewIcon

        data object None : SideViewIcon
    }

    class Builder(
        var icon: () -> Icon?,
        var label: CharSequence,
    ) {
        var iconRes: Int? = null
        var activationState: ActivationState = ActivationState.INACTIVE
        var secondaryLabel: CharSequence? = null
        var supportedActions: Set<UserAction> = setOf(UserAction.CLICK)
        var contentDescription: CharSequence? = null
        var stateDescription: CharSequence? = null
        var sideViewIcon: SideViewIcon = SideViewIcon.None
        var enabledState: EnabledState = EnabledState.ENABLED
        var expandedAccessibilityClass: KClass<out View>? = Switch::class

        fun build(): QSTileState =
            QSTileState(
                icon,
                iconRes,
                label,
                activationState,
                secondaryLabel,
                supportedActions,
                contentDescription,
                stateDescription,
                sideViewIcon,
                enabledState,
                expandedAccessibilityClass?.qualifiedName,
            )
    }
}
