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

package com.android.systemui.keyguard.ui.binder

import android.content.Context
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.keyguard.ClockEventController
import com.android.systemui.customization.R as customizationR
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.keyguard.ui.preview.KeyguardPreviewRenderer
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection.Companion.getDimen
import com.android.systemui.keyguard.ui.view.layout.sections.setVisibility
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.res.R
import com.android.systemui.util.Utils
import kotlin.reflect.KSuspendFunction1
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Binder for the small clock view, large clock view. */
object KeyguardPreviewClockViewBinder {

    @JvmStatic
    fun bind(
        largeClockHostView: View,
        smallClockHostView: View,
        viewModel: KeyguardPreviewClockViewModel,
    ) {
        largeClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLargeClockVisible.collect { largeClockHostView.isVisible = it }
            }
        }

        smallClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSmallClockVisible.collect { smallClockHostView.isVisible = it }
            }
        }
    }

    @JvmStatic
    fun bind(
        context: Context,
        displayId: Int,
        rootView: ConstraintLayout,
        viewModel: KeyguardPreviewClockViewModel,
        clockEventController: ClockEventController,
        updateClockAppearance: KSuspendFunction1<ClockController, Unit>,
    ) {
        // TODO(b/327668072): When this function is called multiple times, the clock view can be
        //                    gone due to a race condition on removeView and addView.
        rootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.selectedClockSize, viewModel.previewClockPair) { _, clock ->
                            clock
                        }
                        .collect { previewClockPair ->
                            viewModel.lastClockPair?.let { clockPair ->
                                (clockPair.first.largeClock.layout.views +
                                        clockPair.first.smallClock.layout.views)
                                    .forEach { rootView.removeView(it) }
                                (clockPair.second.largeClock.layout.views +
                                        clockPair.second.smallClock.layout.views)
                                    .forEach { rootView.removeView(it) }
                            }
                            viewModel.lastClockPair = previewClockPair
                            val clockPreview =
                                if (displayId == 0) previewClockPair.first
                                else previewClockPair.second
                            clockEventController.clock = clockPreview
                            updateClockAppearance(clockPreview)

                            if (viewModel.shouldHighlightSelectedAffordance) {
                                (clockPreview.largeClock.layout.views +
                                        clockPreview.smallClock.layout.views)
                                    .forEach { it.alpha = KeyguardPreviewRenderer.DIM_ALPHA }
                            }
                            clockPreview.largeClock.layout.views.forEach {
                                (it.parent as? ViewGroup)?.removeView(it)
                                rootView.addView(it)
                            }

                            clockPreview.smallClock.layout.views.forEach {
                                (it.parent as? ViewGroup)?.removeView(it)
                                rootView.addView(it)
                            }
                            applyPreviewConstraints(context, rootView, viewModel)
                        }
                }
            }
        }
    }

    private fun applyClockDefaultConstraints(context: Context, constraints: ConstraintSet) {
        constraints.apply {
            constrainWidth(R.id.lockscreen_clock_view_large, ConstraintSet.WRAP_CONTENT)
            constrainHeight(R.id.lockscreen_clock_view_large, ConstraintSet.WRAP_CONTENT)
            val largeClockTopMargin =
                context.resources.getDimensionPixelSize(R.dimen.status_bar_height) +
                    context.resources.getDimensionPixelSize(
                        customizationR.dimen.small_clock_padding_top
                    ) +
                    context.resources.getDimensionPixelSize(
                        R.dimen.keyguard_smartspace_top_offset
                    ) +
                    getDimen(context, DATE_WEATHER_VIEW_HEIGHT) +
                    getDimen(context, ENHANCED_SMARTSPACE_HEIGHT)
            connect(R.id.lockscreen_clock_view_large, TOP, PARENT_ID, TOP, largeClockTopMargin)
            connect(R.id.lockscreen_clock_view_large, START, PARENT_ID, START)
            connect(
                R.id.lockscreen_clock_view_large,
                ConstraintSet.END,
                PARENT_ID,
                ConstraintSet.END
            )

            connect(R.id.lockscreen_clock_view_large, BOTTOM, R.id.lock_icon_view, TOP)
            constrainWidth(R.id.lockscreen_clock_view, WRAP_CONTENT)
            constrainHeight(
                R.id.lockscreen_clock_view,
                context.resources.getDimensionPixelSize(customizationR.dimen.small_clock_height)
            )
            connect(
                R.id.lockscreen_clock_view,
                START,
                PARENT_ID,
                START,
                context.resources.getDimensionPixelSize(customizationR.dimen.clock_padding_start) +
                    context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
            )
            val smallClockTopMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                    Utils.getStatusBarHeaderHeightKeyguard(context)
            connect(R.id.lockscreen_clock_view, TOP, PARENT_ID, TOP, smallClockTopMargin)
        }
    }

    private fun applyPreviewConstraints(
        context: Context,
        rootView: ConstraintLayout,
        viewModel: KeyguardPreviewClockViewModel
    ) {
        val cs = ConstraintSet().apply { clone(rootView) }
        val clockPair = viewModel.previewClockPair.value
        applyClockDefaultConstraints(context, cs)
        clockPair.first.largeClock.layout.applyPreviewConstraints(cs)
        clockPair.first.smallClock.layout.applyPreviewConstraints(cs)
        clockPair.second.largeClock.layout.applyPreviewConstraints(cs)
        clockPair.second.smallClock.layout.applyPreviewConstraints(cs)

        // When selectedClockSize is the initial value, make both clocks invisible to avoid
        // flickering
        val largeClockVisibility =
            when (viewModel.selectedClockSize.value) {
                SettingsClockSize.DYNAMIC -> VISIBLE
                SettingsClockSize.SMALL -> INVISIBLE
                null -> INVISIBLE
            }
        val smallClockVisibility =
            when (viewModel.selectedClockSize.value) {
                SettingsClockSize.DYNAMIC -> INVISIBLE
                SettingsClockSize.SMALL -> VISIBLE
                null -> INVISIBLE
            }

        cs.apply {
            setVisibility(clockPair.first.largeClock.layout.views, largeClockVisibility)
            setVisibility(clockPair.first.smallClock.layout.views, smallClockVisibility)
            setVisibility(clockPair.second.largeClock.layout.views, largeClockVisibility)
            setVisibility(clockPair.second.smallClock.layout.views, smallClockVisibility)
        }
        cs.applyTo(rootView)
    }

    private const val DATE_WEATHER_VIEW_HEIGHT = "date_weather_view_height"
    private const val ENHANCED_SMARTSPACE_HEIGHT = "enhanced_smartspace_height"
}
