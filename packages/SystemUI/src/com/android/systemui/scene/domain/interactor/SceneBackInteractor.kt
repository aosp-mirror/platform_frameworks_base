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

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.SceneContainerConfig
import java.util.Stack
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class SceneBackInteractor
@Inject
constructor(
    private val logger: SceneLogger,
    private val sceneContainerConfig: SceneContainerConfig,
) {
    private val _backScene = MutableStateFlow<SceneKey?>(null)
    /**
     * The scene to navigate to when the user triggers back navigation.
     *
     * This is meant for scene implementations to consult with when they implement their destination
     * scene flow.
     *
     * Note that this flow could emit any scene from the [SceneContainerConfig] and that it's an
     * illegal state to have scene implementation map to itself in its destination scene flow. Thus,
     * scene implementations might wish to filter their own scene key out before using this.
     */
    val backScene: StateFlow<SceneKey?> = _backScene.asStateFlow()

    private val backStack = Stack<SceneKey>()

    fun onSceneChange(from: SceneKey, to: SceneKey) {
        check(from != to) { "from == to, from=${from.debugName}, to=${to.debugName}" }
        when (stackOperation(from, to)) {
            Clear -> {
                backStack.clear()
            }
            Push -> {
                backStack.push(from)
            }
            Pop -> {
                check(backStack.isNotEmpty()) { "Cannot pop ${from.debugName} when stack is empty" }
                val popped = backStack.pop()
                check(to == popped) {
                    "Expected to pop ${to.debugName} but instead popped ${popped.debugName}"
                }
            }
        }

        logger.logSceneBackStack(backStack)
        _backScene.value = peek()
    }

    private fun stackOperation(from: SceneKey, to: SceneKey): StackOperation {
        val fromDistance =
            checkNotNull(sceneContainerConfig.navigationDistances[from]) {
                "No distance mapping for scene \"${from.debugName}\"!"
            }
        val toDistance =
            checkNotNull(sceneContainerConfig.navigationDistances[to]) {
                "No distance mapping for scene \"${to.debugName}\"!"
            }

        return when {
            toDistance == 0 -> Clear
            toDistance > fromDistance -> Push
            toDistance < fromDistance -> Pop
            else ->
                error(
                    "No mapping when from=${from.debugName} (distance=$fromDistance)," +
                        " to=${to.debugName} (distance=$toDistance)!"
                )
        }
    }

    private fun peek(): SceneKey? {
        return if (backStack.isNotEmpty()) {
            backStack.peek()
        } else {
            null
        }
    }

    private sealed interface StackOperation
    private data object Clear : StackOperation
    private data object Push : StackOperation
    private data object Pop : StackOperation
}
