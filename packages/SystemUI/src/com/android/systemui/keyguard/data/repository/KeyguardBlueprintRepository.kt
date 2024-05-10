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

import android.util.Log
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.keyguard.ui.view.layout.blueprints.KeyguardBlueprintModule
import java.io.PrintWriter
import java.util.TreeMap
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manages blueprint changes for the lockscreen.
 *
 * To add a blueprint, create a class that implements LockscreenBlueprint and bind it to the map in
 * the dagger module: [KeyguardBlueprintModule]
 *
 * A Blueprint determines how the layout should be constrained on a high level.
 *
 * A Section is a modular piece of code that implements the constraints. The blueprint uses the
 * sections to define the constraints.
 */
@SysUISingleton
class KeyguardBlueprintRepository
@Inject
constructor(
    configurationRepository: ConfigurationRepository,
    blueprints: Set<@JvmSuppressWildcards KeyguardBlueprint>,
) {
    // This is TreeMap so that we can order the blueprints and assign numerical values to the
    // blueprints in the adb tool.
    private val blueprintIdMap: TreeMap<String, KeyguardBlueprint> =
        TreeMap<String, KeyguardBlueprint>().apply { putAll(blueprints.associateBy { it.id }) }
    val blueprint: MutableStateFlow<KeyguardBlueprint> = MutableStateFlow(blueprintIdMap[DEFAULT]!!)
    val refreshBluePrint: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
    val configurationChange: Flow<Unit> = configurationRepository.onAnyConfigurationChange

    /**
     * Emits the blueprint value to the collectors.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun applyBlueprint(index: Int): Boolean {
        ArrayList(blueprintIdMap.values)[index]?.let {
            applyBlueprint(it)
            return true
        }
        return false
    }

    /**
     * Emits the blueprint value to the collectors.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun applyBlueprint(blueprintId: String?): Boolean {
        val blueprint = blueprintIdMap[blueprintId]
        return if (blueprint != null) {
            applyBlueprint(blueprint)
            true
        } else {
            Log.e(
                TAG,
                "Could not find blueprint with id: $blueprintId. " +
                    "Perhaps it was not added to KeyguardBlueprintModule?"
            )
            false
        }
    }

    /** Emits the blueprint value to the collectors. */
    fun applyBlueprint(blueprint: KeyguardBlueprint?) {
        if (blueprint == this.blueprint.value) {
            refreshBlueprint()
            return
        }

        blueprint?.let { this.blueprint.value = it }
    }

    /** Re-emits the last emitted blueprint value if possible. */
    fun refreshBlueprint() {
        refreshBluePrint.tryEmit(Unit)
    }

    /** Prints all available blueprints to the PrintWriter. */
    fun printBlueprints(pw: PrintWriter) {
        blueprintIdMap.onEachIndexed { index, entry -> pw.println("$index: ${entry.key}") }
    }

    companion object {
        private const val TAG = "KeyguardBlueprintRepository"
    }
}
