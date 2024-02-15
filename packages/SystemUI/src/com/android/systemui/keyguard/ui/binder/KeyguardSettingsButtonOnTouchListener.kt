/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.binder

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.android.systemui.common.ui.view.rawDistanceFrom
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel

class KeyguardSettingsButtonOnTouchListener(
    private val viewModel: KeyguardSettingsMenuViewModel,
) : View.OnTouchListener {

    private val downPositionDisplayCoords = PointF()

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                downPositionDisplayCoords.set(motionEvent.rawX, motionEvent.rawY)
                viewModel.onTouchGestureStarted()
            }
            MotionEvent.ACTION_UP -> {
                view.isPressed = false
                val distanceMoved =
                    motionEvent.rawDistanceFrom(
                        downPositionDisplayCoords.x,
                        downPositionDisplayCoords.y
                    )
                val isClick = distanceMoved < ViewConfiguration.getTouchSlop()
                viewModel.onTouchGestureEnded(isClick)
                if (isClick) {
                    view.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                viewModel.onTouchGestureEnded(/* isClick= */ false)
            }
        }

        return true
    }
}
