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

package com.android.systemui.touchpad.tutorial.ui.viewmodel

import android.content.res.Resources
import android.view.MotionEvent
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.composable.GestureUiState
import com.android.systemui.touchpad.tutorial.ui.composable.toGestureUiState
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureFlowAdapter
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState
import com.android.systemui.touchpad.tutorial.ui.gesture.HomeGestureRecognizer
import com.android.systemui.touchpad.tutorial.ui.gesture.VelocityTracker
import com.android.systemui.touchpad.tutorial.ui.gesture.VerticalVelocityTracker
import com.android.systemui.touchpad.tutorial.ui.gesture.handleTouchpadMotionEvent
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class HomeGestureScreenViewModel
@Inject
constructor(
    val configurationInteractor: ConfigurationInteractor,
    @Main val resources: Resources,
    val velocityTracker: VelocityTracker = VerticalVelocityTracker(),
) : TouchpadTutorialScreenViewModel {

    private var recognizer: HomeGestureRecognizer? = null

    private val distanceThreshold: Flow<Int> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.touchpad_tutorial_gestures_distance_threshold)
            .distinctUntilChanged()

    private val velocityThreshold: Flow<Float> =
        configurationInteractor.onAnyConfigurationChange
            .map { resources.getDimension(R.dimen.touchpad_home_gesture_velocity_threshold) }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val gestureUiState: Flow<GestureUiState> =
        distanceThreshold
            .combine(velocityThreshold, { distance, velocity -> distance to velocity })
            .flatMapLatest { (distance, velocity) ->
                recognizer =
                    HomeGestureRecognizer(
                        gestureDistanceThresholdPx = distance,
                        velocityThresholdPxPerMs = velocity,
                        velocityTracker = velocityTracker,
                    )
                GestureFlowAdapter(recognizer!!).gestureStateAsFlow
            }
            .map { toGestureUiState(it) }

    private fun toGestureUiState(it: GestureState) =
        it.toGestureUiState(
            progressStartMarker = "drag with gesture",
            progressEndMarker = "release playback realtime",
            successAnimation = R.raw.trackpad_home_success,
        )

    override fun handleEvent(event: MotionEvent): Boolean {
        return recognizer?.handleTouchpadMotionEvent(event) ?: false
    }
}
