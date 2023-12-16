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

package com.android.compose.animation.scene

import android.graphics.Picture
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

private const val TAG = "MovableElement"

@Composable
internal fun MovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: Scene,
    key: ElementKey,
    modifier: Modifier,
    content: @Composable MovableElementScope.() -> Unit,
) {
    Box(modifier.element(layoutImpl, scene, key)) {
        // Get the Element from the map. It will always be the same and we don't want to recompose
        // every time an element is added/removed from SceneTransitionLayoutImpl.elements, so we
        // disable read observation during the look-up in that map.
        val element = Snapshot.withoutReadObservation { layoutImpl.elements.getValue(key) }
        val movableElementScope =
            remember(layoutImpl, element, scene) {
                MovableElementScopeImpl(layoutImpl, element, scene)
            }

        // The [Picture] to which we save the last drawing commands of this element. This is
        // necessary because the content of this element might not be composed in this scene, in
        // which case we still need to draw it.
        val picture = remember { Picture() }

        // Whether we should compose the movable element here. The scene picker logic to know in
        // which scene we should compose/draw a movable element might depend on the current
        // transition progress, so we put this in a derivedStateOf to prevent many recompositions
        // during the transition.
        val shouldComposeMovableElement by
            remember(layoutImpl, scene.key, element) {
                derivedStateOf { shouldComposeMovableElement(layoutImpl, scene.key, element) }
            }

        if (shouldComposeMovableElement) {
            Box(
                Modifier.drawWithCache {
                    val width = size.width.toInt()
                    val height = size.height.toInt()

                    onDrawWithContent {
                        // Save the draw commands into [picture] for later to draw the last content
                        // even when this movable content is not composed.
                        val pictureCanvas = Canvas(picture.beginRecording(width, height))
                        draw(this, this.layoutDirection, pictureCanvas, this.size) {
                            this@onDrawWithContent.drawContent()
                        }
                        picture.endRecording()

                        // Draw the content.
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPicture(picture) }
                    }
                }
            ) {
                element.movableContent { movableElementScope.content() }
            }
        } else {
            // If we are not composed, we draw the previous drawing commands at the same size as the
            // movable content when it was composed in this scene.
            val sceneValues = element.sceneValues.getValue(scene.key)

            Spacer(
                Modifier.layout { measurable, _ ->
                        val size =
                            sceneValues.targetSize.takeIf { it != Element.SizeUnspecified }
                                ?: IntSize.Zero
                        val placeable =
                            measurable.measure(Constraints.fixed(size.width, size.height))
                        layout(size.width, size.height) { placeable.place(0, 0) }
                    }
                    .drawBehind {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPicture(picture) }
                    }
            )
        }
    }
}

private fun shouldComposeMovableElement(
    layoutImpl: SceneTransitionLayoutImpl,
    scene: SceneKey,
    element: Element,
): Boolean {
    val transition =
        layoutImpl.state.currentTransition
        // If we are idle, there is only one [scene] that is composed so we can compose our
        // movable content here.
        ?: return true
    val fromScene = transition.fromScene
    val toScene = transition.toScene

    val fromReady = layoutImpl.isSceneReady(fromScene)
    val toReady = layoutImpl.isSceneReady(toScene)

    val otherScene =
        when (scene) {
            fromScene -> toScene
            toScene -> fromScene
            else ->
                error(
                    "shouldComposeMovableElement(scene=$scene) called with fromScene=$fromScene " +
                        "and toScene=$toScene"
                )
        }

    val isShared = otherScene in element.sceneValues

    if (isShared && !toReady && !fromReady) {
        // This should usually not happen given that fromScene should be ready, but let's log a
        // warning here in case it does so it helps debugging flicker issues caused by this part of
        // the code.
        Log.w(
            TAG,
            "MovableElement $element might have to be composed for the first time in both " +
                "fromScene=$fromScene and toScene=$toScene. This will probably lead to a flicker " +
                "where the size of the element will jump from IntSize.Zero to its actual size " +
                "during the transition."
        )
    }

    // Element is not shared in this transition.
    if (!isShared) {
        return true
    }

    // toScene is not ready (because we are composing it for the first time), so we compose it there
    // first. This is the most common scenario when starting a transition that has a shared movable
    // element.
    if (!toReady) {
        return scene == toScene
    }

    // This should usually not happen, but if we are also composing for the first time in fromScene
    // then we should compose it there only.
    if (!fromReady) {
        return scene == fromScene
    }

    return shouldDrawOrComposeSharedElement(
        layoutImpl,
        transition,
        scene,
        element.key,
        sharedElementTransformation(layoutImpl.state, transition, element.key),
    )
}

private class MovableElementScopeImpl(
    private val layoutImpl: SceneTransitionLayoutImpl,
    private val element: Element,
    private val scene: Scene,
) : MovableElementScope {
    @Composable
    override fun <T> animateSharedValueAsState(
        value: T,
        debugName: String,
        lerp: (start: T, stop: T, fraction: Float) -> T,
        canOverflow: Boolean,
    ): State<T> {
        val key = remember { ValueKey(debugName) }
        return animateSharedValueAsState(layoutImpl, scene, element, key, value, lerp, canOverflow)
    }
}
