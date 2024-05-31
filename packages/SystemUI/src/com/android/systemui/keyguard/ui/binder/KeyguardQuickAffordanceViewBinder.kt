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

import android.annotation.SuppressLint
import android.graphics.drawable.Animatable2
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.animation.CycleInterpolator
import androidx.core.animation.ObjectAnimator
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.settingslib.Utils
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableImageView
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.doOnEnd
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** This is only for a SINGLE Quick affordance */
object KeyguardQuickAffordanceViewBinder {

    private const val EXIT_DOZE_BUTTON_REVEAL_ANIMATION_DURATION_MS = 250L
    private const val SCALE_SELECTED_BUTTON = 1.23f
    private const val DIM_ALPHA = 0.3f

    /**
     * Defines interface for an object that acts as the binding between the view and its view-model.
     *
     * Users of the [KeyguardBottomAreaViewBinder] class should use this to control the binder after
     * it is bound.
     */
    interface Binding {
        /** Notifies that device configuration has changed. */
        fun onConfigurationChanged()

        /** Destroys this binding, releases resources, and cancels any coroutines. */
        fun destroy()
    }

    fun bind(
        view: LaunchableImageView,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        alpha: Flow<Float>,
        falsingManager: FalsingManager?,
        vibratorHelper: VibratorHelper?,
        messageDisplayer: (Int) -> Unit,
    ): Binding {
        val button = view as ImageView
        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch("$TAG#viewModel.collect") {
                        viewModel.collect { buttonModel ->
                            updateButton(
                                view = button,
                                viewModel = buttonModel,
                                falsingManager = falsingManager,
                                messageDisplayer = messageDisplayer,
                                vibratorHelper = vibratorHelper,
                            )
                        }
                    }

                    launch("$TAG#updateButtonAlpha") {
                        updateButtonAlpha(
                            view = button,
                            viewModel = viewModel,
                            alphaFlow = alpha,
                        )
                    }

                    launch("$TAG#configurationBasedDimensions") {
                        configurationBasedDimensions.collect { dimensions ->
                            button.updateLayoutParams<ViewGroup.LayoutParams> {
                                width = dimensions.buttonSizePx.width
                                height = dimensions.buttonSizePx.height
                            }
                        }
                    }
                }
            }

        return object : Binding {
            override fun onConfigurationChanged() {
                configurationBasedDimensions.value = loadFromResources(view)
            }

            override fun destroy() {
                view.setOnApplyWindowInsetsListener(null)
                disposableHandle.dispose()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateButton(
        view: ImageView,
        viewModel: KeyguardQuickAffordanceViewModel,
        falsingManager: FalsingManager?,
        messageDisplayer: (Int) -> Unit,
        vibratorHelper: VibratorHelper?,
    ) {
        if (!viewModel.isVisible) {
            view.isInvisible = true
            return
        }

        if (!view.isVisible) {
            view.isVisible = true
        }

        IconViewBinder.bind(viewModel.icon, view)

        (view.drawable as? Animatable2)?.let { animatable ->
            (viewModel.icon as? Icon.Resource)?.res?.let { iconResourceId ->
                // Always start the animation (we do call stop() below, if we need to skip it).
                animatable.start()

                if (view.tag != iconResourceId) {
                    // Here when we haven't run the animation on a previous update.
                    //
                    // Save the resource ID for next time, so we know not to re-animate the same
                    // animation again.
                    view.tag = iconResourceId
                } else {
                    // Here when we've already done this animation on a previous update and want to
                    // skip directly to the final frame of the animation to avoid running it.
                    //
                    // By calling stop after start, we go to the final frame of the animation.
                    animatable.stop()
                }
            }
        }

        view.isActivated = viewModel.isActivated
        view.drawable.setTint(
            Utils.getColorAttrDefaultColor(
                view.context,
                if (viewModel.isActivated) {
                    com.android.internal.R.attr.materialColorOnPrimaryFixed
                } else {
                    com.android.internal.R.attr.materialColorOnSurface
                },
            )
        )

        view.backgroundTintList =
            if (!viewModel.isSelected) {
                Utils.getColorAttr(
                    view.context,
                    if (viewModel.isActivated) {
                        com.android.internal.R.attr.materialColorPrimaryFixed
                    } else {
                        com.android.internal.R.attr.materialColorSurfaceContainerHigh
                    }
                )
            } else {
                null
            }
        view
            .animate()
            .scaleX(if (viewModel.isSelected) SCALE_SELECTED_BUTTON else 1f)
            .scaleY(if (viewModel.isSelected) SCALE_SELECTED_BUTTON else 1f)
            .start()

        view.isClickable = viewModel.isClickable
        if (viewModel.isClickable) {
            if (viewModel.useLongPress) {
                val onTouchListener =
                    KeyguardQuickAffordanceOnTouchListener(
                        view,
                        viewModel,
                        messageDisplayer,
                        vibratorHelper,
                        falsingManager,
                    )
                view.setOnTouchListener(onTouchListener)
                view.setOnClickListener {
                    messageDisplayer.invoke(R.string.keyguard_affordance_press_too_short)
                    val amplitude =
                        view.context.resources
                            .getDimensionPixelSize(R.dimen.keyguard_affordance_shake_amplitude)
                            .toFloat()
                    val shakeAnimator =
                        ObjectAnimator.ofFloat(
                            view,
                            "translationX",
                            -amplitude / 2,
                            amplitude / 2,
                        )
                    shakeAnimator.duration =
                        KeyguardBottomAreaVibrations.ShakeAnimationDuration.inWholeMilliseconds
                    shakeAnimator.interpolator =
                        CycleInterpolator(KeyguardBottomAreaVibrations.ShakeAnimationCycles)
                    shakeAnimator.doOnEnd { view.translationX = 0f }
                    shakeAnimator.start()

                    vibratorHelper?.vibrate(KeyguardBottomAreaVibrations.Shake)
                }
                view.onLongClickListener =
                    OnLongClickListener(falsingManager, viewModel, vibratorHelper, onTouchListener)
            } else {
                view.setOnClickListener(OnClickListener(viewModel, checkNotNull(falsingManager)))
            }
        } else {
            view.onLongClickListener = null
            view.setOnClickListener(null)
            view.setOnTouchListener(null)
        }

        view.isSelected = viewModel.isSelected
    }

    private suspend fun updateButtonAlpha(
        view: View,
        viewModel: Flow<KeyguardQuickAffordanceViewModel>,
        alphaFlow: Flow<Float>,
    ) {
        combine(viewModel.map { it.isDimmed }, alphaFlow) { isDimmed, alpha ->
                if (isDimmed) DIM_ALPHA else alpha
            }
            .collect { view.alpha = it }
    }

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            buttonSizePx =
                Size(
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width),
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height),
                ),
        )
    }

    private class OnClickListener(
        private val viewModel: KeyguardQuickAffordanceViewModel,
        private val falsingManager: FalsingManager,
    ) : View.OnClickListener {
        override fun onClick(view: View) {
            if (falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return
            }

            if (viewModel.configKey != null) {
                viewModel.onClicked(
                    KeyguardQuickAffordanceViewModel.OnClickedParameters(
                        configKey = viewModel.configKey,
                        expandable = Expandable.fromView(view),
                        slotId = viewModel.slotId,
                    )
                )
            }
        }
    }

    private class OnLongClickListener(
        private val falsingManager: FalsingManager?,
        private val viewModel: KeyguardQuickAffordanceViewModel,
        private val vibratorHelper: VibratorHelper?,
        private val onTouchListener: KeyguardQuickAffordanceOnTouchListener
    ) : View.OnLongClickListener {
        override fun onLongClick(view: View): Boolean {
            if (falsingManager?.isFalseLongTap(FalsingManager.MODERATE_PENALTY) == true) {
                return true
            }

            if (viewModel.configKey != null) {
                viewModel.onClicked(
                    KeyguardQuickAffordanceViewModel.OnClickedParameters(
                        configKey = viewModel.configKey,
                        expandable = Expandable.fromView(view),
                        slotId = viewModel.slotId,
                    )
                )
                vibratorHelper?.vibrate(
                    if (viewModel.isActivated) {
                        KeyguardBottomAreaVibrations.Activated
                    } else {
                        KeyguardBottomAreaVibrations.Deactivated
                    }
                )
            }

            onTouchListener.cancel()
            return true
        }

        override fun onLongClickUseDefaultHapticFeedback(view: View) = false
    }

    private data class ConfigurationBasedDimensions(
        val buttonSizePx: Size,
    )

    private const val TAG = "KeyguardQuickAffordanceViewBinder"
}
