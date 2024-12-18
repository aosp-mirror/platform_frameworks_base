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

import android.animation.FloatEvaluator
import android.animation.IntEvaluator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.hideAffordancesRequest
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Encapsulates business logic for transitions between UDFPS states on the keyguard. */
@ExperimentalCoroutinesApi
@SysUISingleton
class UdfpsKeyguardInteractor
@Inject
constructor(
    burnInInteractor: BurnInInteractor,
    keyguardInteractor: KeyguardInteractor,
    shadeInteractor: ShadeInteractor,
    shadeLockscreenInteractor: ShadeLockscreenInteractor,
    dialogManager: SystemUIDialogManager,
) {
    private val intEvaluator = IntEvaluator()
    private val floatEvaluator = FloatEvaluator()

    val dozeAmount = keyguardInteractor.dozeAmount

    /** Burn-in offsets for the UDFPS view to mitigate burn-in on AOD. */
    val burnInOffsets: Flow<Offsets> =
        combine(
            keyguardInteractor.dozeAmount,
            burnInInteractor.deviceEntryIconXOffset,
            burnInInteractor.deviceEntryIconYOffset,
            burnInInteractor.udfpsProgress
        ) { dozeAmount, fullyDozingBurnInX, fullyDozingBurnInY, fullyDozingBurnInProgress ->
            Offsets(
                intEvaluator.evaluate(dozeAmount, 0, fullyDozingBurnInX),
                intEvaluator.evaluate(dozeAmount, 0, fullyDozingBurnInY),
                floatEvaluator.evaluate(dozeAmount, 0, fullyDozingBurnInProgress),
            )
        }

    val dialogHideAffordancesRequest: Flow<Boolean> = dialogManager.hideAffordancesRequest

    val qsProgress: Flow<Float> =
        shadeInteractor.qsExpansion // swipe from top of LS
            .map { (it * 2).coerceIn(0f, 1f) }
            .onStart { emit(0f) }

    val shadeExpansion: Flow<Float> =
        combine(
                shadeLockscreenInteractor
                    .udfpsTransitionToFullShadeProgress, // swipe from middle of LS
                keyguardInteractor.statusBarState, // quick swipe from middle of LS
            ) { shadeProgress, statusBarState ->
                if (statusBarState == StatusBarState.SHADE_LOCKED) {
                    1f
                } else {
                    shadeProgress
                }
            }
            .onStart { emit(0f) }
}

data class Offsets(
    val x: Int, // current x burn in offset based on the aodTransitionAmount
    val y: Int, // current y burn in offset based on the aodTransitionAmount
    val progress: Float, // current progress based on the aodTransitionAmount
)
