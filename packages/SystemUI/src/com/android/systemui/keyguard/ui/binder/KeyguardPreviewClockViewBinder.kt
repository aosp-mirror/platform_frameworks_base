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

import android.content.res.Resources
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.keyguard.ui.preview.KeyguardPreviewRenderer
import com.android.systemui.keyguard.ui.view.layout.sections.setVisibility
import com.android.systemui.keyguard.ui.viewmodel.KeyguardPreviewClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockPreviewConfig
import com.android.systemui.shared.clocks.ClockRegistry

/** Binder for the small clock view, large clock view. */
object KeyguardPreviewClockViewBinder {
    val lockId = View.generateViewId()

    @JvmStatic
    fun bind(
        largeClockHostView: View,
        smallClockHostView: View,
        viewModel: KeyguardPreviewClockViewModel,
    ) {
        largeClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.isLargeClockVisible") {
                    viewModel.isLargeClockVisible.collect { largeClockHostView.isVisible = it }
                }
            }
        }

        smallClockHostView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.isSmallClockVisible") {
                    viewModel.isSmallClockVisible.collect { smallClockHostView.isVisible = it }
                }
            }
        }
    }

    @JvmStatic
    fun bind(
        rootView: ConstraintLayout,
        viewModel: KeyguardPreviewClockViewModel,
        clockRegistry: ClockRegistry,
        updateClockAppearance: suspend (ClockController, Resources) -> Unit,
        clockPreviewConfig: ClockPreviewConfig,
    ) {
        rootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastClock: ClockController? = null
                launch("$TAG#viewModel.previewClock") {
                        viewModel.previewClock.collect { currentClock ->
                            lastClock?.let { clock ->
                                (clock.largeClock.layout.views + clock.smallClock.layout.views)
                                    .forEach { rootView.removeView(it) }
                            }
                            lastClock = currentClock
                            updateClockAppearance(
                                currentClock,
                                clockPreviewConfig.previewContext.resources,
                            )

                            if (viewModel.shouldHighlightSelectedAffordance) {
                                (currentClock.largeClock.layout.views +
                                        currentClock.smallClock.layout.views)
                                    .forEach { it.alpha = KeyguardPreviewRenderer.DIM_ALPHA }
                            }
                            currentClock.largeClock.layout.views.forEach {
                                (it.parent as? ViewGroup)?.removeView(it)
                                rootView.addView(it)
                            }

                            currentClock.smallClock.layout.views.forEach {
                                (it.parent as? ViewGroup)?.removeView(it)
                                rootView.addView(it)
                            }
                            applyPreviewConstraints(
                                clockPreviewConfig,
                                rootView,
                                currentClock,
                                viewModel,
                            )
                        }
                    }
                    .invokeOnCompletion {
                        // recover seed color especially for Transit clock
                        lastClock?.apply {
                            smallClock.run {
                                events.onThemeChanged(
                                    theme.copy(seedColor = clockRegistry.seedColor)
                                )
                            }
                            largeClock.run {
                                events.onThemeChanged(
                                    theme.copy(seedColor = clockRegistry.seedColor)
                                )
                            }
                        }
                        lastClock = null
                    }
            }
        }
    }

    private fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        rootView: ConstraintLayout,
        previewClock: ClockController,
        viewModel: KeyguardPreviewClockViewModel,
    ) {
        val cs = ConstraintSet().apply { clone(rootView) }
        previewClock.largeClock.layout.applyPreviewConstraints(clockPreviewConfig, cs)
        previewClock.smallClock.layout.applyPreviewConstraints(clockPreviewConfig, cs)

        // When selectedClockSize is the initial value, make both clocks invisible to avoid
        // flickering
        val largeClockVisibility =
            when (viewModel.selectedClockSize.value) {
                ClockSizeSetting.DYNAMIC -> VISIBLE
                ClockSizeSetting.SMALL -> INVISIBLE
                null -> INVISIBLE
            }
        val smallClockVisibility =
            when (viewModel.selectedClockSize.value) {
                ClockSizeSetting.DYNAMIC -> INVISIBLE
                ClockSizeSetting.SMALL -> VISIBLE
                null -> INVISIBLE
            }
        cs.apply {
            setVisibility(previewClock.largeClock.layout.views, largeClockVisibility)
            setVisibility(previewClock.smallClock.layout.views, smallClockVisibility)
        }
        cs.applyTo(rootView)
    }

    private const val TAG = "KeyguardPreviewClockViewBinder"
}
