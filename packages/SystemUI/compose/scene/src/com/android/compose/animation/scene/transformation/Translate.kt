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

package com.android.compose.animation.scene.transformation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.Element
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.OverscrollScope
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.content.state.TransitionState

internal class Translate(
    override val matcher: ElementMatcher,
    private val x: Dp = 0.dp,
    private val y: Dp = 0.dp,
) : PropertyTransformation<Offset> {
    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        content: ContentKey,
        element: Element,
        stateInContent: Element.State,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        return with(layoutImpl.density) { Offset(value.x + x.toPx(), value.y + y.toPx()) }
    }
}

internal class OverscrollTranslate(
    override val matcher: ElementMatcher,
    val x: OverscrollScope.() -> Float = { 0f },
    val y: OverscrollScope.() -> Float = { 0f },
) : PropertyTransformation<Offset> {
    private val cachedOverscrollScope = CachedOverscrollScope()

    override fun transform(
        layoutImpl: SceneTransitionLayoutImpl,
        content: ContentKey,
        element: Element,
        stateInContent: Element.State,
        transition: TransitionState.Transition,
        value: Offset,
    ): Offset {
        // As this object is created by OverscrollBuilderImpl and we retrieve the current
        // OverscrollSpec only when the transition implements HasOverscrollProperties, we can assume
        // that this method was invoked after performing this check.
        val overscrollProperties = transition as TransitionState.HasOverscrollProperties
        val overscrollScope =
            cachedOverscrollScope.getFromCacheOrCompute(layoutImpl.density, overscrollProperties)

        return Offset(x = value.x + overscrollScope.x(), y = value.y + overscrollScope.y())
    }
}

/**
 * A helper class to cache a [OverscrollScope] given a [Density] and
 * [TransitionState.HasOverscrollProperties]. This helps avoid recreating a scope every frame
 * whenever an overscroll transition is computed.
 */
private class CachedOverscrollScope() {
    private var previousScope: OverscrollScope? = null
    private var previousDensity: Density? = null
    private var previousOverscrollProperties: TransitionState.HasOverscrollProperties? = null

    fun getFromCacheOrCompute(
        density: Density,
        overscrollProperties: TransitionState.HasOverscrollProperties,
    ): OverscrollScope {
        if (
            previousScope == null ||
                density != previousDensity ||
                previousOverscrollProperties != overscrollProperties
        ) {
            val scope =
                object : OverscrollScope, Density by density {
                    override val absoluteDistance: Float
                        get() = overscrollProperties.absoluteDistance
                }

            previousScope = scope
            previousDensity = density
            previousOverscrollProperties = overscrollProperties
            return scope
        }

        return checkNotNull(previousScope)
    }
}
