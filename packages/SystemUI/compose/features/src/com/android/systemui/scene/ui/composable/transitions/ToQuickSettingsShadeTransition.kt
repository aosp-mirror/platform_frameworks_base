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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.Shade
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.toQuickSettingsShadeTransition(durationScale: Double = 1.0) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())
    swipeSpec =
        spring(
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = Shade.Dimensions.ScrimVisibilityThreshold,
        )
    distance = UserActionDistance { fromSceneSize, _ -> fromSceneSize.height.toFloat() * 2 / 3f }

    translate(OverlayShade.Elements.Panel, Edge.Top)

    fractionRange(end = .5f) { fade(OverlayShade.Elements.Scrim) }
}

private val DefaultDuration = 300.milliseconds
