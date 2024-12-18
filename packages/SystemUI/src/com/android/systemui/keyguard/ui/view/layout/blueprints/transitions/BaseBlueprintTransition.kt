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
 */

package com.android.systemui.keyguard.ui.view.layout.blueprints.transitions

import android.animation.Animator
import android.animation.ObjectAnimator
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.helper.widget.Layer
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView

class BaseBlueprintTransition(val clockViewModel: KeyguardClockViewModel) : TransitionSet() {
    init {
        ordering = ORDERING_SEQUENTIAL
        addTransition(AlphaOutVisibility())
            .addTransition(ChangeBounds())
            .addTransition(AlphaInVisibility())
        excludeTarget(Layer::class.java, /* exclude= */ true)
        excludeClockAndSmartspaceViews(this)
    }

    private fun excludeClockAndSmartspaceViews(transition: Transition) {
        transition.excludeTarget(SmartspaceView::class.java, true)
        clockViewModel.currentClock.value?.let { clock ->
            clock.largeClock.layout.views.forEach { view -> transition.excludeTarget(view, true) }
            clock.smallClock.layout.views.forEach { view -> transition.excludeTarget(view, true) }
        }
    }

    class AlphaOutVisibility : Visibility() {
        override fun onDisappear(
            sceneRoot: ViewGroup?,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator {
            return ObjectAnimator.ofFloat(view, "alpha", 0f).apply {
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    class AlphaInVisibility : Visibility() {
        override fun onAppear(
            sceneRoot: ViewGroup?,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator {
            return ObjectAnimator.ofFloat(view, "alpha", 1f).apply {
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }
}
