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

package com.android.systemui.touchpad.tutorial.ui.gesture

import kotlin.math.abs

/** Monitors for touchpad back gesture, that is three fingers swiping left or right */
class BackGestureMonitor(
    override val gestureDistanceThresholdPx: Int,
    override val gestureStateChangedCallback: (GestureState) -> Unit
) :
    TouchpadGestureMonitor by ThreeFingerDistanceBasedGestureMonitor(
        gestureDistanceThresholdPx = gestureDistanceThresholdPx,
        gestureStateChangedCallback = gestureStateChangedCallback,
        donePredicate =
            object : GestureDonePredicate {
                override fun wasGestureDone(
                    startX: Float,
                    startY: Float,
                    endX: Float,
                    endY: Float
                ): Boolean {
                    val distance = abs(endX - startX)
                    return distance >= gestureDistanceThresholdPx
                }
            }
    )
