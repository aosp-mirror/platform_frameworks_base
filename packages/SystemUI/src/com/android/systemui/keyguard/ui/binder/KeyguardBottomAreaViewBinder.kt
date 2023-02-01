/*
 * Copyright (C) 2022 The Android Open Source Project
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
 */

package com.android.systemui.keyguard.ui.binder

import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.Interpolators
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Binds a keyguard bottom area view to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
object KeyguardBottomAreaViewBinder {

    private const val EXIT_DOZE_BUTTON_REVEAL_ANIMATION_DURATION_MS = 250L

    /**
     * Defines interface for an object that acts as the binding between the view and its view-model.
     *
     * Users of the [KeyguardBottomAreaViewBinder] class should use this to control the binder after
     * it is bound.
     */
    interface Binding {
        /**
         * Returns a collection of [ViewPropertyAnimator] instances that can be used to animate the
         * indication areas.
         */
        fun getIndicationAreaAnimators(): List<ViewPropertyAnimator>

        /** Notifies that device configuration has changed. */
        fun onConfigurationChanged()
    }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardBottomAreaViewModel,
        falsingManager: FalsingManager,
    ): Binding {
        val indicationArea: View = view.requireViewById(R.id.keyguard_indication_area)
        val ambientIndicationArea: View? = view.findViewById(R.id.ambient_indication_container)
        val startButton: ImageView = view.requireViewById(R.id.start_button)
        val endButton: ImageView = view.requireViewById(R.id.end_button)
        val overlayContainer: View = view.requireViewById(R.id.overlay_container)
        val indicationText: TextView = view.requireViewById(R.id.keyguard_indication_text)
        val indicationTextBottom: TextView =
            view.requireViewById(R.id.keyguard_indication_text_bottom)

        view.clipChildren = false
        view.clipToPadding = false

        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.startButton.collect { buttonModel ->
                        updateButton(
                            view = startButton,
                            viewModel = buttonModel,
                            falsingManager = falsingManager,
                        )
                    }
                }

                launch {
                    viewModel.endButton.collect { buttonModel ->
                        updateButton(
                            view = endButton,
                            viewModel = buttonModel,
                            falsingManager = falsingManager,
                        )
                    }
                }

                launch {
                    viewModel.isOverlayContainerVisible.collect { isVisible ->
                        overlayContainer.visibility =
                            if (isVisible) {
                                View.VISIBLE
                            } else {
                                View.INVISIBLE
                            }
                    }
                }

                launch {
                    viewModel.alpha.collect { alpha ->
                        view.importantForAccessibility =
                            if (alpha == 0f) {
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            } else {
                                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            }

                        ambientIndicationArea?.alpha = alpha
                        indicationArea.alpha = alpha
                        startButton.alpha = alpha
                        endButton.alpha = alpha
                    }
                }

                launch {
                    viewModel.indicationAreaTranslationX.collect { translationX ->
                        indicationArea.translationX = translationX
                        ambientIndicationArea?.translationX = translationX
                    }
                }

                launch {
                    combine(
                            viewModel.isIndicationAreaPadded,
                            configurationBasedDimensions.map { it.indicationAreaPaddingPx },
                        ) { isPadded, paddingIfPaddedPx ->
                            if (isPadded) {
                                paddingIfPaddedPx
                            } else {
                                0
                            }
                        }
                        .collect { paddingPx ->
                            indicationArea.setPadding(paddingPx, 0, paddingPx, 0)
                        }
                }

                launch {
                    configurationBasedDimensions
                        .map { it.defaultBurnInPreventionYOffsetPx }
                        .flatMapLatest { defaultBurnInOffsetY ->
                            viewModel.indicationAreaTranslationY(defaultBurnInOffsetY)
                        }
                        .collect { translationY ->
                            indicationArea.translationY = translationY
                            ambientIndicationArea?.translationY = translationY
                        }
                }

                launch {
                    configurationBasedDimensions.collect { dimensions ->
                        indicationText.setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            dimensions.indicationTextSizePx.toFloat(),
                        )
                        indicationTextBottom.setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            dimensions.indicationTextSizePx.toFloat(),
                        )

                        startButton.updateLayoutParams<ViewGroup.LayoutParams> {
                            width = dimensions.buttonSizePx.width
                            height = dimensions.buttonSizePx.height
                        }
                        endButton.updateLayoutParams<ViewGroup.LayoutParams> {
                            width = dimensions.buttonSizePx.width
                            height = dimensions.buttonSizePx.height
                        }
                    }
                }
            }
        }

        return object : Binding {
            override fun getIndicationAreaAnimators(): List<ViewPropertyAnimator> {
                return listOf(indicationArea, ambientIndicationArea).mapNotNull { it?.animate() }
            }

            override fun onConfigurationChanged() {
                configurationBasedDimensions.value = loadFromResources(view)
            }
        }
    }

    private fun updateButton(
        view: ImageView,
        viewModel: KeyguardQuickAffordanceViewModel,
        falsingManager: FalsingManager,
    ) {
        if (!viewModel.isVisible) {
            view.isVisible = false
            return
        }

        if (!view.isVisible) {
            view.isVisible = true
            if (viewModel.animateReveal) {
                view.alpha = 0f
                view.translationY = view.height / 2f
                view
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setDuration(EXIT_DOZE_BUTTON_REVEAL_ANIMATION_DURATION_MS)
                    .start()
            }
        }

        when (viewModel.icon) {
            is ContainedDrawable.WithDrawable -> view.setImageDrawable(viewModel.icon.drawable)
            is ContainedDrawable.WithResource -> view.setImageResource(viewModel.icon.resourceId)
        }

        view.drawable.setTint(
            Utils.getColorAttrDefaultColor(
                view.context,
                com.android.internal.R.attr.textColorPrimary
            )
        )
        view.backgroundTintList =
            Utils.getColorAttr(view.context, com.android.internal.R.attr.colorSurface)

        view.contentDescription = view.context.getString(viewModel.contentDescriptionResourceId)
        view.isClickable = viewModel.isClickable
        if (viewModel.isClickable) {
            view.setOnClickListener(OnClickListener(viewModel, falsingManager))
        } else {
            view.setOnClickListener(null)
        }
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
                        animationController = ActivityLaunchAnimator.Controller.fromView(view),
                    )
                )
            }
        }
    }

    private fun loadFromResources(view: View): ConfigurationBasedDimensions {
        return ConfigurationBasedDimensions(
            defaultBurnInPreventionYOffsetPx =
                view.resources.getDimensionPixelOffset(R.dimen.default_burn_in_prevention_offset),
            indicationAreaPaddingPx =
                view.resources.getDimensionPixelOffset(R.dimen.keyguard_indication_area_padding),
            indicationTextSizePx =
                view.resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.text_size_small_material,
                ),
            buttonSizePx =
                Size(
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width),
                    view.resources.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height),
                ),
        )
    }

    private data class ConfigurationBasedDimensions(
        val defaultBurnInPreventionYOffsetPx: Int,
        val indicationAreaPaddingPx: Int,
        val indicationTextSizePx: Int,
        val buttonSizePx: Size,
    )
}
