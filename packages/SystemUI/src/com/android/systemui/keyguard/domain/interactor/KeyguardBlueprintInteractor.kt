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

package com.android.systemui.keyguard.domain.interactor

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.SplitShadeKeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.statusbar.policy.SplitShadeStateController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@SysUISingleton
class KeyguardBlueprintInteractor
@Inject
constructor(
    private val keyguardBlueprintRepository: KeyguardBlueprintRepository,
    @Application private val applicationScope: CoroutineScope,
    private val context: Context,
    private val splitShadeStateController: SplitShadeStateController,
) {

    /** The current blueprint for the lockscreen. */
    val blueprint: Flow<KeyguardBlueprint> = keyguardBlueprintRepository.blueprint

    /**
     * Triggered when the blueprint isn't changed, but the ConstraintSet should be rebuilt and
     * optionally a transition should be fired to move to the rebuilt ConstraintSet.
     */
    val refreshTransition = keyguardBlueprintRepository.refreshTransition

    init {
        applicationScope.launch {
            keyguardBlueprintRepository.configurationChange
                .onStart { emit(Unit) }
                .collect { updateBlueprint() }
        }
    }

    /**
     * Detects when a new blueprint should be applied and calls [transitionToBlueprint]. This may
     * end up reapplying the same blueprint, which is fine as configuration may have changed.
     */
    private fun updateBlueprint() {
        val useSplitShade =
            splitShadeStateController.shouldUseSplitNotificationShade(context.resources)

        val blueprintId =
            if (useSplitShade) {
                SplitShadeKeyguardBlueprint.ID
            } else {
                DefaultKeyguardBlueprint.DEFAULT
            }

        transitionToBlueprint(blueprintId)
    }

    /**
     * Transitions to a blueprint.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun transitionToBlueprint(blueprintId: String): Boolean {
        return keyguardBlueprintRepository.applyBlueprint(blueprintId)
    }

    /**
     * Transitions to a blueprint.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun transitionToBlueprint(blueprintId: Int): Boolean {
        return keyguardBlueprintRepository.applyBlueprint(blueprintId)
    }

    /** Emits a value to refresh the blueprint with the appropriate transition. */
    fun refreshBlueprint(type: Type = Type.NoTransition) = refreshBlueprint(Config(type))

    /** Emits a value to refresh the blueprint with the appropriate transition. */
    fun refreshBlueprint(config: Config) = keyguardBlueprintRepository.refreshBlueprint(config)

    fun getCurrentBlueprint(): KeyguardBlueprint {
        return keyguardBlueprintRepository.blueprint.value
    }
}
