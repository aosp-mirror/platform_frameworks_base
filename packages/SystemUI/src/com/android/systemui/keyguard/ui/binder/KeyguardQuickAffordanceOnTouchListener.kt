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

import android.annotation.SuppressLint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewPropertyAnimator
import androidx.core.animation.CycleInterpolator
import androidx.core.animation.ObjectAnimator
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.view.rawDistanceFrom
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper

class KeyguardQuickAffordanceOnTouchListener(
    private val view: View,
    private val viewModel: KeyguardQuickAffordanceViewModel,
    private val messageDisplayer: (Int) -> Unit,
    private val vibratorHelper: VibratorHelper?,
    private val falsingManager: FalsingManager?,
) : View.OnTouchListener {

    private val longPressDurationMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var longPressAnimator: ViewPropertyAnimator? = null
    private val downDisplayCoords: PointF by lazy { PointF() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (viewModel.configKey != null) {
                    downDisplayCoords.set(event.rawX, event.rawY)
                    if (isUsingAccurateTool(event)) {
                        // For accurate tool types (stylus, mouse, etc.), we don't require a
                        // long-press.
                    } else {
                        // When not using a stylus, we require a long-press to activate the
                        // quick affordance, mostly to do "falsing" (e.g. protect from false
                        // clicks in the pocket/bag).
                        longPressAnimator =
                            view
                                .animate()
                                .scaleX(PRESSED_SCALE)
                                .scaleY(PRESSED_SCALE)
                                .setDuration(longPressDurationMs)
                    }
                }
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isUsingAccurateTool(event)) {
                    // Moving too far while performing a long-press gesture cancels that
                    // gesture.
                    if (
                        event
                            .rawDistanceFrom(
                                downDisplayCoords.x,
                                downDisplayCoords.y,
                            ) > ViewConfiguration.getTouchSlop()
                    ) {
                        cancel()
                    }
                }
                false
            }
            MotionEvent.ACTION_UP -> {
                if (isUsingAccurateTool(event)) {
                    // When using an accurate tool type (stylus, mouse, etc.), we don't require
                    // a long-press gesture to activate the quick affordance. Therefore, lifting
                    // the pointer performs a click.
                    if (
                        viewModel.configKey != null &&
                            event.rawDistanceFrom(downDisplayCoords.x, downDisplayCoords.y) <=
                                ViewConfiguration.getTouchSlop() &&
                            falsingManager?.isFalseTap(FalsingManager.NO_PENALTY) == false
                    ) {
                        dispatchClick(viewModel.configKey)
                    }
                } else {
                    // When not using a stylus, lifting the finger/pointer will actually cancel
                    // the long-press gesture. Calling cancel after the quick affordance was
                    // already long-press activated is a no-op, so it's safe to call from here.
                    cancel()
                }
                false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancel()
                true
            }
            else -> false
        }
    }

    private fun dispatchClick(
        configKey: String,
    ) {
        view.setOnClickListener {
            vibratorHelper?.vibrate(
                if (viewModel.isActivated) {
                    KeyguardBottomAreaVibrations.Activated
                } else {
                    KeyguardBottomAreaVibrations.Deactivated
                }
            )
            viewModel.onClicked(
                KeyguardQuickAffordanceViewModel.OnClickedParameters(
                    configKey = configKey,
                    expandable = Expandable.fromView(view),
                    slotId = viewModel.slotId,
                )
            )
        }
        view.performClick()
        view.setOnClickListener(null)
    }

    fun cancel() {
        longPressAnimator?.cancel()
        longPressAnimator = null
        view.animate().scaleX(1f).scaleY(1f)
    }

    companion object {
        private const val PRESSED_SCALE = 1.5f

        /**
         * Returns `true` if the tool type at the given pointer index is an accurate tool (like
         * stylus or mouse), which means we can trust it to not be a false click; `false` otherwise.
         */
        private fun isUsingAccurateTool(
            event: MotionEvent,
            pointerIndex: Int = 0,
        ): Boolean {
            return when (event.getToolType(pointerIndex)) {
                MotionEvent.TOOL_TYPE_STYLUS -> true
                MotionEvent.TOOL_TYPE_MOUSE -> true
                else -> false
            }
        }
    }
}
