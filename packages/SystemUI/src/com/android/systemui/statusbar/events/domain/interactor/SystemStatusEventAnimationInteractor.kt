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

package com.android.systemui.statusbar.events.domain.interactor

import android.view.View
import androidx.core.animation.Animator
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.events.data.repository.SystemStatusEventAnimationRepository
import com.android.systemui.statusbar.phone.fragment.StatusBarSystemEventDefaultAnimator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for dealing with system status event animations. This class can be used to monitor the
 * current [animationState], and defines some common animation functions that an handle hiding
 * system chrome in order to make space for the event chips
 */
@SysUISingleton
class SystemStatusEventAnimationInteractor
@Inject
constructor(
    repo: SystemStatusEventAnimationRepository,
    configurationInteractor: ConfigurationInteractor,
    @Application scope: CoroutineScope,
) {
    private val chipAnimateInTranslationX =
        configurationInteractor
            .dimensionPixelSize(R.dimen.ongoing_appops_chip_animation_in_status_bar_translation_x)
            .stateIn(scope, SharingStarted.Eagerly, 0)

    private val chipAnimateOutTranslationX =
        configurationInteractor
            .dimensionPixelSize(R.dimen.ongoing_appops_chip_animation_out_status_bar_translation_x)
            .stateIn(scope, SharingStarted.Eagerly, 0)

    val animationState = repo.animationState

    private fun getDefaultStatusBarAnimationForChipEnter(
        setX: (Float) -> Unit,
        setAlpha: (Float) -> Unit,
    ): Animator {
        return StatusBarSystemEventDefaultAnimator.getDefaultStatusBarAnimationForChipEnter(
            chipAnimateInTranslationX.value,
            setX,
            setAlpha,
        )
    }

    private fun getDefaultStatusBarAnimationForChipExit(
        setX: (Float) -> Unit,
        setAlpha: (Float) -> Unit,
    ): Animator {
        return StatusBarSystemEventDefaultAnimator.getDefaultStatusBarAnimationForChipExit(
            chipAnimateOutTranslationX.value,
            setX,
            setAlpha,
        )
    }

    fun animateStatusBarContentForChipEnter(v: View) {
        getDefaultStatusBarAnimationForChipEnter(setX = v::setTranslationX, setAlpha = v::setAlpha)
            .start()
    }

    fun animateStatusBarContentForChipExit(v: View) {
        v.translationX = chipAnimateOutTranslationX.value.toFloat()
        getDefaultStatusBarAnimationForChipExit(setX = v::setTranslationX, setAlpha = v::setAlpha)
            .start()
    }
}
