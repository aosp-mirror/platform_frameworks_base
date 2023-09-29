package com.android.systemui.qs.tiles.viewmodel

import android.service.quicksettings.Tile
import com.android.systemui.common.shared.model.Icon

/**
 * Represents current a state of the tile to be displayed in on the view. Consider using
 * [QSTileState.build] for better state creation experience and preset default values for certain
 * fields.
 *
 * // TODO(b/http://b/299909989): Clean up legacy mappings after the transition
 */
data class QSTileState(
    val icon: () -> Icon,
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

        fun build(icon: () -> Icon, label: CharSequence, build: Builder.() -> Unit): QSTileState =
            Builder(icon, label).apply(build).build()

        fun build(icon: Icon, label: CharSequence, build: Builder.() -> Unit): QSTileState =
            build({ icon }, label, build)
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
        INACTIVE(Tile.STATE_INACTIVE),
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
        LONG_CLICK,
    }

    sealed interface SideViewIcon {
        data class Custom(val icon: Icon) : SideViewIcon
        data object Chevron : SideViewIcon
        data object None : SideViewIcon
    }

    class Builder(
        var icon: () -> Icon,
        var label: CharSequence,
    ) {
        var activationState: ActivationState = ActivationState.INACTIVE
        var secondaryLabel: CharSequence? = null
        var supportedActions: Set<UserAction> = setOf(UserAction.CLICK)
        var contentDescription: CharSequence? = null
        var stateDescription: CharSequence? = null
        var sideViewIcon: SideViewIcon = SideViewIcon.None
        var enabledState: EnabledState = EnabledState.ENABLED
        var expandedAccessibilityClassName: String? = null

        fun build(): QSTileState =
            QSTileState(
                icon,
                label,
                activationState,
                secondaryLabel,
                supportedActions,
                contentDescription,
                stateDescription,
                sideViewIcon,
                enabledState,
                expandedAccessibilityClassName,
            )
    }
}
