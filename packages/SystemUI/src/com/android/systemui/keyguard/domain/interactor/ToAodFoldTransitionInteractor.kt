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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import android.view.ViewGroup
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.shade.ShadeFoldAnimator
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class ToAodFoldTransitionInteractor
@Inject
constructor(
    private val keyguardClockInteractor: KeyguardClockInteractor,
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val transitionRepository: KeyguardTransitionRepository,
    @Application private val mainScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {
    private var parentAnimator: NotificationPanelViewController.ShadeFoldAnimatorImpl? = null

    // TODO(b/331770313): Migrate to PowerInteractor; Deprecate ShadeFoldAnimator again
    val foldAnimator =
        object : ShadeFoldAnimator {
            override val view: ViewGroup?
                get() = throw NotImplementedError("Deprecated. Do not call.")

            override fun prepareFoldToAodAnimation() {
                forceToAod()
                parentAnimator?.prepareFoldToAodAnimation()
            }

            override fun startFoldToAodAnimation(
                startAction: Runnable,
                endAction: Runnable,
                cancelAction: Runnable
            ) {
                parentAnimator?.let {
                    it.buildViewAnimator(startAction, endAction, cancelAction)
                        .setUpdateListener {
                            keyguardClockInteractor.animateFoldToAod(it.animatedFraction)
                        }
                        .start()
                }
            }

            override fun cancelFoldToAodAnimation() {
                parentAnimator?.cancelFoldToAodAnimation()
            }
        }

    fun initialize(parentAnimator: ShadeFoldAnimator) {
        this.parentAnimator =
            parentAnimator as? NotificationPanelViewController.ShadeFoldAnimatorImpl?
    }

    /** Forces the keyguard into AOD or Doze */
    private fun forceToAod() {
        mainScope.launch(mainDispatcher) {
            transitionRepository.startTransition(
                TransitionInfo(
                    "$TAG (Fold transition triggered)",
                    transitionInteractor.getCurrentState(),
                    transitionInteractor.asleepKeyguardState.value,
                    ValueAnimator().apply { duration = 0 },
                    TransitionModeOnCanceled.LAST_VALUE,
                )
            )
        }
    }

    companion object {
        private val TAG = ToAodFoldTransitionInteractor::class.simpleName!!
    }
}
