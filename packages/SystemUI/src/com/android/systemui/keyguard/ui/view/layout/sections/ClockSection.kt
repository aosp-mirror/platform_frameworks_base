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
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.GONE
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VISIBLE
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.systemui.customization.R as custR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.binder.KeyguardClockViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockFaceLayout
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import dagger.Lazy
import javax.inject.Inject

internal fun ConstraintSet.setVisibility(
    views: Iterable<View>,
    visibility: Int,
) = views.forEach { view -> this.setVisibility(view.id, visibility) }

internal fun ConstraintSet.setAlpha(
    views: Iterable<View>,
    alpha: Float,
) = views.forEach { view -> this.setAlpha(view.id, alpha) }

@SysUISingleton
class ClockSection
@Inject
constructor(
    private val clockInteractor: KeyguardClockInteractor,
    protected val keyguardClockViewModel: KeyguardClockViewModel,
    private val context: Context,
    val smartspaceViewModel: KeyguardSmartspaceViewModel,
    val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val rootViewModel: KeyguardRootViewModel,
) : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {}
    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        KeyguardClockViewBinder.bind(
            this,
            constraintLayout,
            keyguardClockViewModel,
            clockInteractor,
            blueprintInteractor.get(),
            rootViewModel,
        )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        keyguardClockViewModel.currentClock.value?.let { clock ->
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
        getNonTargetClockFace(clock).applyConstraints(constraintSet)
        getTargetClockFace(clock).applyConstraints(constraintSet)

        // Add constraint between elements in clock and clock container
        return constraintSet.apply {
            setVisibility(getTargetClockFace(clock).views, VISIBLE)
            setVisibility(getNonTargetClockFace(clock).views, GONE)
            setAlpha(getTargetClockFace(clock).views, 1F)
            setAlpha(getNonTargetClockFace(clock).views, 0F)
            if (!keyguardClockViewModel.isLargeClockVisible.value) {
                connect(sharedR.id.bc_smartspace_view, TOP, sharedR.id.date_smartspace_view, BOTTOM)
            }
        }
    }

    private fun getTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.largeClock.layout
        else clock.smallClock.layout
    private fun getNonTargetClockFace(clock: ClockController): ClockFaceLayout =
        if (keyguardClockViewModel.isLargeClockVisible.value) clock.smallClock.layout
        else clock.largeClock.layout

    fun constrainWeatherClockDateIconsBarrier(constraints: ConstraintSet) {
        constraints.apply {
            if (keyguardClockViewModel.isAodIconsVisible.value) {
                createBarrier(
                    R.id.weather_clock_date_and_icons_barrier_bottom,
                    Barrier.BOTTOM,
                    0,
                    *intArrayOf(sharedR.id.bc_smartspace_view, R.id.aod_notification_icon_container)
                )
            } else {
                if (smartspaceViewModel.bcSmartspaceVisibility.value == VISIBLE) {
                    createBarrier(
                        R.id.weather_clock_date_and_icons_barrier_bottom,
                        Barrier.BOTTOM,
                        0,
                        (sharedR.id.bc_smartspace_view)
                    )
                } else {
                    createBarrier(
                        R.id.weather_clock_date_and_icons_barrier_bottom,
                        Barrier.BOTTOM,
                        getDimen(ENHANCED_SMARTSPACE_HEIGHT),
                        (R.id.lockscreen_clock_view)
                    )
                }
            }
        }
    }

    open fun applyDefaultConstraints(constraints: ConstraintSet) {
        val guideline =
            if (keyguardClockViewModel.clockShouldBeCentered.value) PARENT_ID
            else R.id.split_shade_guideline
        constraints.apply {
            connect(R.id.lockscreen_clock_view_large, START, PARENT_ID, START)
            connect(R.id.lockscreen_clock_view_large, END, guideline, END)
            connect(R.id.lockscreen_clock_view_large, BOTTOM, R.id.device_entry_icon_view, TOP)
            val largeClockTopMargin =
                keyguardClockViewModel.getLargeClockTopMargin() +
                    getDimen(DATE_WEATHER_VIEW_HEIGHT) +
                    getDimen(ENHANCED_SMARTSPACE_HEIGHT)
            connect(R.id.lockscreen_clock_view_large, TOP, PARENT_ID, TOP, largeClockTopMargin)
            constrainWidth(R.id.lockscreen_clock_view_large, WRAP_CONTENT)
            constrainHeight(R.id.lockscreen_clock_view_large, MATCH_CONSTRAINT)
            constrainWidth(R.id.lockscreen_clock_view, WRAP_CONTENT)
            constrainHeight(
                R.id.lockscreen_clock_view,
                context.resources.getDimensionPixelSize(custR.dimen.small_clock_height)
            )
            connect(
                R.id.lockscreen_clock_view,
                START,
                PARENT_ID,
                START,
                context.resources.getDimensionPixelSize(custR.dimen.clock_padding_start) +
                    context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
            )
            val smallClockTopMargin = keyguardClockViewModel.getSmallClockTopMargin()
            create(R.id.small_clock_guideline_top, ConstraintSet.HORIZONTAL_GUIDELINE)
            setGuidelineBegin(R.id.small_clock_guideline_top, smallClockTopMargin)
            connect(R.id.lockscreen_clock_view, TOP, R.id.small_clock_guideline_top, BOTTOM)
        }

        constrainWeatherClockDateIconsBarrier(constraints)
    }

    private fun getDimen(name: String): Int {
        return getDimen(context, name)
    }

    companion object {
        private const val DATE_WEATHER_VIEW_HEIGHT = "date_weather_view_height"
        private const val ENHANCED_SMARTSPACE_HEIGHT = "enhanced_smartspace_height"
        fun getDimen(context: Context, name: String): Int {
            val res = context.packageManager.getResourcesForApplication(context.packageName)
            val id = res.getIdentifier(name, "dimen", context.packageName)
            return if (id == 0) 0 else res.getDimensionPixelSize(id)
        }
    }
}
