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

import android.os.Handler
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.keyguard.ui.view.layout.blueprints.KeyguardBlueprintModule
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.KeyguardBlueprintLog
import com.android.systemui.util.ThreadAssert
import java.io.PrintWriter
import java.util.TreeMap
import javax.inject.Inject
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
    blueprints: Set<@JvmSuppressWildcards KeyguardBlueprint>,
    @Main val handler: Handler,
    val assert: ThreadAssert,
    @KeyguardBlueprintLog log: LogBuffer,
) {
    private val logger = Logger(log, "KeyguardBlueprintRepository")

    // This is TreeMap so that we can order the blueprints and assign numerical values to the
    // blueprints in the adb tool.
    private val blueprintIdMap: TreeMap<String, KeyguardBlueprint> =
        TreeMap<String, KeyguardBlueprint>().apply { putAll(blueprints.associateBy { it.id }) }
    val blueprint: MutableStateFlow<KeyguardBlueprint> = MutableStateFlow(blueprintIdMap[DEFAULT]!!)
    val refreshTransition = MutableSharedFlow<Config>(extraBufferCapacity = 1)
    @VisibleForTesting var targetTransitionConfig: Config? = null

    /**
     * Emits the blueprint value to the collectors.
     *
     * @param blueprintId
     * @return whether the transition has succeeded.
     */
    fun applyBlueprint(blueprintId: String?): Boolean {
        val blueprint = blueprintIdMap[blueprintId]
        if (blueprint == null) {
            logger.e({
                "Could not find blueprint with id: $str1. " +
                    "Perhaps it was not added to KeyguardBlueprintModule?"
            }) {
                str1 = blueprintId
            }
            return false
        }

        if (blueprint == this.blueprint.value) {
            return true
        }

        this.blueprint.value = blueprint
        return true
    }

    /**
     * Re-emits the last emitted blueprint value if possible. This is delayed until next frame to
     * dedupe requests and determine the correct transition to execute.
     */
    fun refreshBlueprint(config: Config = Config.DEFAULT) {
        fun scheduleCallback() {
            // We use a handler here instead of a CoroutineDispatcher because the one provided by
            // @Main CoroutineDispatcher is currently Dispatchers.Main.immediate, which doesn't
            // delay the callback, and instead runs it immediately.
            handler.post {
                assert.isMainThread()
                targetTransitionConfig?.let {
                    val success = refreshTransition.tryEmit(it)
                    if (!success) {
                        logger.e({ "refreshBlueprint: Failed to emit blueprint refresh: $str1" }) {
                            str1 = "$it"
                        }
                    }
                }
                targetTransitionConfig = null
            }
        }

        assert.isMainThread()
        if ((targetTransitionConfig?.type?.priority ?: Int.MIN_VALUE) < config.type.priority) {
            if (targetTransitionConfig == null) scheduleCallback()
            targetTransitionConfig = config
        } else {
            logger.i({ "Skipping low priority transition: $str1" }) { str1 = "$config" }
        }
    }

    /** Prints all available blueprints to the PrintWriter. */
    fun printBlueprints(pw: PrintWriter) {
        blueprintIdMap.onEachIndexed { index, entry -> pw.println("$index: ${entry.key}") }
    }
}
