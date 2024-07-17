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

package com.android.systemui.communal.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.motionEventSpy
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CommunalTouchableSurface(
    viewModel: CommunalViewModel,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            modifier
                .combinedClickable(
                    onLongClick = viewModel::onLongClick,
                    onClick = viewModel::onClick,
                    interactionSource = interactionSource,
                    indication = null,
                )
                .onPreviewKeyEvent {
                    viewModel.signalUserInteraction()
                    false
                }
                .motionEventSpy { viewModel.signalUserInteraction() }
    ) {
        content()
    }
}
