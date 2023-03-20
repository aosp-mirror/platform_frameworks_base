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
 *
 */

package com.android.systemui.multishade.domain.interactor

import androidx.annotation.FloatRange
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.multishade.data.model.MultiShadeInteractionModel
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.data.repository.MultiShadeRepository
import com.android.systemui.multishade.shared.math.isZero
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeConfig
import com.android.systemui.multishade.shared.model.ShadeId
import com.android.systemui.multishade.shared.model.ShadeModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.yield

/** Encapsulates business logic related to interactions with the multi-shade system. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MultiShadeInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: MultiShadeRepository,
    private val inputProxy: MultiShadeInputProxy,
) {
    /** The current configuration of the shade system. */
    val shadeConfig: StateFlow<ShadeConfig> = repository.shadeConfig

    /** The expansion of the shade that's most expanded. */
    val maxShadeExpansion: Flow<Float> =
        repository.shadeConfig.flatMapLatest { shadeConfig ->
            combine(allShades(shadeConfig)) { shadeModels ->
                shadeModels.maxOfOrNull { it.expansion } ?: 0f
            }
        }

    /** Whether any shade is expanded, even a little bit. */
    val isAnyShadeExpanded: Flow<Boolean> =
        maxShadeExpansion.map { maxExpansion -> !maxExpansion.isZero() }.distinctUntilChanged()

    /**
     * A _processed_ version of the proxied input flow.
     *
     * All internal dependencies on the proxied input flow *must* use this one for two reasons:
     * 1. It's a [SharedFlow] so we only do the upstream work once, no matter how many usages we
     *    actually have.
     * 2. It actually does some preprocessing as the proxied input events stream through, handling
     *    common things like recording the current state of the system based on incoming input
     *    events.
     */
    private val processedProxiedInput: SharedFlow<ProxiedInputModel> =
        combine(
                repository.shadeConfig,
                repository.proxiedInput.distinctUntilChanged(),
                ::Pair,
            )
            .map { (shadeConfig, proxiedInput) ->
                if (proxiedInput !is ProxiedInputModel.OnTap) {
                    // If the user is interacting with any other gesture type (for instance,
                    // dragging),
                    // we no longer want to force collapse all shades.
                    repository.setForceCollapseAll(false)
                }

                when (proxiedInput) {
                    is ProxiedInputModel.OnDrag -> {
                        val affectedShadeId = affectedShadeId(shadeConfig, proxiedInput.xFraction)
                        // This might be the start of a new drag gesture, let's update our
                        // application
                        // state to record that fact.
                        onUserInteractionStarted(
                            shadeId = affectedShadeId,
                            isProxied = true,
                        )
                    }
                    is ProxiedInputModel.OnTap -> {
                        // Tapping outside any shade collapses all shades. This code path is not hit
                        // for
                        // taps that happen _inside_ a shade as that input event is directly applied
                        // through the UI and is, hence, not a proxied input.
                        collapseAll()
                    }
                    else -> Unit
                }

                proxiedInput
            }
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                replay = 1,
            )

    /** Whether the shade with the given ID should be visible. */
    fun isVisible(shadeId: ShadeId): Flow<Boolean> {
        return repository.shadeConfig.map { shadeConfig -> shadeConfig.shadeIds.contains(shadeId) }
    }

    /** Whether direct user input is allowed on the shade with the given ID. */
    fun isNonProxiedInputAllowed(shadeId: ShadeId): Flow<Boolean> {
        return combine(
                isForceCollapsed(shadeId),
                repository.shadeInteraction,
                ::Pair,
            )
            .map { (isForceCollapsed, shadeInteraction) ->
                !isForceCollapsed && shadeInteraction?.isProxied != true
            }
    }

    /** Whether the shade with the given ID is forced to collapse. */
    fun isForceCollapsed(shadeId: ShadeId): Flow<Boolean> {
        return combine(
                repository.forceCollapseAll,
                repository.shadeInteraction.map { it?.shadeId },
                ::Pair,
            )
            .map { (collapseAll, userInteractedShadeIdOrNull) ->
                val counterpartShadeIdOrNull =
                    when (shadeId) {
                        ShadeId.SINGLE -> null
                        ShadeId.LEFT -> ShadeId.RIGHT
                        ShadeId.RIGHT -> ShadeId.LEFT
                    }

                when {
                    // If all shades have been told to collapse (by a tap outside, for example),
                    // then this shade is collapsed.
                    collapseAll -> true
                    // A shade that doesn't have a counterpart shade cannot be force-collapsed by
                    // interactions on the counterpart shade.
                    counterpartShadeIdOrNull == null -> false
                    // If the current user interaction is on the counterpart shade, then this shade
                    // should be force-collapsed.
                    else -> userInteractedShadeIdOrNull == counterpartShadeIdOrNull
                }
            }
    }

    /**
     * Proxied input affecting the shade with the given ID. This is input coming from sources
     * outside of system UI (for example, swiping down on the Launcher or from the status bar) or
     * outside the UI of any shade (for example, the scrim that's shown behind the shades).
     */
    fun proxiedInput(shadeId: ShadeId): Flow<ProxiedInputModel?> {
        return combine(
                processedProxiedInput,
                isForceCollapsed(shadeId).distinctUntilChanged(),
                repository.shadeInteraction,
                ::Triple,
            )
            .map { (proxiedInput, isForceCollapsed, shadeInteraction) ->
                when {
                    // If the shade is force-collapsed, we ignored proxied input on it.
                    isForceCollapsed -> null
                    // If the proxied input does not belong to this shade, ignore it.
                    shadeInteraction?.shadeId != shadeId -> null
                    // If there is ongoing non-proxied user input on any shade, ignore the
                    // proxied input.
                    !shadeInteraction.isProxied -> null
                    // Otherwise, send the proxied input downstream.
                    else -> proxiedInput
                }
            }
            .onEach { proxiedInput ->
                // We use yield() to make sure that the following block of code happens _after_
                // downstream collectors had a chance to process the proxied input. Otherwise, we
                // might change our state to clear the current UserInteraction _before_ those
                // downstream collectors get a chance to process the proxied input, which will make
                // them ignore it (since they ignore proxied input when the current user interaction
                // doesn't match their shade).
                yield()

                if (
                    proxiedInput is ProxiedInputModel.OnDragEnd ||
                        proxiedInput is ProxiedInputModel.OnDragCancel
                ) {
                    onUserInteractionEnded(shadeId = shadeId, isProxied = true)
                }
            }
    }

    /** Sets the expansion amount for the shade with the given ID. */
    fun setExpansion(
        shadeId: ShadeId,
        @FloatRange(from = 0.0, to = 1.0) expansion: Float,
    ) {
        repository.setExpansion(shadeId, expansion)
    }

    /** Collapses all shades. */
    fun collapseAll() {
        repository.setForceCollapseAll(true)
    }

    /**
     * Notifies that a new non-proxied interaction may have started. Safe to call multiple times for
     * the same interaction as it won't overwrite an existing interaction.
     *
     * Existing interactions can be cleared by calling [onUserInteractionEnded].
     */
    fun onUserInteractionStarted(shadeId: ShadeId) {
        onUserInteractionStarted(
            shadeId = shadeId,
            isProxied = false,
        )
    }

    /**
     * Notifies that the current non-proxied interaction has ended.
     *
     * Safe to call multiple times, even if there's no current interaction or even if the current
     * interaction doesn't belong to the given shade or is proxied as the code is a no-op unless
     * there's a match between the parameters and the current interaction.
     */
    fun onUserInteractionEnded(
        shadeId: ShadeId,
    ) {
        onUserInteractionEnded(
            shadeId = shadeId,
            isProxied = false,
        )
    }

    fun sendProxiedInput(proxiedInput: ProxiedInputModel) {
        inputProxy.onProxiedInput(proxiedInput)
    }

    /**
     * Notifies that a new interaction may have started. Safe to call multiple times for the same
     * interaction as it won't overwrite an existing interaction.
     *
     * Existing interactions can be cleared by calling [onUserInteractionEnded].
     */
    private fun onUserInteractionStarted(
        shadeId: ShadeId,
        isProxied: Boolean,
    ) {
        if (repository.shadeInteraction.value != null) {
            return
        }

        repository.setShadeInteraction(
            MultiShadeInteractionModel(
                shadeId = shadeId,
                isProxied = isProxied,
            )
        )
    }

    /**
     * Notifies that the current interaction has ended.
     *
     * Safe to call multiple times, even if there's no current interaction or even if the current
     * interaction doesn't belong to the given shade or [isProxied] value as the code is a no-op
     * unless there's a match between the parameters and the current interaction.
     */
    private fun onUserInteractionEnded(
        shadeId: ShadeId,
        isProxied: Boolean,
    ) {
        repository.shadeInteraction.value?.let { (interactionShadeId, isInteractionProxied) ->
            if (shadeId == interactionShadeId && isProxied == isInteractionProxied) {
                repository.setShadeInteraction(null)
            }
        }
    }

    /**
     * Returns the ID of the shade that's affected by user input at a given coordinate.
     *
     * @param config The shade configuration being used.
     * @param xFraction The horizontal position of the user input as a fraction along the width of
     *   its container where `0` is all the way to the left and `1` is all the way to the right.
     */
    private fun affectedShadeId(
        config: ShadeConfig,
        @FloatRange(from = 0.0, to = 1.0) xFraction: Float,
    ): ShadeId {
        return if (config is ShadeConfig.DualShadeConfig) {
            if (xFraction <= config.splitFraction) {
                ShadeId.LEFT
            } else {
                ShadeId.RIGHT
            }
        } else {
            ShadeId.SINGLE
        }
    }

    /** Returns the list of flows of all the shades in the given configuration. */
    private fun allShades(
        config: ShadeConfig,
    ): List<Flow<ShadeModel>> {
        return config.shadeIds.map { shadeId -> repository.getShade(shadeId) }
    }
}
