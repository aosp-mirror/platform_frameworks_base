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

import android.util.MathUtils
import com.android.app.animation.Interpolators
import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.keyguard.domain.interactor.BurnInInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.BurnInModel
import com.android.systemui.keyguard.shared.model.KeyguardRootViewVisibilityState
import com.android.systemui.plugins.ClockController
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardRootViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val burnInInteractor: BurnInInteractor,
) {

    data class PreviewMode(val isInPreviewMode: Boolean = false)

    /**
     * Whether this view-model instance is powering the preview experience that renders exclusively
     * in the wallpaper picker application. This should _always_ be `false` for the real lock screen
     * experience.
     */
    private val previewMode = MutableStateFlow(PreviewMode())

    public var clockControllerProvider: Provider<ClockController>? = null

    /** Represents the current state of the KeyguardRootView visibility */
    val keyguardRootViewVisibilityState: Flow<KeyguardRootViewVisibilityState> =
        keyguardInteractor.keyguardRootViewVisibilityState

    /** An observable for the alpha level for the entire keyguard root view. */
    val alpha: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(1f)
            } else {
                keyguardInteractor.keyguardAlpha.distinctUntilChanged()
            }
        }

    private val burnIn: Flow<BurnInModel> =
        combine(keyguardInteractor.dozeAmount, burnInInteractor.keyguardBurnIn) { dozeAmount, burnIn
            ->
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
                BurnInModel(
                    translationX = MathUtils.lerp(0, burnIn.translationX, interpolation).toInt(),
                    translationY = MathUtils.lerp(0, burnIn.translationY, interpolation).toInt(),
                    scale = MathUtils.lerp(burnIn.scale, 1f, 1f - interpolation),
                    scaleClockOnly = true,
                )
            }
        }

    val translationY: Flow<Float> =
        merge(keyguardInteractor.keyguardTranslationY, burnIn.map { it.translationY.toFloat() })

    val translationX: Flow<Float> = burnIn.map { it.translationX.toFloat() }

    val scale: Flow<Pair<Float, Boolean>> = burnIn.map { Pair(it.scale, it.scaleClockOnly) }

    /**
     * Puts this view-model in "preview mode", which means it's being used for UI that is rendering
     * the lock screen preview in wallpaper picker / settings and not the real experience on the
     * lock screen.
     */
    fun enablePreviewMode() {
        val newPreviewMode = PreviewMode(true)
        previewMode.value = newPreviewMode
    }

    fun onSharedNotificationContainerPositionChanged(top: Float, bottom: Float) {
        keyguardInteractor.sharedNotificationContainerPosition.value =
            SharedNotificationContainerPosition(top, bottom)
    }
}
