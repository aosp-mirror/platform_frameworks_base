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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.compose.animation.scene.UserActionDistanceScope
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.toShadeTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())
    swipeSpec =
        spring(
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = Shade.Dimensions.ScrimVisibilityThreshold,
        )
    distance =
        object : UserActionDistance {
            override fun UserActionDistanceScope.absoluteDistance(
                fromSceneSize: IntSize,
                orientation: Orientation,
            ): Float {
                return Notifications.Elements.NotificationScrim.targetOffset(Scenes.Shade)?.y ?: 0f
            }
        }

    fractionRange(start = .58f) {
        fade(ShadeHeader.Elements.Clock)
        fade(ShadeHeader.Elements.CollapsedContentStart)
        fade(ShadeHeader.Elements.CollapsedContentEnd)
        fade(ShadeHeader.Elements.PrivacyChip)
        fade(QuickSettings.Elements.SplitShadeQuickSettings)
        fade(QuickSettings.Elements.FooterActions)
    }

    val qsTranslation = -ShadeHeader.Dimensions.CollapsedHeight * 0.66f
    translate(QuickSettings.Elements.QuickQuickSettings, y = qsTranslation)
    translate(MediaCarousel.Elements.Content, y = qsTranslation)
    translate(Notifications.Elements.NotificationScrim, Edge.Top, false)
}

private val DefaultDuration = 500.milliseconds
