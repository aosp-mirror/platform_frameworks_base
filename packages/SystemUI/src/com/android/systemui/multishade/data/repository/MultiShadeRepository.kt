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

package com.android.systemui.multishade.data.repository

import android.content.Context
import androidx.annotation.FloatRange
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.multishade.data.model.MultiShadeInteractionModel
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeConfig
import com.android.systemui.multishade.shared.model.ShadeId
import com.android.systemui.multishade.shared.model.ShadeModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates application state for all shades. */
@SysUISingleton
class MultiShadeRepository
@Inject
constructor(
    @Application private val applicationContext: Context,
    inputProxy: MultiShadeInputProxy,
) {
    /**
     * Remote input coming from sources outside of system UI (for example, swiping down on the
     * Launcher or from the status bar).
     */
    val proxiedInput: Flow<ProxiedInputModel> = inputProxy.proxiedInput

    /** Width of the left-hand side shade, in pixels. */
    private val leftShadeWidthPx =
        applicationContext.resources.getDimensionPixelSize(R.dimen.left_shade_width)

    /** Width of the right-hand side shade, in pixels. */
    private val rightShadeWidthPx =
        applicationContext.resources.getDimensionPixelSize(R.dimen.right_shade_width)

    /**
     * The amount that the user must swipe up when the shade is fully expanded to automatically
     * collapse once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully expanded once the user stops swiping.
     *
     * This is a fraction between `0` and `1`.
     */
    private val swipeCollapseThreshold =
        checkInBounds(applicationContext.resources.getFloat(R.dimen.shade_swipe_collapse_threshold))

    /**
     * The amount that the user must swipe down when the shade is fully collapsed to automatically
     * expand once the user lets go of the shade. If the user swipes less than this amount, the
     * shade will automatically revert back to fully collapsed once the user stops swiping.
     *
     * This is a fraction between `0` and `1`.
     */
    private val swipeExpandThreshold =
        checkInBounds(applicationContext.resources.getFloat(R.dimen.shade_swipe_expand_threshold))

    /**
     * Maximum opacity when the scrim that shows up behind the dual shades is fully visible.
     *
     * This is a fraction between `0` and `1`.
     */
    private val dualShadeScrimAlpha =
        checkInBounds(applicationContext.resources.getFloat(R.dimen.dual_shade_scrim_alpha))

    /** The current configuration of the shade system. */
    val shadeConfig: StateFlow<ShadeConfig> =
        MutableStateFlow(
                if (applicationContext.resources.getBoolean(R.bool.dual_shade_enabled)) {
                    ShadeConfig.DualShadeConfig(
                        leftShadeWidthPx = leftShadeWidthPx,
                        rightShadeWidthPx = rightShadeWidthPx,
                        swipeCollapseThreshold = swipeCollapseThreshold,
                        swipeExpandThreshold = swipeExpandThreshold,
                        splitFraction =
                            applicationContext.resources.getFloat(
                                R.dimen.dual_shade_split_fraction
                            ),
                        scrimAlpha = dualShadeScrimAlpha,
                    )
                } else {
                    ShadeConfig.SingleShadeConfig(
                        swipeCollapseThreshold = swipeCollapseThreshold,
                        swipeExpandThreshold = swipeExpandThreshold,
                    )
                }
            )
            .asStateFlow()

    private val _forceCollapseAll = MutableStateFlow(false)
    /** Whether all shades should be collapsed. */
    val forceCollapseAll: StateFlow<Boolean> = _forceCollapseAll.asStateFlow()

    private val _shadeInteraction = MutableStateFlow<MultiShadeInteractionModel?>(null)
    /** The current shade interaction or `null` if no shade is interacted with currently. */
    val shadeInteraction: StateFlow<MultiShadeInteractionModel?> = _shadeInteraction.asStateFlow()

    private val stateByShade = mutableMapOf<ShadeId, MutableStateFlow<ShadeModel>>()

    /** The model for the shade with the given ID. */
    fun getShade(
        shadeId: ShadeId,
    ): StateFlow<ShadeModel> {
        return getMutableShade(shadeId).asStateFlow()
    }

    /** Sets the expansion amount for the shade with the given ID. */
    fun setExpansion(
        shadeId: ShadeId,
        @FloatRange(from = 0.0, to = 1.0) expansion: Float,
    ) {
        getMutableShade(shadeId).let { mutableState ->
            mutableState.value = mutableState.value.copy(expansion = expansion)
        }
    }

    /** Sets whether all shades should be immediately forced to collapse. */
    fun setForceCollapseAll(isForced: Boolean) {
        _forceCollapseAll.value = isForced
    }

    /** Sets the current shade interaction; use `null` if no shade is interacted with currently. */
    fun setShadeInteraction(shadeInteraction: MultiShadeInteractionModel?) {
        _shadeInteraction.value = shadeInteraction
    }

    private fun getMutableShade(id: ShadeId): MutableStateFlow<ShadeModel> {
        return stateByShade.getOrPut(id) { MutableStateFlow(ShadeModel(id)) }
    }

    /** Asserts that the given [Float] is in the range of `0` and `1`, inclusive. */
    private fun checkInBounds(float: Float): Float {
        check(float in 0f..1f) { "$float isn't between 0 and 1." }
        return float
    }
}
