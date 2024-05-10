/*
 *  Copyright (C) 2023 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import androidx.annotation.DimenRes
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business-logic related to Ambient Display burn-in offsets. */
@ExperimentalCoroutinesApi
@SysUISingleton
class BurnInInteractor
@Inject
constructor(
    private val context: Context,
    private val burnInHelperWrapper: BurnInHelperWrapper,
    @Application private val scope: CoroutineScope,
    private val configurationRepository: ConfigurationRepository,
    private val keyguardInteractor: KeyguardInteractor,
) {
    val deviceEntryIconXOffset: StateFlow<Int> =
        burnInOffsetDefinedInPixels(R.dimen.udfps_burn_in_offset_x, isXAxis = true)
    val deviceEntryIconYOffset: StateFlow<Int> =
        burnInOffsetDefinedInPixels(R.dimen.udfps_burn_in_offset_y, isXAxis = false)
    val udfpsProgress: StateFlow<Float> =
        keyguardInteractor.dozeTimeTick
            .mapLatest { burnInHelperWrapper.burnInProgressOffset() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                burnInHelperWrapper.burnInProgressOffset()
            )

    val keyguardBurnIn: Flow<BurnInModel> =
        combine(
                burnInOffset(R.dimen.burn_in_prevention_offset_x, isXAxis = true),
                burnInOffset(R.dimen.burn_in_prevention_offset_y, isXAxis = false).map {
                    it * 2 -
                        context.resources.getDimensionPixelSize(R.dimen.burn_in_prevention_offset_y)
                }
            ) { translationX, translationY ->
                BurnInModel(translationX, translationY, burnInHelperWrapper.burnInScale())
            }
            .distinctUntilChanged()

    /**
     * Use for max burn-in offsets that are NOT specified in pixels. This flow will recalculate the
     * max burn-in offset on any configuration changes. If the max burn-in offset is specified in
     * pixels, use [burnInOffsetDefinedInPixels].
     */
    private fun burnInOffset(
        @DimenRes maxBurnInOffsetResourceId: Int,
        isXAxis: Boolean,
    ): StateFlow<Int> {
        return configurationRepository.onAnyConfigurationChange
            .flatMapLatest {
                val maxBurnInOffsetPixels =
                    context.resources.getDimensionPixelSize(maxBurnInOffsetResourceId)
                keyguardInteractor.dozeTimeTick.mapLatest {
                    calculateOffset(maxBurnInOffsetPixels, isXAxis)
                }
            }
            .stateIn(
                scope,
                SharingStarted.Lazily,
                calculateOffset(
                    context.resources.getDimensionPixelSize(maxBurnInOffsetResourceId),
                    isXAxis,
                )
            )
    }

    /**
     * Use for max burn-in offBurn-in offsets that ARE specified in pixels. This flow will apply the
     * a scale for any resolution changes. If the max burn-in offset is specified in dp, use
     * [burnInOffset].
     */
    private fun burnInOffsetDefinedInPixels(
        @DimenRes maxBurnInOffsetResourceId: Int,
        isXAxis: Boolean,
    ): StateFlow<Int> {
        return configurationRepository.scaleForResolution
            .flatMapLatest { scale ->
                val maxBurnInOffsetPixels =
                    context.resources.getDimensionPixelSize(maxBurnInOffsetResourceId)
                keyguardInteractor.dozeTimeTick.mapLatest {
                    calculateOffset(maxBurnInOffsetPixels, isXAxis, scale)
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                calculateOffset(
                    context.resources.getDimensionPixelSize(maxBurnInOffsetResourceId),
                    isXAxis,
                    configurationRepository.getResolutionScale(),
                )
            )
    }

    private fun calculateOffset(
        maxBurnInOffsetPixels: Int,
        isXAxis: Boolean,
        scale: Float = 1f
    ): Int {
        return (burnInHelperWrapper.burnInOffset(maxBurnInOffsetPixels, isXAxis) * scale).toInt()
    }
}
