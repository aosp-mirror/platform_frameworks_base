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
 *
 */

package com.android.systemui.multishade.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.IntSize
import com.android.systemui.R
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.ui.viewmodel.MultiShadeViewModel
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.footer.ui.compose.QuickSettings
import com.android.systemui.statusbar.ui.composable.StatusBar
import com.android.systemui.util.time.SystemClock

@Composable
fun MultiShade(
    viewModel: MultiShadeViewModel,
    clock: SystemClock,
    modifier: Modifier = Modifier,
) {
    val isScrimEnabled: Boolean by viewModel.isScrimEnabled.collectAsState()
    val scrimAlpha: Float by viewModel.scrimAlpha.collectAsState()

    // TODO(b/273298030): find a different way to get the height constraint from its parent.
    BoxWithConstraints(modifier = modifier) {
        val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        Scrim(
            modifier = Modifier.fillMaxSize(),
            remoteTouch = viewModel::onScrimTouched,
            alpha = { scrimAlpha },
            isScrimEnabled = isScrimEnabled,
        )
        Shade(
            viewModel = viewModel.leftShade,
            currentTimeMillis = clock::elapsedRealtime,
            containerHeightPx = maxHeightPx,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column {
                StatusBar()
                Notifications()
            }
        }
        Shade(
            viewModel = viewModel.rightShade,
            currentTimeMillis = clock::elapsedRealtime,
            containerHeightPx = maxHeightPx,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Column {
                StatusBar()
                QuickSettings()
            }
        }
        Shade(
            viewModel = viewModel.singleShade,
            currentTimeMillis = clock::elapsedRealtime,
            containerHeightPx = maxHeightPx,
            modifier = Modifier,
        ) {
            Column {
                StatusBar()
                Notifications()
                QuickSettings()
            }
        }
    }
}

@Composable
private fun Scrim(
    remoteTouch: (ProxiedInputModel) -> Unit,
    alpha: () -> Float,
    isScrimEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha() }
                .background(colorResource(R.color.opaque_scrim))
                .fillMaxSize()
                .onSizeChanged { size = it }
                .then(
                    if (isScrimEnabled) {
                        Modifier.pointerInput(Unit) {
                                detectTapGestures(onTap = { remoteTouch(ProxiedInputModel.OnTap) })
                            }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        remoteTouch(
                                            ProxiedInputModel.OnDrag(
                                                xFraction = change.position.x / size.width,
                                                yDragAmountPx = dragAmount,
                                            )
                                        )
                                    },
                                    onDragEnd = { remoteTouch(ProxiedInputModel.OnDragEnd) },
                                    onDragCancel = { remoteTouch(ProxiedInputModel.OnDragCancel) }
                                )
                            }
                    } else {
                        Modifier
                    }
                )
    )
}
