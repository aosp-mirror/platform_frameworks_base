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
import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransitionData(
    val config: Config,
    val start: Long = System.currentTimeMillis(),
)

class KeyguardBlueprintViewModel
@Inject
constructor(
    @Main private val handler: Handler,
    keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
) {
    val blueprint = keyguardBlueprintInteractor.blueprint
    val blueprintId = keyguardBlueprintInteractor.blueprintId
    val refreshTransition = keyguardBlueprintInteractor.refreshTransition

    private val _currentTransition = MutableStateFlow<TransitionData?>(null)
    val currentTransition = _currentTransition.asStateFlow()

    private val runningTransitions = mutableSetOf<Transition>()
    private val transitionListener =
        object : Transition.TransitionListener {
            override fun onTransitionCancel(transition: Transition) {
                if (DEBUG) Log.e(TAG, "onTransitionCancel: ${transition::class.simpleName}")
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionEnd(transition: Transition) {
                if (DEBUG) Log.e(TAG, "onTransitionEnd: ${transition::class.simpleName}")
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionPause(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionPause: ${transition::class.simpleName}")
                updateTransitions(null) { remove(transition) }
            }

            override fun onTransitionResume(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionResume: ${transition::class.simpleName}")
                updateTransitions(null) { add(transition) }
            }

            override fun onTransitionStart(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionStart: ${transition::class.simpleName}")
                updateTransitions(null) { add(transition) }
            }
        }

    fun updateTransitions(data: TransitionData?, mutate: MutableSet<Transition>.() -> Unit) {
        runningTransitions.mutate()

        if (runningTransitions.size <= 0) _currentTransition.value = null
        else if (data != null) _currentTransition.value = data
    }

    fun runTransition(
        constraintLayout: ConstraintLayout,
        transition: Transition,
        config: Config,
        apply: () -> Unit,
    ) {
        val currentPriority = currentTransition.value?.let { it.config.type.priority } ?: -1
        if (config.checkPriority && config.type.priority < currentPriority) {
            if (DEBUG) {
                Log.w(
                    TAG,
                    "runTransition: skipping ${transition::class.simpleName}: " +
                        "currentPriority=$currentPriority; config=$config"
                )
            }
            apply()
            return
        }

        if (DEBUG) {
            Log.i(
                TAG,
                "runTransition: running ${transition::class.simpleName}: " +
                    "currentPriority=$currentPriority; config=$config"
            )
        }

        // beginDelayedTransition makes a copy, so we temporarially add the uncopied transition to
        // the running set until the copy is started by the handler.
        updateTransitions(TransitionData(config)) { add(transition) }
        transition.addListener(transitionListener)

        handler.post {
            if (config.terminatePrevious) {
                TransitionManager.endTransitions(constraintLayout)
            }

            TransitionManager.beginDelayedTransition(constraintLayout, transition)
            apply()

            // Delay removal until after copied transition has started
            handler.post { updateTransitions(null) { remove(transition) } }
        }
    }

    companion object {
        private const val TAG = "KeyguardBlueprintViewModel"
        private const val DEBUG = false
    }
}
