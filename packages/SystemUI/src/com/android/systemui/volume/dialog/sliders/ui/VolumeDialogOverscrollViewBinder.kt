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

package com.android.systemui.volume.dialog.sliders.ui

import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogOverscrollViewModel.OverscrollEventModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogSliderScope
class VolumeDialogOverscrollViewBinder
@Inject
constructor(private val viewModel: VolumeDialogOverscrollViewModel) {

    /**
     * [viewsToAnimate] is an array of [View] to be affected by the overscroll animation. [view] is
     * NOT animated by default.
     */
    fun CoroutineScope.bind(view: View, viewsToAnimate: Array<View>) {
        val animationValueHolder = FloatValueHolder(0f)
        val animation: SpringAnimation =
            SpringAnimation(animationValueHolder)
                .setSpring(
                    SpringForce(0f).apply {
                        stiffness = 800f
                        dampingRatio = 0.6f
                    }
                )
                .addUpdateListener { _, value, _ -> viewsToAnimate.setTranslationY(value) }

        viewModel.overscrollEvent
            .onEach { event ->
                when (event) {
                    is OverscrollEventModel.Animate -> {
                        animation.animateToFinalPosition(event.targetOffsetPx)
                    }
                    is OverscrollEventModel.Move -> {
                        animation.cancel()
                        viewsToAnimate.setTranslationY(event.touchOffsetPx)
                        animationValueHolder.value = event.touchOffsetPx
                    }
                }
            }
            .launchIn(this)
    }
}

private fun Array<View>.setTranslationY(translation: Float) {
    for (viewToAnimate in this) {
        viewToAnimate.translationY = translation
    }
}
