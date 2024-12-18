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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import androidx.annotation.FloatRange
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.shade.shared.model.ShadeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Business logic for shade interactions. */
interface ShadeInteractor : BaseShadeInteractor {
    /** Emits true if the Notifications shade is currently allowed and false otherwise. */
    val isShadeEnabled: StateFlow<Boolean>

    /** Emits true if QS shade is currently allowed and false otherwise. */
    val isQsEnabled: StateFlow<Boolean>

    /** Whether either the Notifications shade or QS shade is fully expanded. */
    val isAnyFullyExpanded: StateFlow<Boolean>

    /** Whether the Notifications Shade is fully expanded. */
    val isShadeFullyExpanded: Flow<Boolean>

    /** Whether Notifications Shade is expanded a non-zero amount. */
    val isShadeAnyExpanded: StateFlow<Boolean>

    /** Whether the Notifications Shade is fully collapsed. */
    val isShadeFullyCollapsed: Flow<Boolean>

    /**
     * Whether the user is expanding or collapsing either the shade or quick settings with user
     * input (i.e. dragging a pointer). This will be true even if the user's input gesture had ended
     * but a transition they initiated is still animating.
     */
    val isUserInteracting: StateFlow<Boolean>

    /** Are touches allowed on the notification panel? */
    val isShadeTouchable: Flow<Boolean>

    /** Whether the shade can be expanded from QQS to QS. */
    val isExpandToQsEnabled: Flow<Boolean>

    /**
     * The version of the shade layout to use.
     *
     * Note: Most likely, you want to read [isShadeLayoutWide] instead of this.
     */
    val shadeMode: StateFlow<ShadeMode>

    /**
     * Whether the shade layout should be wide (true) or narrow (false).
     *
     * In a wide layout, notifications and quick settings each take up only half the screen width
     * (whether they are shown at the same time or not). In a narrow layout, they can each be as
     * wide as the entire screen.
     */
    val isShadeLayoutWide: StateFlow<Boolean>

    /**
     * The fraction between [0..1] (i.e., percentage) of screen width to consider the threshold
     * between "top-left" and "top-right" for the purposes of dual-shade invocation.
     *
     * When the dual-shade is not wide, this always returns 0.5 (the top edge is evenly split). On
     * wide layouts however, a larger fraction is returned because only the area of the system
     * status icons is considered top-right.
     *
     * Note that this fraction only determines the split between the absolute left and right
     * directions. In RTL layouts, the "top-start" edge will resolve to "top-right", and "top-end"
     * will resolve to "top-left".
     */
    @FloatRange(from = 0.0, to = 1.0) fun getTopEdgeSplitFraction(): Float
}

/** ShadeInteractor methods with implementations that differ between non-empty impls. */
interface BaseShadeInteractor {
    /** The amount [0-1] either QS or the shade has been opened. */
    val anyExpansion: StateFlow<Float>

    /**
     * Whether either the shade or QS is partially or fully expanded, i.e. not fully collapsed. At
     * this time, this is not simply a matter of checking if either value in shadeExpansion and
     * qsExpansion is greater than zero, because it includes the legacy concept of whether input
     * transfer is about to occur. If the scene container flag is enabled, it just checks whether
     * either expansion value is positive.
     *
     * TODO(b/300258424) remove all but the first sentence of this comment
     */
    val isAnyExpanded: StateFlow<Boolean>

    /** The amount [0-1] that the Notifications Shade has been opened. */
    val shadeExpansion: StateFlow<Float>

    /**
     * The amount [0-1] QS has been opened. Normal shade with notifications (QQS) visible will
     * report 0f. If split shade is enabled, value matches shadeExpansion.
     */
    val qsExpansion: StateFlow<Float>

    /** Whether Quick Settings Shade is expanded a non-zero amount. */
    val isQsExpanded: StateFlow<Boolean>

    /**
     * Emits true whenever Quick Settings is being expanded without first expanding the Shade or if
     * if Quick Settings is being collapsed without first collapsing to shade, i.e. expanding with
     * 2-finger swipe or collapsing by flinging from the bottom of the screen. This concept was
     * previously called "expand immediate" in the legacy codebase.
     */
    val isQsBypassingShade: Flow<Boolean>

    /**
     * Emits true when QS is displayed over the entire screen of the device. Currently, this only
     * happens on phones that are not unfolded when QS expansion is equal to 1.
     */
    val isQsFullscreen: Flow<Boolean>

    /**
     * Whether the user is expanding or collapsing the shade with user input. This will be true even
     * if the user's input gesture has ended but a transition they initiated is animating.
     */
    val isUserInteractingWithShade: Flow<Boolean>

    /**
     * Whether the user is expanding or collapsing quick settings with user input. This will be true
     * even if the user's input gesture has ended but a transition they initiated is still
     * animating.
     */
    val isUserInteractingWithQs: Flow<Boolean>

    /**
     * Triggers the expansion (opening) of the notifications shade. If it is already expanded, this
     * has no effect.
     */
    fun expandNotificationsShade(loggingReason: String, transitionKey: TransitionKey? = null)

    /**
     * Triggers the expansion (opening) of the quick settings shade. If it is already expanded, this
     * has no effect.
     */
    fun expandQuickSettingsShade(loggingReason: String, transitionKey: TransitionKey? = null)

    /**
     * Triggers the collapse (closing) of the notifications shade. If it is already collapsed, this
     * has no effect.
     */
    fun collapseNotificationsShade(loggingReason: String, transitionKey: TransitionKey? = null)

    /**
     * Triggers the collapse (closing) of the quick settings shade. If it is already collapsed, this
     * has no effect.
     */
    fun collapseQuickSettingsShade(
        loggingReason: String,
        transitionKey: TransitionKey? = null,
        bypassNotificationsShade: Boolean = false,
    )

    /**
     * Triggers the collapse (closing) of the notifications shade or quick settings shade, whichever
     * is open. If both are already collapsed, this has no effect.
     */
    fun collapseEitherShade(loggingReason: String, transitionKey: TransitionKey? = null)
}

fun createAnyExpansionFlow(
    scope: CoroutineScope,
    shadeExpansion: Flow<Float>,
    qsExpansion: Flow<Float>,
): StateFlow<Float> {
    return combine(shadeExpansion, qsExpansion) { shadeExp, qsExp -> maxOf(shadeExp, qsExp) }
        .stateIn(scope, SharingStarted.Eagerly, 0f)
}
