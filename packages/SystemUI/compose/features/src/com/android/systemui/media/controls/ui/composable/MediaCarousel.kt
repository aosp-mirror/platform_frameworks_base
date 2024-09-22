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

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.scene.MovableElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.res.R
import com.android.systemui.util.animation.MeasurementInput

object MediaCarousel {
    object Elements {
        internal val Content =
            MovableElementKey(
                debugName = "MediaCarouselContent",
                contentPicker = MediaContentPicker,
            )
    }
}

@Composable
fun SceneScope.MediaCarousel(
    isVisible: Boolean,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    carouselController: MediaCarouselController,
    offsetProvider: (() -> IntOffset)? = null,
) {
    if (!isVisible || carouselController.isLockedAndHidden()) {
        return
    }

    val mediaHeight = dimensionResource(R.dimen.qs_media_session_height_expanded)

    MovableElement(
        key = MediaCarousel.Elements.Content,
        modifier = modifier.height(mediaHeight).fillMaxWidth(),
    ) {
        content {
            AndroidView(
                modifier =
                    Modifier.fillMaxSize()
                        .approachLayout(
                            isMeasurementApproachInProgress = { offsetProvider != null },
                            approachMeasure = { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    placeable.placeRelative(
                                        offsetProvider?.invoke() ?: IntOffset.Zero
                                    )
                                }
                            },
                        )
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)

                            // Notify controller to size the carousel for the current space
                            mediaHost.measurementInput =
                                MeasurementInput(placeable.width, placeable.height)
                            carouselController.setSceneContainerSize(
                                placeable.width,
                                placeable.height,
                            )

                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(0, 0)
                            }
                        },
                factory = { context ->
                    FrameLayout(context).apply {
                        layoutParams =
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            )
                    }
                },
                update = { it.setView(carouselController.mediaFrame) },
                onRelease = { it.removeAllViews() },
            )
        }
    }
}

private fun ViewGroup.setView(view: View) {
    if (view.parent == this) {
        return
    }
    (view.parent as? ViewGroup)?.removeView(view)
    addView(view)
}
