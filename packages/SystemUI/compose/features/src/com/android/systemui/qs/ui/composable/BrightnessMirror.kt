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

package com.android.systemui.qs.ui.composable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.height
import com.android.compose.modifiers.width
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.settings.brightness.ui.binder.BrightnessMirrorInflater
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel

@Composable
fun BrightnessMirror(
    viewModel: BrightnessMirrorViewModel,
    qsSceneAdapter: QSSceneAdapter,
    modifier: Modifier = Modifier,
    measureFromContainer: Boolean = false,
) {
    val isShowing by viewModel.isShowing.collectAsStateWithLifecycle()
    val mirrorAlpha by
        animateFloatAsState(
            targetValue = if (isShowing) 1f else 0f,
            label = "alphaAnimationBrightnessMirrorShowing",
        )
    val mirrorOffsetAndSize by viewModel.locationAndSize.collectAsStateWithLifecycle()
    val yOffset =
        if (measureFromContainer) {
            mirrorOffsetAndSize.yOffsetFromContainer
        } else {
            mirrorOffsetAndSize.yOffsetFromWindow
        }
    val offset = IntOffset(0, yOffset)

    // Use unbounded=true as the full mirror (with paddings and background offset) may be larger
    // than the space we have (but it will fit, because the brightness slider fits).
    Box(
        modifier =
            modifier.fillMaxHeight().wrapContentWidth(unbounded = true).graphicsLayer {
                alpha = mirrorAlpha
            }
    ) {
        QuickSettingsTheme {
            // The assumption for using this AndroidView is that there will be only one in view at
            // a given time (which is a reasonable assumption). Because `QSSceneAdapter` (actually
            // `BrightnessSliderController` only supports a single mirror).
            // The benefit of doing it like this is that if the configuration changes or QSImpl is
            // re-inflated, it's not relevant to the composable, as we'll always get a new one.
            AndroidView(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .offset { offset }
                        .width { mirrorOffsetAndSize.width }
                        .height { mirrorOffsetAndSize.height },
                factory = { context ->
                    val (view, controller) =
                        BrightnessMirrorInflater.inflate(context, viewModel.sliderControllerFactory)
                    viewModel.setToggleSlider(controller)
                    view
                },
                update = { qsSceneAdapter.setBrightnessMirrorController(viewModel) },
                onRelease = { qsSceneAdapter.setBrightnessMirrorController(null) }
            )
        }
    }
}
