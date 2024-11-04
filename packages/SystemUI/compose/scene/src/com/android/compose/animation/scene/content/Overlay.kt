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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult

/** An overlay defined in a [SceneTransitionLayout]. */
@Stable
internal class Overlay(
    override val key: OverlayKey,
    layoutImpl: SceneTransitionLayoutImpl,
    content: @Composable ContentScope.() -> Unit,
    actions: Map<UserAction.Resolved, UserActionResult>,
    zIndex: Float,
    alignment: Alignment,
    isModal: Boolean,
) : Content(key, layoutImpl, content, actions, zIndex) {
    var alignment by mutableStateOf(alignment)
    var isModal by mutableStateOf(isModal)

    override fun toString(): String {
        return "Overlay(key=$key)"
    }
}
