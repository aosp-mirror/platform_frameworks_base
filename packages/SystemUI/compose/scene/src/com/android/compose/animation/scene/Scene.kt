/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.android.compose.animation.scene.modifiers.noResizeDuringTransitions

/** A scene in a [SceneTransitionLayout]. */
@Stable
internal class Scene(
    val key: SceneKey,
    layoutImpl: SceneTransitionLayoutImpl,
    content: @Composable SceneScope.() -> Unit,
    actions: Map<UserAction, UserActionResult>,
    zIndex: Float,
) {
    internal val scope = SceneScopeImpl(layoutImpl, this)

    var content by mutableStateOf(content)
    private var _userActions by mutableStateOf(checkValid(actions))
    var zIndex by mutableFloatStateOf(zIndex)
    var targetSize by mutableStateOf(IntSize.Zero)

    var userActions
        get() = _userActions
        set(value) {
            _userActions = checkValid(value)
        }

    private fun checkValid(
        userActions: Map<UserAction, UserActionResult>
    ): Map<UserAction, UserActionResult> {
        userActions.forEach { (action, result) ->
            if (key == result.toScene) {
                error(
                    "Transition to the same scene is not supported. Scene $key, action $action," +
                        " result $result"
                )
            }
        }
        return userActions
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    fun Content(modifier: Modifier = Modifier) {
        Box(
            modifier
                .zIndex(zIndex)
                .approachLayout(
                    isMeasurementApproachInProgress = { scope.layoutState.isTransitioning() }
                ) { measurable, constraints ->
                    targetSize = lookaheadSize
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .testTag(key.testTag)
        ) {
            scope.content()
        }
    }

    override fun toString(): String {
        return "Scene(key=$key)"
    }
}

internal class SceneScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val scene: Scene,
) : SceneScope, ElementStateScope by layoutImpl.elementStateScope {
    override val layoutState: SceneTransitionLayoutState = layoutImpl.state

    override fun Modifier.element(key: ElementKey): Modifier {
        return element(layoutImpl, scene, key)
    }

    @Composable
    override fun Element(
        key: ElementKey,
        modifier: Modifier,
        content: @Composable (ElementScope<ElementContentScope>.() -> Unit)
    ) {
        Element(layoutImpl, scene, key, modifier, content)
    }

    @Composable
    override fun MovableElement(
        key: ElementKey,
        modifier: Modifier,
        content: @Composable (ElementScope<MovableElementContentScope>.() -> Unit)
    ) {
        MovableElement(layoutImpl, scene, key, modifier, content)
    }

    @Composable
    override fun <T> animateSceneValueAsState(
        value: T,
        key: ValueKey,
        lerp: (T, T, Float) -> T,
        canOverflow: Boolean
    ): AnimatedState<T> {
        return animateSharedValueAsState(
            layoutImpl = layoutImpl,
            scene = scene.key,
            element = null,
            key = key,
            value = value,
            lerp = lerp,
            canOverflow = canOverflow,
        )
    }

    override fun Modifier.horizontalNestedScrollToScene(
        leftBehavior: NestedScrollBehavior,
        rightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ): Modifier =
        nestedScrollToScene(
            layoutImpl = layoutImpl,
            orientation = Orientation.Horizontal,
            topOrLeftBehavior = leftBehavior,
            bottomOrRightBehavior = rightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )

    override fun Modifier.verticalNestedScrollToScene(
        topBehavior: NestedScrollBehavior,
        bottomBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ): Modifier =
        nestedScrollToScene(
            layoutImpl = layoutImpl,
            orientation = Orientation.Vertical,
            topOrLeftBehavior = topBehavior,
            bottomOrRightBehavior = bottomBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )

    override fun Modifier.noResizeDuringTransitions(): Modifier {
        return noResizeDuringTransitions(layoutState = layoutImpl.state)
    }
}
