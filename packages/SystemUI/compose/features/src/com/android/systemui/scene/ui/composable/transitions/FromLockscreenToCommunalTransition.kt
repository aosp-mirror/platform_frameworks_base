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

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.communal.ui.compose.AllElements
import com.android.systemui.communal.ui.compose.Communal
import com.android.systemui.scene.shared.model.Scenes

fun TransitionBuilder.lockscreenToCommunalTransition() {
    spec = tween(durationMillis = 1000)

    // Translate lockscreen to the start direction.
    translate(Scenes.Lockscreen.rootElementKey, Edge.Start)

    // Translate communal hub grid from the end direction.
    translate(Communal.Elements.Grid, Edge.End)

    // Fade all communal hub elements.
    timestampRange(startMillis = 167, endMillis = 334) { fade(AllElements) }
}
