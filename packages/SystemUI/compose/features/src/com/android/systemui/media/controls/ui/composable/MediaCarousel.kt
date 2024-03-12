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

package com.android.systemui.media.controls.ui.composable

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.contains
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.util.animation.MeasurementInput

private object MediaCarousel {
    object Elements {
        internal val Content = ElementKey("MediaCarouselContent")
    }
}

@Composable
fun SceneScope.MediaCarousel(
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    layoutWidth: Int,
    layoutHeight: Int,
    carouselController: MediaCarouselController,
) {
    // Notify controller to size the carousel for the current space
    mediaHost.measurementInput = MeasurementInput(layoutWidth, layoutHeight)
    carouselController.setSceneContainerSize(layoutWidth, layoutHeight)

    AndroidView(
        modifier = modifier.element(MediaCarousel.Elements.Content),
        factory = { context ->
            FrameLayout(context).apply {
                val mediaFrame = carouselController.mediaFrame
                (mediaFrame.parent as? ViewGroup)?.removeView(mediaFrame)
                addView(mediaFrame)
            }
        },
        update = {
            if (it.contains(carouselController.mediaFrame)) {
                return@AndroidView
            }
            val mediaFrame = carouselController.mediaFrame
            (mediaFrame.parent as? ViewGroup)?.removeView(mediaFrame)
            it.addView(mediaFrame)
        },
    )
}
