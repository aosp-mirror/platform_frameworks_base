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

import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.systemui.bouncer.ui.composable.Bouncer

const val TO_BOUNCER_FADE_FRACTION = 0.5f
private const val TO_BOUNCER_SWIPE_DISTANCE_FRACTION = 0.5f

fun TransitionBuilder.toBouncerTransition() {
    spec = tween(durationMillis = 500)

    distance = UserActionDistance { fromSceneSize, _ ->
        fromSceneSize.height * TO_BOUNCER_SWIPE_DISTANCE_FRACTION
    }

    translate(Bouncer.Elements.Content, y = 300.dp)
    fractionRange(end = TO_BOUNCER_FADE_FRACTION) { fade(Bouncer.Elements.Background) }
    fractionRange(start = TO_BOUNCER_FADE_FRACTION) { fade(Bouncer.Elements.Content) }
}
