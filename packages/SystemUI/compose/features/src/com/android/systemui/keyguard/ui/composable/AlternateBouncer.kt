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

package com.android.systemui.keyguard.ui.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.background
import com.android.compose.modifiers.height
import com.android.compose.modifiers.width
import com.android.systemui.deviceentry.shared.model.BiometricMessage
import com.android.systemui.deviceentry.ui.binder.UdfpsAccessibilityOverlayBinder
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlay
import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.binder.AlternateBouncerUdfpsViewBinder
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerMessageAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.log.LongPressHandlingViewLogger
import com.android.systemui.res.R
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@Composable
fun AlternateBouncer(
    alternateBouncerDependencies: AlternateBouncerDependencies,
    modifier: Modifier = Modifier,
) {

    val isVisible by
        alternateBouncerDependencies.viewModel.isVisible.collectAsStateWithLifecycle(
            initialValue = false
        )

    val udfpsIconLocation by
        alternateBouncerDependencies.udfpsIconViewModel.iconLocation.collectAsStateWithLifecycle(
            initialValue = null
        )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier =
                Modifier.background(color = Colors.AlternateBouncerBackgroundColor, alpha = { 1f })
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { alternateBouncerDependencies.viewModel.onTapped() }
                        )
                    },
        ) {
            StatusMessage(
                viewModel = alternateBouncerDependencies.messageAreaViewModel,
            )
        }

        udfpsIconLocation?.let { udfpsLocation ->
            Box {
                DeviceEntryIcon(
                    viewModel = alternateBouncerDependencies.udfpsIconViewModel,
                    logger = alternateBouncerDependencies.logger,
                    modifier =
                        Modifier.width { udfpsLocation.width }
                            .height { udfpsLocation.height }
                            .fillMaxHeight()
                            .offset {
                                IntOffset(
                                    x = udfpsLocation.left,
                                    y = udfpsLocation.top,
                                )
                            },
                )
            }

            UdfpsA11yOverlay(
                viewModel = alternateBouncerDependencies.udfpsAccessibilityOverlayViewModel.get(),
                modifier = Modifier.fillMaxHeight(),
            )
        }
    }
}

@ExperimentalCoroutinesApi
@Composable
private fun StatusMessage(
    viewModel: AlternateBouncerMessageAreaViewModel,
    modifier: Modifier = Modifier,
) {
    val message: BiometricMessage? by
        viewModel.message.collectAsStateWithLifecycle(initialValue = null)

    Crossfade(
        targetState = message,
        label = "Alternate Bouncer message",
        animationSpec = tween(),
        modifier = modifier,
    ) { biometricMessage ->
        biometricMessage?.let {
            Text(
                textAlign = TextAlign.Center,
                text = it.message ?: "",
                color = Colors.AlternateBouncerTextColor,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 92.dp),
            )
        }
    }
}

@ExperimentalCoroutinesApi
@Composable
private fun DeviceEntryIcon(
    viewModel: AlternateBouncerUdfpsIconViewModel,
    logger: LongPressHandlingViewLogger,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view =
                DeviceEntryIconView(context, null, logger = logger).apply {
                    id = R.id.alternate_bouncer_udfps_icon_view
                    contentDescription =
                        context.resources.getString(R.string.accessibility_fingerprint_label)
                }
            AlternateBouncerUdfpsViewBinder.bind(view, viewModel)
            view
        },
    )
}

/** TODO (b/353955910): Validate accessibility CUJs */
@ExperimentalCoroutinesApi
@Composable
private fun UdfpsA11yOverlay(
    viewModel: AlternateBouncerUdfpsAccessibilityOverlayViewModel,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val view =
                UdfpsAccessibilityOverlay(context).apply {
                    id = R.id.alternate_bouncer_udfps_accessibility_overlay
                }
            UdfpsAccessibilityOverlayBinder.bind(view, viewModel)
            view
        },
        modifier = modifier,
    )
}

private object Colors {
    val AlternateBouncerBackgroundColor: Color = Color.Black.copy(alpha = .66f)
    val AlternateBouncerTextColor: Color = Color.White
}
