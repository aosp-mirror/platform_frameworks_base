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

package com.android.systemui.keyguard.data.repository

import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.keyguard.ui.view.layout.blueprints.KeyguardBlueprintModule
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Manages blueprint changes for the lockscreen.
 *
 * To add a blueprint, create a class that implements LockscreenBlueprint and bind it to the map in
 * the dagger module:
 *
 * A Blueprint determines how the layout should be constrained on a high level.
 *
 * A Section is a modular piece of code that implements the constraints. The blueprint uses the
 * sections to define the constraints.
 *
 * @see KeyguardBlueprintModule
 */
@SysUISingleton
class KeyguardBlueprintRepository
@Inject
constructor(
    configurationRepository: ConfigurationRepository,
    blueprints: Set<@JvmSuppressWildcards KeyguardBlueprint>,
    @Application private val applicationScope: CoroutineScope,
) {
    private val blueprintIdMap: Map<String, KeyguardBlueprint> = blueprints.associateBy { it.id }
    private val _blueprint: MutableSharedFlow<KeyguardBlueprint> = MutableSharedFlow(replay = 1)
    val blueprint: Flow<KeyguardBlueprint> = _blueprint.asSharedFlow()

    init {
        applyBlueprint(blueprintIdMap[DEFAULT]!!)
        applicationScope.launch {
            configurationRepository.onAnyConfigurationChange.collect { refreshBlueprint() }
        }
    }

    /**
     * Emits the blueprint value to the collectors.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun applyBlueprint(blueprintId: String?): Boolean {
        val blueprint = blueprintIdMap[blueprintId] ?: return false
        applyBlueprint(blueprint)
        return true
    }

    /** Emits the blueprint value to the collectors. */
    fun applyBlueprint(blueprint: KeyguardBlueprint?) {
        blueprint?.let { _blueprint.tryEmit(it) }
    }

    /** Re-emits the last emitted blueprint value if possible. */
    fun refreshBlueprint() {
        if (_blueprint.replayCache.isNotEmpty()) {
            _blueprint.tryEmit(_blueprint.replayCache.last())
        }
    }

    /** Prints all available blueprints to the PrintWriter. */
    fun printBlueprints(pw: PrintWriter) {
        blueprintIdMap.forEach { entry -> pw.println("${entry.key}") }
    }
}

/** Determines the constraints for the ConstraintSet in the lockscreen root view. */
interface KeyguardBlueprint {
    val id: String

    fun apply(constraintSet: ConstraintSet)
}
