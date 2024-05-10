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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSmartspaceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject

class SmartspaceSection
@Inject
constructor(
    val keyguardClockViewModel: KeyguardClockViewModel,
    val keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    private val context: Context,
    val smartspaceController: LockscreenSmartspaceController,
    val keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
) : KeyguardSection() {
    private var smartspaceView: View? = null
    private var weatherView: View? = null
    private var dateView: View? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        smartspaceView = smartspaceController.buildAndConnectView(constraintLayout)
        weatherView = smartspaceController.buildAndConnectWeatherView(constraintLayout)
        dateView = smartspaceController.buildAndConnectDateView(constraintLayout)
        if (keyguardSmartspaceViewModel.isSmartspaceEnabled) {
            constraintLayout.addView(smartspaceView)
            if (keyguardSmartspaceViewModel.isDateWeatherDecoupled) {
                constraintLayout.addView(weatherView)
                constraintLayout.addView(dateView)
            }
        }
        keyguardUnlockAnimationController.lockscreenSmartspace = smartspaceView
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        KeyguardSmartspaceViewBinder.bind(this, constraintLayout, keyguardClockViewModel)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        // Generally, weather should be next to dateView
        // smartspace should be below date & weather views
        constraintSet.apply {
            // migrate addDateWeatherView, addWeatherView from KeyguardClockSwitchController
            dateView?.let { dateView ->
                constrainHeight(dateView.id, WRAP_CONTENT)
                constrainWidth(dateView.id, WRAP_CONTENT)
                connect(
                    dateView.id,
                    START,
                    PARENT_ID,
                    START,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start)
                )
            }
            weatherView?.let {
                constrainWidth(it.id, WRAP_CONTENT)
                dateView?.let { dateView ->
                    connect(it.id, TOP, dateView.id, TOP)
                    connect(it.id, BOTTOM, dateView.id, BOTTOM)
                    connect(it.id, START, dateView.id, END, 4)
                }
            }
            // migrate addSmartspaceView from KeyguardClockSwitchController
            smartspaceView?.let {
                constrainHeight(it.id, WRAP_CONTENT)
                connect(
                    it.id,
                    START,
                    PARENT_ID,
                    START,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start)
                )
                connect(
                    it.id,
                    END,
                    PARENT_ID,
                    END,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end)
                )
            }

            if (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) {
                dateView?.let { dateView ->
                    smartspaceView?.let { smartspaceView ->
                        connect(dateView.id, BOTTOM, smartspaceView.id, TOP)
                    }
                }
            } else {
                dateView?.let { dateView ->
                    clear(dateView.id, BOTTOM)
                    connect(dateView.id, TOP, R.id.lockscreen_clock_view, BOTTOM)
                    constrainHeight(dateView.id, WRAP_CONTENT)
                    smartspaceView?.let { smartspaceView ->
                        clear(smartspaceView.id, TOP)
                        connect(smartspaceView.id, TOP, dateView.id, BOTTOM)
                    }
                }
            }
            updateVisibility(constraintSet)
        }
    }

    private fun updateVisibility(constraintSet: ConstraintSet) {
        constraintSet.apply {
            weatherView?.let {
                setVisibility(
                    it.id,
                    when (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) {
                        true -> ConstraintSet.GONE
                        false ->
                            when (keyguardSmartspaceViewModel.isWeatherEnabled) {
                                true -> ConstraintSet.VISIBLE
                                false -> ConstraintSet.GONE
                            }
                    }
                )
            }
            dateView?.let {
                setVisibility(
                    it.id,
                    if (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) ConstraintSet.GONE
                    else ConstraintSet.VISIBLE
                )
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        listOf(smartspaceView, dateView, weatherView).forEach {
            it?.let {
                if (it.parent == constraintLayout) {
                    constraintLayout.removeView(it)
                }
            }
        }
    }
}
