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

package com.android.systemui.communal.ui.compose.section

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.ui.viewmodel.PopupType
import com.android.systemui.res.R
import javax.inject.Inject

class CommunalPopupSection
@Inject
constructor(
    private val viewModel: CommunalViewModel,
) {

    @Composable
    fun Popup() {
        val currentPopup by viewModel.currentPopup.collectAsStateWithLifecycle(initialValue = null)

        if (currentPopup == PopupType.CtaTile) {
            PopupOnDismissCtaTile(viewModel::onHidePopup)
        }

        AnimatedVisibility(
            visible = currentPopup == PopupType.CustomizeWidgetButton,
            modifier = Modifier.fillMaxSize()
        ) {
            ButtonToEditWidgets(
                onClick = {
                    viewModel.onHidePopup()
                    viewModel.onOpenWidgetEditor()
                },
                onDismissRequest = {
                    viewModel.onHidePopup()
                    viewModel.setSelectedKey(null)
                }
            )
        }
    }

    @Composable
    private fun AnimatedVisibilityScope.ButtonToEditWidgets(
        onClick: () -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, 40),
            onDismissRequest = onDismissRequest,
        ) {
            val colors = LocalAndroidColorScheme.current
            Button(
                modifier =
                    Modifier.height(56.dp)
                        .graphicsLayer { transformOrigin = TransformOrigin(0f, 0f) }
                        .animateEnterExit(
                            enter =
                                fadeIn(
                                    initialAlpha = 0f,
                                    animationSpec =
                                        tween(durationMillis = 83, easing = LinearEasing)
                                ),
                            exit =
                                fadeOut(
                                    animationSpec =
                                        tween(
                                            durationMillis = 83,
                                            delayMillis = 167,
                                            easing = LinearEasing
                                        )
                                )
                        )
                        .background(colors.secondary, RoundedCornerShape(50.dp)),
                onClick = onClick,
            ) {
                Row(
                    modifier =
                        Modifier.animateEnterExit(
                            enter =
                                fadeIn(
                                    animationSpec =
                                        tween(
                                            durationMillis = 167,
                                            delayMillis = 83,
                                            easing = LinearEasing
                                        )
                                ),
                            exit =
                                fadeOut(
                                    animationSpec =
                                        tween(durationMillis = 167, easing = LinearEasing)
                                )
                        )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Widgets,
                        contentDescription =
                            stringResource(R.string.button_to_configure_widgets_text),
                        tint = colors.onSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.button_to_configure_widgets_text),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSecondary
                    )
                }
            }
        }
    }

    @Composable
    private fun PopupOnDismissCtaTile(onDismissRequest: () -> Unit) {
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, 40),
            onDismissRequest = onDismissRequest
        ) {
            val colors = LocalAndroidColorScheme.current
            Row(
                modifier =
                    Modifier.height(56.dp)
                        .background(colors.secondary, RoundedCornerShape(50.dp))
                        .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = stringResource(R.string.popup_on_dismiss_cta_tile_text),
                    tint = colors.onSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.popup_on_dismiss_cta_tile_text),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSecondary,
                )
            }
        }
    }
}
