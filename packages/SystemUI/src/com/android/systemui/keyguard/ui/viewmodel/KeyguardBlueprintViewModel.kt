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

import android.os.Handler
import android.transition.Transition
import android.transition.TransitionManager
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransitionData(val config: Config, val start: Long = System.currentTimeMillis())

class KeyguardBlueprintViewModel
@Inject
constructor(
    @Main private val handler: Handler,
    private val keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    @KeyguardBlueprintLog private val blueprintLog: LogBuffer,
) {
    private val logger = Logger(blueprintLog, "KeyguardBlueprintViewModel")
    val blueprint = keyguardBlueprintInteractor.blueprint
    val blueprintId = keyguardBlueprintInteractor.blueprintId
    val refreshTransition = keyguardBlueprintInteractor.refreshTransition

    private val _currentTransition = MutableStateFlow<TransitionData?>(null)
    val currentTransition = _currentTransition.asStateFlow()

    private val runningTransitions = mutableSetOf<Transition>()
    private val transitionListener =
        object : Transition.TransitionListener {
            override fun onTransitionCancel(transition: Transition) {
                logger.w({ "onTransitionCancel: $str1" }) { str1 = transition::class.simpleName }
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionEnd(transition: Transition) {
                logger.i({ "onTransitionEnd: $str1" }) { str1 = transition::class.simpleName }
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionPause(transition: Transition) {
                logger.i({ "onTransitionPause: $str1" }) { str1 = transition::class.simpleName }
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionResume(transition: Transition) {
                logger.i({ "onTransitionResume: $str1" }) { str1 = transition::class.simpleName }
                updateTransitions(null) { add(transition) }
            }

            override fun onTransitionStart(transition: Transition) {
                logger.i({ "onTransitionStart: $str1" }) { str1 = transition::class.simpleName }
                updateTransitions(null) { add(transition) }
            }
        }

    fun refreshBlueprint(type: Type = Type.NoTransition) =
        keyguardBlueprintInteractor.refreshBlueprint(type)

    fun updateTransitions(data: TransitionData?, mutate: MutableSet<Transition>.() -> Unit) {
        runningTransitions.mutate()

        if (runningTransitions.size <= 0) _currentTransition.value = null
        else if (data != null) _currentTransition.value = data
    }

    fun runTransition(
        constraintLayout: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
        config: Config,
        apply: () -> Unit,
    ) {
        val newConfig =
            if (keyguardTransitionInteractor.getCurrentState() == KeyguardState.OFF) {
                config.copy(type = Type.Init)
            } else {
                config
            }

        runTransition(
            constraintLayout,
            IntraBlueprintTransition(newConfig, clockViewModel, smartspaceViewModel, blueprintLog),
            config,
            apply,
        )
    }

    fun runTransition(
        constraintLayout: ConstraintLayout,
        transition: Transition,
        config: Config,
        apply: () -> Unit,
    ) {
        val currentPriority = currentTransition.value?.let { it.config.type.priority } ?: -1
        if (config.checkPriority && config.type.priority < currentPriority) {
            logger.w({ "runTransition: skipping $str1: currentPriority=$int1; config=$str2" }) {
                str1 = transition::class.simpleName
                int1 = currentPriority
                str2 = "$config"
            }
            apply()
            return
        }

        // Don't allow transitions with animations while in OFF state
        val newConfig =
            if (keyguardTransitionInteractor.getCurrentState() == KeyguardState.OFF) {
                config.copy(type = Type.Init)
            } else {
                config
            }

        logger.i({ "runTransition: running $str1: currentPriority=$int1; config=$str2" }) {
            str1 = transition::class.simpleName
            int1 = currentPriority
            str2 = "$newConfig"
        }

        // beginDelayedTransition makes a copy, so we temporarially add the uncopied transition to
        // the running set until the copy is started by the handler.
        updateTransitions(TransitionData(newConfig)) { add(transition) }
        transition.addListener(transitionListener)

        handler.post {
            if (newConfig.terminatePrevious) {
                TransitionManager.endTransitions(constraintLayout)
            }

            TransitionManager.beginDelayedTransition(constraintLayout, transition)
            apply()

            // Delay removal until after copied transition has started
            handler.post { updateTransitions(null) { remove(transition) } }
        }
    }
}
