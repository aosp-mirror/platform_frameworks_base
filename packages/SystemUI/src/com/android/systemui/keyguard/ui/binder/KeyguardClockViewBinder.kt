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

import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View.INVISIBLE
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.helper.widget.Layer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.keyguard.ui.view.layout.sections.ClockSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.clocks.AodClockBurnInModel
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.util.ui.value
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object KeyguardClockViewBinder {
    private val TAG = KeyguardClockViewBinder::class.simpleName!!
    // When changing to new clock, we need to remove old clock views from burnInLayer
    private var lastClock: ClockController? = null

    @JvmStatic
    fun bind(
        clockSection: ClockSection,
        keyguardRootView: ConstraintLayout,
        viewModel: KeyguardClockViewModel,
        keyguardClockInteractor: KeyguardClockInteractor,
        blueprintInteractor: KeyguardBlueprintInteractor,
        rootViewModel: KeyguardRootViewModel,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                keyguardClockInteractor.clockEventController.registerListeners(keyguardRootView)
            }
        }

        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    if (!MigrateClocksToBlueprint.isEnabled) return@launch
                    viewModel.currentClock.collect { currentClock ->
                        cleanupClockViews(currentClock, keyguardRootView, viewModel.burnInLayer)
                        addClockViews(currentClock, keyguardRootView)
                        updateBurnInLayer(keyguardRootView, viewModel, viewModel.clockSize.value)
                        applyConstraints(clockSection, keyguardRootView, true)
                    }
                }

                launch {
                    if (!MigrateClocksToBlueprint.isEnabled) return@launch
                    viewModel.clockSize.collect { clockSize ->
                        updateBurnInLayer(keyguardRootView, viewModel, clockSize)
                        blueprintInteractor.refreshBlueprint(Type.ClockSize)
                    }
                }

                launch {
                    if (!MigrateClocksToBlueprint.isEnabled) return@launch
                    viewModel.clockShouldBeCentered.collect {
                        viewModel.currentClock.value?.let {
                            // TODO(b/301502635): remove "!it.config.useCustomClockScene" when
                            // migrate clocks to blueprint is fully rolled out
                            if (
                                it.largeClock.config.hasCustomPositionUpdatedAnimation &&
                                    !it.config.useCustomClockScene
                            ) {
                                blueprintInteractor.refreshBlueprint(Type.DefaultClockStepping)
                            } else {
                                blueprintInteractor.refreshBlueprint(Type.DefaultTransition)
                            }
                        }
                    }
                }

                launch {
                    if (!MigrateClocksToBlueprint.isEnabled) return@launch
                    combine(
                            viewModel.hasAodIcons,
                            rootViewModel.isNotifIconContainerVisible.map { it.value }
                        ) { hasIcon, isVisible ->
                            hasIcon && isVisible
                        }
                        .distinctUntilChanged()
                        .collect { _ ->
                            viewModel.currentClock.value?.let {
                                if (it.config.useCustomClockScene) {
                                    blueprintInteractor.refreshBlueprint(Type.DefaultTransition)
                                }
                            }
                        }
                }

                launch {
                    if (!MigrateClocksToBlueprint.isEnabled) return@launch
                    rootViewModel.burnInModel.collect { burnInModel ->
                        viewModel.currentClock.value?.let {
                            it.largeClock.layout.applyAodBurnIn(
                                AodClockBurnInModel(
                                    translationX = burnInModel.translationX.toFloat(),
                                    translationY = burnInModel.translationY.toFloat(),
                                    scale = burnInModel.scale
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @VisibleForTesting
    fun updateBurnInLayer(
        keyguardRootView: ConstraintLayout,
        viewModel: KeyguardClockViewModel,
        clockSize: ClockSize,
    ) {
        val burnInLayer = viewModel.burnInLayer
        val clockController = viewModel.currentClock.value
        // Large clocks won't be added to or removed from burn in layer
        // Weather large clock has customized burn in preventing mechanism
        // Non-weather large clock will only scale and translate vertically
        clockController?.let { clock ->
            when (clockSize) {
                ClockSize.LARGE -> {
                    clock.smallClock.layout.views.forEach { burnInLayer?.removeView(it) }
                }
                ClockSize.SMALL -> {
                    clock.smallClock.layout.views.forEach { burnInLayer?.addView(it) }
                }
            }
        }
        viewModel.burnInLayer?.updatePostLayout(keyguardRootView)
    }

    private fun cleanupClockViews(
        currentClock: ClockController?,
        rootView: ConstraintLayout,
        burnInLayer: Layer?
    ) {
        if (lastClock == currentClock) {
            return
        }

        lastClock?.let { clock ->
            clock.smallClock.layout.views.forEach {
                burnInLayer?.removeView(it)
                rootView.removeView(it)
            }
            clock.largeClock.layout.views.forEach { rootView.removeView(it) }
        }
        lastClock = currentClock
    }

    @VisibleForTesting
    fun addClockViews(
        clockController: ClockController?,
        rootView: ConstraintLayout,
    ) {
        // We'll collect the same clock when exiting wallpaper picker without changing clock
        // so we need to remove clock views from parent before addView again
        clockController?.let { clock ->
            clock.smallClock.layout.views.forEach {
                if (it.parent != null) {
                    (it.parent as ViewGroup).removeView(it)
                }
                rootView.addView(it).apply { it.visibility = INVISIBLE }
            }
            clock.largeClock.layout.views.forEach {
                if (it.parent != null) {
                    (it.parent as ViewGroup).removeView(it)
                }
                rootView.addView(it).apply { it.visibility = INVISIBLE }
            }
        }
    }

    fun applyConstraints(
        clockSection: ClockSection,
        rootView: ConstraintLayout,
        animated: Boolean,
        set: TransitionSet? = null,
    ) {
        val constraintSet = ConstraintSet().apply { clone(rootView) }
        clockSection.applyConstraints(constraintSet)
        if (animated) {
            set?.let { TransitionManager.beginDelayedTransition(rootView, it) }
                ?: run { TransitionManager.beginDelayedTransition(rootView) }
        }
        constraintSet.applyTo(rootView)
    }
}
