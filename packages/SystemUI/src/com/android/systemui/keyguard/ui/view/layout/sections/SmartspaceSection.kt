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
import android.view.View.GONE
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardSmartspaceViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.res.R as R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import dagger.Lazy
import javax.inject.Inject

open class SmartspaceSection
@Inject
constructor(
    val context: Context,
    val keyguardClockViewModel: KeyguardClockViewModel,
    val keyguardSmartspaceViewModel: KeyguardSmartspaceViewModel,
    val keyguardSmartspaceInteractor: KeyguardSmartspaceInteractor,
    val smartspaceController: LockscreenSmartspaceController,
    val keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
    val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {
    private var smartspaceView: View? = null
    private var weatherView: View? = null
    private var dateView: View? = null

    private var smartspaceVisibilityListener: OnGlobalLayoutListener? = null

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
        smartspaceVisibilityListener =
            object : OnGlobalLayoutListener {
                var pastVisibility = GONE
                override fun onGlobalLayout() {
                    smartspaceView?.let {
                        val newVisibility = it.visibility
                        if (pastVisibility != newVisibility) {
                            keyguardSmartspaceInteractor.setBcSmartspaceVisibility(newVisibility)
                            pastVisibility = newVisibility
                        }
                    }
                }
            }
        smartspaceView?.viewTreeObserver?.addOnGlobalLayoutListener(smartspaceVisibilityListener)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        KeyguardSmartspaceViewBinder.bind(
            constraintLayout,
            keyguardClockViewModel,
            keyguardSmartspaceViewModel,
            blueprintInteractor.get(),
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        val horizontalPaddingStart =
            context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start) +
                context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
        val horizontalPaddingEnd =
            context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end) +
                context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
        constraintSet.apply {
            // migrate addDateWeatherView, addWeatherView from KeyguardClockSwitchController
            constrainHeight(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
            constrainWidth(sharedR.id.date_smartspace_view, ConstraintSet.WRAP_CONTENT)
            connect(
                sharedR.id.date_smartspace_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                horizontalPaddingStart
            )
            constrainWidth(sharedR.id.weather_smartspace_view, ConstraintSet.WRAP_CONTENT)
            connect(
                sharedR.id.weather_smartspace_view,
                ConstraintSet.TOP,
                sharedR.id.date_smartspace_view,
                ConstraintSet.TOP
            )
            connect(
                sharedR.id.weather_smartspace_view,
                ConstraintSet.BOTTOM,
                sharedR.id.date_smartspace_view,
                ConstraintSet.BOTTOM
            )
            connect(
                sharedR.id.weather_smartspace_view,
                ConstraintSet.START,
                sharedR.id.date_smartspace_view,
                ConstraintSet.END,
                4
            )

            // migrate addSmartspaceView from KeyguardClockSwitchController
            constrainHeight(sharedR.id.bc_smartspace_view, ConstraintSet.WRAP_CONTENT)
            connect(
                sharedR.id.bc_smartspace_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                horizontalPaddingStart
            )
            connect(
                sharedR.id.bc_smartspace_view,
                ConstraintSet.END,
                if (keyguardClockViewModel.clockShouldBeCentered.value) ConstraintSet.PARENT_ID
                else R.id.split_shade_guideline,
                ConstraintSet.END,
                horizontalPaddingEnd
            )

            if (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) {
                clear(sharedR.id.date_smartspace_view, ConstraintSet.TOP)
                connect(
                    sharedR.id.date_smartspace_view,
                    ConstraintSet.BOTTOM,
                    sharedR.id.bc_smartspace_view,
                    ConstraintSet.TOP
                )
            } else {
                clear(sharedR.id.date_smartspace_view, ConstraintSet.BOTTOM)
                connect(
                    sharedR.id.date_smartspace_view,
                    ConstraintSet.TOP,
                    R.id.lockscreen_clock_view,
                    ConstraintSet.BOTTOM
                )
                connect(
                    sharedR.id.bc_smartspace_view,
                    ConstraintSet.TOP,
                    sharedR.id.date_smartspace_view,
                    ConstraintSet.BOTTOM
                )
            }

            createBarrier(
                R.id.smart_space_barrier_bottom,
                Barrier.BOTTOM,
                0,
                *intArrayOf(
                    sharedR.id.bc_smartspace_view,
                    sharedR.id.date_smartspace_view,
                    sharedR.id.weather_smartspace_view,
                )
            )
        }
        updateVisibility(constraintSet)
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        listOf(smartspaceView, dateView, weatherView).forEach {
            it?.let {
                if (it.parent == constraintLayout) {
                    constraintLayout.removeView(it)
                }
            }
        }
        smartspaceView?.viewTreeObserver?.removeOnGlobalLayoutListener(smartspaceVisibilityListener)
        smartspaceVisibilityListener = null
    }

    private fun updateVisibility(constraintSet: ConstraintSet) {
        constraintSet.apply {
            setVisibility(
                sharedR.id.weather_smartspace_view,
                when (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) {
                    true -> ConstraintSet.GONE
                    false ->
                        when (keyguardSmartspaceViewModel.isWeatherEnabled) {
                            true -> ConstraintSet.VISIBLE
                            false -> ConstraintSet.GONE
                        }
                }
            )
            setVisibility(
                sharedR.id.date_smartspace_view,
                if (keyguardClockViewModel.hasCustomWeatherDataDisplay.value) ConstraintSet.GONE
                else ConstraintSet.VISIBLE
            )
        }
    }
}
