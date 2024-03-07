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
 *
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
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardClockViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.Utils
import javax.inject.Inject

internal fun ConstraintSet.setVisibility(
    views: Iterable<View>,
    visibility: Int,
) = views.forEach { view -> this.setVisibility(view.id, visibility) }

internal fun ConstraintSet.setAlpha(
    views: Iterable<View>,
    alpha: Float,
) = views.forEach { view -> this.setAlpha(view.id, alpha) }

class ClockSection
@Inject
constructor(
    private val clockInteractor: KeyguardClockInteractor,
    private val keyguardClockViewModel: KeyguardClockViewModel,
    val smartspaceViewModel: KeyguardSmartspaceViewModel,
    private val context: Context,
    private val splitShadeStateController: SplitShadeStateController,
    private val featureFlags: FeatureFlagsClassic,
) : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {
        KeyguardClockViewBinder.bind(
            this,
            constraintLayout,
            keyguardClockViewModel,
            clockInteractor,
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        clockInteractor.clock?.let { clock ->
            constraintSet.applyDeltaFrom(buildConstraints(clock, constraintSet))
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {}

    private fun buildConstraints(
        clock: ClockController,
        constraintSet: ConstraintSet
    ): ConstraintSet {
        // Add constraint between rootView and clockContainer
        applyDefaultConstraints(constraintSet)
        getTargetClockFace(clock).applyConstraints(constraintSet)

        // Add constraint between elements in clock and clock container
        return constraintSet.apply {
            setAlpha(getTargetClockFace(clock).views, 1F)
            setAlpha(getNonTargetClockFace(clock).views, 0F)
        }
    }

    var largeClockEndGuideline = PARENT_ID

    // Return if largeClockEndGuideline changes,
    // and use it to decide whether to refresh blueprint
    fun setClockShouldBeCentered(shouldBeCentered: Boolean): Boolean {
        val previousValue = largeClockEndGuideline
        largeClockEndGuideline = if (shouldBeCentered) PARENT_ID else R.id.split_shade_guideline
        return previousValue != largeClockEndGuideline
    }

    private fun getTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.useLargeClock) getLargeClockFace(clock)
        else getSmallClockFace(clock)
    private fun getNonTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.useLargeClock) getSmallClockFace(clock)
        else getLargeClockFace(clock)

    private fun getLargeClockFace(clock: ClockController): ClockFaceLayout = clock.largeClock.layout
    private fun getSmallClockFace(clock: ClockController): ClockFaceLayout = clock.smallClock.layout
    fun applyDefaultConstraints(constraints: ConstraintSet) {
        constraints.apply {
            connect(R.id.lockscreen_clock_view_large, START, PARENT_ID, START)
            connect(R.id.lockscreen_clock_view_large, END, largeClockEndGuideline, END)
            connect(R.id.lockscreen_clock_view_large, BOTTOM, R.id.lock_icon_view, TOP)
            var largeClockTopMargin =
                context.resources.getDimensionPixelSize(R.dimen.status_bar_height) +
                    context.resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_padding_top
                    ) +
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset)
            largeClockTopMargin += smartspaceViewModel.getDimen(DATE_WEATHER_VIEW_HEIGHT)
            largeClockTopMargin += smartspaceViewModel.getDimen(ENHANCED_SMARTSPACE_HEIGHT)
            if (!keyguardClockViewModel.useLargeClock) {
                largeClockTopMargin -=
                    context.resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_height
                    )
            }
            connect(R.id.lockscreen_clock_view_large, TOP, PARENT_ID, TOP, largeClockTopMargin)
            constrainHeight(R.id.lockscreen_clock_view_large, WRAP_CONTENT)
            constrainWidth(R.id.lockscreen_clock_view_large, WRAP_CONTENT)
            constrainWidth(R.id.lockscreen_clock_view, WRAP_CONTENT)
            constrainHeight(
                R.id.lockscreen_clock_view,
                context.resources.getDimensionPixelSize(
                    com.android.systemui.customization.R.dimen.small_clock_height
                )
            )
            connect(
                R.id.lockscreen_clock_view,
                START,
                PARENT_ID,
                START,
                context.resources.getDimensionPixelSize(
                    com.android.systemui.customization.R.dimen.clock_padding_start
                )
            )
            var smallClockTopMargin =
                if (splitShadeStateController.shouldUseSplitNotificationShade(context.resources)) {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
                } else {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                        Utils.getStatusBarHeaderHeightKeyguard(context)
                }
            if (keyguardClockViewModel.useLargeClock) {
                smallClockTopMargin -=
                    context.resources.getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_height
                    )
            }
            connect(R.id.lockscreen_clock_view, TOP, PARENT_ID, TOP, smallClockTopMargin)
        }
    }

    companion object {
        private const val DATE_WEATHER_VIEW_HEIGHT = "date_weather_view_height"
        private const val ENHANCED_SMARTSPACE_HEIGHT = "enhanced_smartspace_height"
    }
}
