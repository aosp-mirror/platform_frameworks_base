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

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import android.util.MathUtils
import android.view.View.VISIBLE
import com.android.app.animation.Interpolators
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.plugins.ClockController
import com.android.systemui.res.R
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardRootViewModel
@Inject
constructor(
    private val context: Context,
    private val keyguardInteractor: KeyguardInteractor,
    private val burnInInteractor: BurnInInteractor,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {

    data class PreviewMode(val isInPreviewMode: Boolean = false)

    /**
     * Whether this view-model instance is powering the preview experience that renders exclusively
     * in the wallpaper picker application. This should _always_ be `false` for the real lock screen
     * experience.
     */
    private val previewMode = MutableStateFlow(PreviewMode())

    var clockControllerProvider: Provider<ClockController>? = null

    /** System insets that keyguard needs to stay out of */
    var topInset: Int = 0
    /** Status view top, without translation added in */
    var statusViewTop: Int = 0

    val burnInLayerVisibility: Flow<Int> =
        keyguardTransitionInteractor.startedKeyguardState
            .filter { it == AOD || it == LOCKSCREEN }
            .map { VISIBLE }

    val goneToAodTransition = keyguardTransitionInteractor.goneToAodTransition

    /** An observable for the alpha level for the entire keyguard root view. */
    val alpha: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(1f)
            } else {
                keyguardInteractor.keyguardAlpha.distinctUntilChanged()
            }
        }

    private fun burnIn(): Flow<BurnInModel> {
        val dozingAmount: Flow<Float> =
            merge(
                keyguardTransitionInteractor.goneToAodTransition.map { it.value },
                keyguardTransitionInteractor.dozeAmountTransition.map { it.value },
            )

        return combine(dozingAmount, burnInInteractor.keyguardBurnIn) { dozeAmount, burnIn ->
            val interpolation = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(dozeAmount)
            val useScaleOnly =
                clockControllerProvider?.get()?.config?.useAlternateSmartspaceAODTransition ?: false
            if (useScaleOnly) {
                BurnInModel(
                    translationX = 0,
                    translationY = 0,
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolation),
                )
            } else {
                // Ensure the desired translation doesn't encroach on the top inset
                val burnInY = MathUtils.lerp(0, burnIn.translationY, interpolation).toInt()
                val translationY = -(statusViewTop - Math.max(topInset, statusViewTop + burnInY))
                BurnInModel(
                    translationX = MathUtils.lerp(0, burnIn.translationX, interpolation).toInt(),
                    translationY = translationY,
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolation),
                    scaleClockOnly = true,
                )
            }
        }
    }

    /** Specific alpha value for elements visible during [KeyguardState.LOCKSCREEN] */
    val lockscreenStateAlpha: Flow<Float> = aodToLockscreenTransitionViewModel.lockscreenAlpha

    /** For elements that appear and move during the animation -> AOD */
    val burnInLayerAlpha: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(1f)
            } else {
                goneToAodTransitionViewModel.enterFromTopAnimationAlpha
            }
        }

    val translationY: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(0f)
            } else {
                keyguardInteractor.configurationChange.flatMapLatest { _ ->
                    val enterFromTopAmount =
                        context.resources.getDimensionPixelSize(
                            R.dimen.keyguard_enter_from_top_translation_y
                        )
                    combine(
                        keyguardInteractor.keyguardTranslationY.onStart { emit(0f) },
                        burnIn().map { it.translationY.toFloat() }.onStart { emit(0f) },
                        goneToAodTransitionViewModel
                            .enterFromTopTranslationY(enterFromTopAmount)
                            .onStart { emit(0f) },
                    ) { keyguardTransitionY, burnInTranslationY, goneToAodTransitionTranslationY ->
                        // All 3 values need to be combined for a smooth translation
                        keyguardTransitionY + burnInTranslationY + goneToAodTransitionTranslationY
                    }
                }
            }
        }

    val translationX: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(0f)
            } else {
                burnIn().map { it.translationX.toFloat() }
            }
        }

    val scale: Flow<Pair<Float, Boolean>> =
        previewMode.flatMapLatest { previewMode ->
            burnIn().map {
                val scale = if (previewMode.isInPreviewMode) 1f else it.scale
                Pair(scale, it.scaleClockOnly)
            }
        }

    /**
     * Puts this view-model in "preview mode", which means it's being used for UI that is rendering
     * the lock screen preview in wallpaper picker / settings and not the real experience on the
     * lock screen.
     */
    fun enablePreviewMode() {
        previewMode.value = PreviewMode(true)
    }

    fun onSharedNotificationContainerPositionChanged(top: Float, bottom: Float) {
        // Notifications should not be visible in preview mode
        if (previewMode.value.isInPreviewMode) {
            return
        }
        keyguardInteractor.sharedNotificationContainerPosition.value =
            SharedNotificationContainerPosition(top, bottom)
    }
}
