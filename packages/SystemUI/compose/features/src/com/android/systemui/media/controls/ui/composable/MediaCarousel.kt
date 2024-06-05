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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.contains
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.res.R
import com.android.systemui.util.animation.MeasurementInput

private object MediaCarousel {
    object Elements {
        internal val Content = ElementKey("MediaCarouselContent")
    }
}

@Composable
fun SceneScope.MediaCarousel(
    isVisible: Boolean,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    carouselController: MediaCarouselController,
) {
    if (!isVisible) {
        return
    }

    val density = LocalDensity.current
    val mediaHeight = dimensionResource(R.dimen.qs_media_session_height_expanded)

    val layoutWidth = 0
    val layoutHeight = with(density) { mediaHeight.toPx() }.toInt()

    // Notify controller to size the carousel for the current space
    mediaHost.measurementInput = MeasurementInput(layoutWidth, layoutHeight)
    carouselController.setSceneContainerSize(layoutWidth, layoutHeight)

    AndroidView(
        modifier =
            modifier
                .element(MediaCarousel.Elements.Content)
                .height(mediaHeight)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)

                    // Notify controller to size the carousel for the current space
                    mediaHost.measurementInput = MeasurementInput(placeable.width, placeable.height)
                    carouselController.setSceneContainerSize(placeable.width, placeable.height)

                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                },
        factory = { context ->
            FrameLayout(context).apply {
                val mediaFrame = carouselController.mediaFrame
                (mediaFrame.parent as? ViewGroup)?.removeView(mediaFrame)
                addView(mediaFrame)
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
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
