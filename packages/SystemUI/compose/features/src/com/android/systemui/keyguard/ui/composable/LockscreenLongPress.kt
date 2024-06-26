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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.keyguard.ui.composable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.keyguard.ui.viewmodel.KeyguardLongPressViewModel

/** Container for lockscreen content that handles long-press to bring up the settings menu. */
@Composable
// TODO(b/344879669): now that it's more generic than long-press, rename it.
fun LockscreenLongPress(
    viewModel: KeyguardLongPressViewModel,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(onSettingsMenuPlaces: (coordinates: Rect?) -> Unit) -> Unit,
) {
    val isEnabled: Boolean by
        viewModel.isLongPressHandlingEnabled.collectAsStateWithLifecycle(initialValue = false)
    val (settingsMenuBounds, setSettingsMenuBounds) = remember { mutableStateOf<Rect?>(null) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            modifier
                .pointerInput(isEnabled) {
                    if (isEnabled) {
                        detectLongPressGesture { viewModel.onLongPress() }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { viewModel.onClick(it.x, it.y) },
                        onDoubleTap = { viewModel.onDoubleClick() },
                    )
                }
                .pointerInput(settingsMenuBounds) {
                    awaitEachGesture {
                        val pointerInputChange = awaitFirstDown()
                        if (settingsMenuBounds?.contains(pointerInputChange.position) == false) {
                            viewModel.onTouchedOutside()
                        }
                    }
                }
                // Passing null for the indication removes the ripple effect.
                .indication(interactionSource, null)
    ) {
        content(setSettingsMenuBounds)
    }
}
