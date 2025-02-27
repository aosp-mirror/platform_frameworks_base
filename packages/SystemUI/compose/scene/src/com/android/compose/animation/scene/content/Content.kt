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

package com.android.compose.animation.scene.content

import android.annotation.SuppressLint
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.android.compose.animation.scene.AnimatedState
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementScope
import com.android.compose.animation.scene.ElementStateScope
import com.android.compose.animation.scene.MovableElement
import com.android.compose.animation.scene.MovableElementContentScope
import com.android.compose.animation.scene.MovableElementKey
import com.android.compose.animation.scene.NestedScrollBehavior
import com.android.compose.animation.scene.SceneTransitionLayoutForTesting
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.SceneTransitionLayoutScope
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.SharedValueType
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.ValueKey
import com.android.compose.animation.scene.animateSharedValueAsState
import com.android.compose.animation.scene.effect.GestureEffect
import com.android.compose.animation.scene.effect.OffsetOverscrollEffect
import com.android.compose.animation.scene.effect.VisualEffect
import com.android.compose.animation.scene.element
import com.android.compose.animation.scene.modifiers.noResizeDuringTransitions
import com.android.compose.animation.scene.nestedScrollToScene
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.ContainerState
import com.android.compose.ui.graphics.container

/** A content defined in a [SceneTransitionLayout], i.e. a scene or an overlay. */
@Stable
internal sealed class Content(
    open val key: ContentKey,
    val layoutImpl: SceneTransitionLayoutImpl,
    content: @Composable ContentScope.() -> Unit,
    actions: Map<UserAction.Resolved, UserActionResult>,
    zIndex: Float,
) {
    internal val scope = ContentScopeImpl(layoutImpl, content = this)
    val containerState = ContainerState()

    var content by mutableStateOf(content)
    var zIndex by mutableFloatStateOf(zIndex)
    var targetSize by mutableStateOf(IntSize.Zero)
    var userActions by mutableStateOf(actions)

    @SuppressLint("NotConstructor")
    @Composable
    fun Content(modifier: Modifier = Modifier) {
        Box(
            modifier
                .zIndex(zIndex)
                .approachLayout(
                    isMeasurementApproachInProgress = { layoutImpl.state.isTransitioning() }
                ) { measurable, constraints ->
                    // TODO(b/353679003): Use the ModifierNode API to set this *before* the approach
                    // pass is started.
                    targetSize = lookaheadSize
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .thenIf(layoutImpl.state.isElevationPossible(content = key, element = null)) {
                    Modifier.container(containerState)
                }
                .testTag(key.testTag)
        ) {
            scope.content()
        }
    }
}

internal class ContentScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val content: Content,
) : ContentScope, ElementStateScope by layoutImpl.elementStateScope {
    override val contentKey: ContentKey
        get() = content.key

    override val layoutState: SceneTransitionLayoutState = layoutImpl.state

    private val _verticalOverscrollEffect =
        OffsetOverscrollEffect(
            orientation = Orientation.Vertical,
            animationScope = layoutImpl.animationScope,
        )

    private val _horizontalOverscrollEffect =
        OffsetOverscrollEffect(
            orientation = Orientation.Horizontal,
            animationScope = layoutImpl.animationScope,
        )

    val verticalOverscrollGestureEffect = GestureEffect(_verticalOverscrollEffect)

    val horizontalOverscrollGestureEffect = GestureEffect(_horizontalOverscrollEffect)

    override val verticalOverscrollEffect = VisualEffect(_verticalOverscrollEffect)

    override val horizontalOverscrollEffect = VisualEffect(_horizontalOverscrollEffect)

    override fun Modifier.element(key: ElementKey): Modifier {
        return element(layoutImpl, content, key)
    }

    @Composable
    override fun Element(
        key: ElementKey,
        modifier: Modifier,
        content: @Composable (ElementScope<ElementContentScope>.() -> Unit),
    ) {
        Element(layoutImpl, this@ContentScopeImpl.content, key, modifier, content)
    }

    @Composable
    override fun MovableElement(
        key: MovableElementKey,
        modifier: Modifier,
        content: @Composable (ElementScope<MovableElementContentScope>.() -> Unit),
    ) {
        MovableElement(layoutImpl, this@ContentScopeImpl.content, key, modifier, content)
    }

    @Composable
    override fun <T> animateContentValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T> {
        return animateSharedValueAsState(
            layoutImpl = layoutImpl,
            content = content.key,
            element = null,
            key = key,
            value = value,
            type = type,
            canOverflow = canOverflow,
        )
    }

    override fun Modifier.horizontalNestedScrollToScene(
        leftBehavior: NestedScrollBehavior,
        rightBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ): Modifier {
        return nestedScrollToScene(
            draggableHandler = layoutImpl.horizontalDraggableHandler,
            topOrLeftBehavior = leftBehavior,
            bottomOrRightBehavior = rightBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )
    }

    override fun Modifier.verticalNestedScrollToScene(
        topBehavior: NestedScrollBehavior,
        bottomBehavior: NestedScrollBehavior,
        isExternalOverscrollGesture: () -> Boolean,
    ): Modifier {
        return nestedScrollToScene(
            draggableHandler = layoutImpl.verticalDraggableHandler,
            topOrLeftBehavior = topBehavior,
            bottomOrRightBehavior = bottomBehavior,
            isExternalOverscrollGesture = isExternalOverscrollGesture,
        )
    }

    override fun Modifier.noResizeDuringTransitions(): Modifier {
        return noResizeDuringTransitions(layoutState = layoutImpl.state)
    }

    @Composable
    override fun NestedSceneTransitionLayout(
        state: SceneTransitionLayoutState,
        modifier: Modifier,
        builder: SceneTransitionLayoutScope.() -> Unit,
    ) {
        SceneTransitionLayoutForTesting(
            state,
            modifier,
            onLayoutImpl = null,
            builder = builder,
            sharedElementMap = layoutImpl.elements,
            ancestorContentKeys =
                remember(layoutImpl.ancestorContentKeys, contentKey) {
                    layoutImpl.ancestorContentKeys + contentKey
                },
            lookaheadScope = layoutImpl.lookaheadScope,
        )
    }
}
