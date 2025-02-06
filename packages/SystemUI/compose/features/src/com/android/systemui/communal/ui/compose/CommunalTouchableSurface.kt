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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.res.R

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CommunalTouchableSurface(
    viewModel: CommunalViewModel,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier =
            modifier
                .semantics {
                    contentDescription =
                        context.getString(
                            R.string.accessibility_content_description_for_communal_hub
                        )
                    customActions =
                        listOf(
                            CustomAccessibilityAction(
                                context.getString(
                                    R.string.accessibility_action_label_close_communal_hub
                                )
                            ) {
                                viewModel.changeScene(
                                    CommunalScenes.Blank,
                                    "closed by accessibility",
                                )
                                true
                            },
                            CustomAccessibilityAction(
                                context.getString(R.string.accessibility_action_label_edit_widgets)
                            ) {
                                viewModel.onOpenWidgetEditor()
                                true
                            },
                        )
                }
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
