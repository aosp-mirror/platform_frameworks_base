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

package com.android.systemui.keyguard.ui.composable.section

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.contains
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.padding
import com.android.systemui.customization.R
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.largeClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockElementKeys.smallClockElementKey
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.largeClockScene
import com.android.systemui.keyguard.ui.composable.blueprint.ClockScenes.splitShadeLargeClockScene
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.composable.modifier.onTopPlacementChanged
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import javax.inject.Inject

@Composable
fun ClockView(view: View?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = {
            FrameLayout(it).apply {
                // Clip nothing. The clock views at times render outside their bounds. Compose does
                // not clip by default, so only this layer needs clipping to be explicitly disabled.
                clipChildren = false
                clipToPadding = false
            }
        },
        update = { parent ->
            view?.let {
                parent.removeAllViews()
                (view.parent as? ViewGroup)?.removeView(view)
                parent.addView(view)
            } ?: run { parent.removeAllViews() }
        },
        modifier = modifier,
    )
}

/** Provides small clock and large clock composables for the default clock face. */
class DefaultClockSection
@Inject
constructor(
    private val viewModel: KeyguardClockViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
) {
    @Composable
    fun ContentScope.SmallClock(
        burnInParams: BurnInParameters,
        onTopChanged: (top: Float?) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val currentClock by viewModel.currentClock.collectAsStateWithLifecycle()
        val smallTopMargin by
            viewModel.smallClockTopMargin.collectAsStateWithLifecycle(
                viewModel.getSmallClockTopMargin()
            )
        if (currentClock?.smallClock?.view == null) {
            return
        }

        ClockView(
            checkNotNull(currentClock).smallClock.view,
            modifier =
                modifier
                    .height(dimensionResource(R.dimen.small_clock_height))
                    .padding(horizontal = dimensionResource(R.dimen.clock_padding_start))
                    .padding(top = { smallTopMargin })
                    .onTopPlacementChanged(onTopChanged)
                    .burnInAware(viewModel = aodBurnInViewModel, params = burnInParams)
                    .element(smallClockElementKey),
        )
    }

    @Composable
    fun ContentScope.LargeClock(burnInParams: BurnInParameters, modifier: Modifier = Modifier) {
        val currentClock by viewModel.currentClock.collectAsStateWithLifecycle()
        if (currentClock?.largeClock?.view == null) {
            return
        }

        // Centering animation for clocks that have custom position animations.
        LaunchedEffect(layoutState.currentTransition?.progress) {
            val transition = layoutState.currentTransition ?: return@LaunchedEffect
            if (currentClock?.largeClock?.config?.hasCustomPositionUpdatedAnimation != true) {
                return@LaunchedEffect
            }

            // If we are not doing the centering animation, do not animate.
            val progress =
                if (transition.isTransitioningBetween(largeClockScene, splitShadeLargeClockScene)) {
                    transition.progress
                } else {
                    1f
                }

            val dir = if (transition.toContent == splitShadeLargeClockScene) -1f else 1f
            val distance = dir * getClockCenteringDistance()
            val largeClock = checkNotNull(currentClock).largeClock
            largeClock.animations.onPositionUpdated(distance = distance, fraction = progress)
        }

        Element(key = largeClockElementKey, modifier = modifier) {
            content {
                ClockView(
                    checkNotNull(currentClock).largeClock.view,
                    modifier =
                        Modifier.fillMaxSize()
                            .burnInAware(
                                viewModel = aodBurnInViewModel,
                                params = burnInParams,
                                isClock = true,
                            ),
                )
            }
        }
    }

    fun getClockCenteringDistance(): Float {
        return Resources.getSystem().displayMetrics.widthPixels / 4f
    }
}
