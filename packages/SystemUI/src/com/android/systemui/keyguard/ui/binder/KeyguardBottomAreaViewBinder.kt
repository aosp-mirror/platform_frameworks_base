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

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Animatable2
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.view.LaunchableLinearLayout
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
@OptIn(ExperimentalCoroutinesApi::class)
object KeyguardBottomAreaViewBinder {

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
        /**
         * Returns a collection of [ViewPropertyAnimator] instances that can be used to animate the
         * indication areas.
         */
        fun getIndicationAreaAnimators(): List<ViewPropertyAnimator>

        /** Notifies that device configuration has changed. */
        fun onConfigurationChanged()

        /**
         * Returns whether the keyguard bottom area should be constrained to the top of the lock
         * icon
         */
        fun shouldConstrainToTopOfLockIcon(): Boolean

        /** Destroys this binding, releases resources, and cancels any coroutines. */
        fun destroy()
    }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardBottomAreaViewModel,
        falsingManager: FalsingManager?,
        vibratorHelper: VibratorHelper?,
        activityStarter: ActivityStarter?,
        messageDisplayer: (Int) -> Unit,
    ): Binding {
        val indicationArea: View = view.requireViewById(R.id.keyguard_indication_area)
        val ambientIndicationArea: View? = view.findViewById(R.id.ambient_indication_container)
        val startButton: ImageView = view.requireViewById(R.id.start_button)
        val endButton: ImageView = view.requireViewById(R.id.end_button)
        val overlayContainer: View = view.requireViewById(R.id.overlay_container)
        val indicationText: TextView = view.requireViewById(R.id.keyguard_indication_text)
        val indicationTextBottom: TextView =
            view.requireViewById(R.id.keyguard_indication_text_bottom)
        val settingsMenu: LaunchableLinearLayout =
            view.requireViewById(R.id.keyguard_settings_button)

        view.clipChildren = false
        view.clipToPadding = false
        view.setOnTouchListener { _, event ->
            if (settingsMenu.isVisible) {
                val hitRect = Rect()
                settingsMenu.getHitRect(hitRect)
                if (!hitRect.contains(event.x.toInt(), event.y.toInt())) {
                    viewModel.onTouchedOutsideLockScreenSettingsMenu()
                }
            }

            false
        }

        val configurationBasedDimensions = MutableStateFlow(loadFromResources(view))

        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.startButton.collect { buttonModel ->
                            updateButton(
                                view = startButton,
                                viewModel = buttonModel,
                                falsingManager = falsingManager,
                                messageDisplayer = messageDisplayer,
                                vibratorHelper = vibratorHelper,
                            )
                        }
                    }

                    launch {
                        viewModel.endButton.collect { buttonModel ->
                            updateButton(
                                view = endButton,
                                viewModel = buttonModel,
                                falsingManager = falsingManager,
                                messageDisplayer = messageDisplayer,
                                vibratorHelper = vibratorHelper,
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
                        }
                    }

                    launch {
                        updateButtonAlpha(
                            view = startButton,
                            viewModel = viewModel.startButton,
                            alphaFlow = viewModel.alpha,
                        )
                    }

                    launch {
                        updateButtonAlpha(
                            view = endButton,
                            viewModel = viewModel.endButton,
                            alphaFlow = viewModel.alpha,
                        )
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

                    launch {
                        viewModel.settingsMenuViewModel.isVisible.distinctUntilChanged().collect {
                            isVisible ->
                            settingsMenu.animateVisibility(visible = isVisible)
                            if (isVisible) {
                                vibratorHelper?.vibrate(KeyguardBottomAreaVibrations.Activated)
                                settingsMenu.setOnTouchListener(
                                    KeyguardSettingsButtonOnTouchListener(
                                        view = settingsMenu,
                                        viewModel = viewModel.settingsMenuViewModel,
                                    )
                                )
                                IconViewBinder.bind(
                                    icon = viewModel.settingsMenuViewModel.icon,
                                    view = settingsMenu.requireViewById(R.id.icon),
                                )
                                TextViewBinder.bind(
                                    view = settingsMenu.requireViewById(R.id.text),
                                    viewModel = viewModel.settingsMenuViewModel.text,
                                )
                            }
                        }
                    }

                    // activityStarter will only be null when rendering the preview that
                    // shows up in the Wallpaper Picker app. If we do that, then the
                    // settings menu should never be visible.
                    if (activityStarter != null) {
                        launch {
                            viewModel.settingsMenuViewModel.shouldOpenSettings
                                .filter { it }
                                .collect {
                                    navigateToLockScreenSettings(
                                        activityStarter = activityStarter,
                                        view = settingsMenu,
                                    )
                                    viewModel.settingsMenuViewModel.onSettingsShown()
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

            override fun shouldConstrainToTopOfLockIcon(): Boolean =
                viewModel.shouldConstrainToTopOfLockIcon()

            override fun destroy() {
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
                val onTouchListener = KeyguardQuickAffordanceOnTouchListener(
                    view,
                    viewModel,
                    messageDisplayer,
                    vibratorHelper,
                    falsingManager,
                )
                view.setOnTouchListener(onTouchListener)
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

    private fun View.animateVisibility(visible: Boolean) {
        animate()
            .withStartAction {
                if (visible) {
                    alpha = 0f
                    isVisible = true
                }
            }
            .alpha(if (visible) 1f else 0f)
            .withEndAction {
                if (!visible) {
                    isVisible = false
                }
            }
            .start()
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

    /** Opens the wallpaper picker screen after the device is unlocked by the user. */
    private fun navigateToLockScreenSettings(
        activityStarter: ActivityStarter,
        view: View,
    ) {
        activityStarter.postStartActivityDismissingKeyguard(
            Intent(Intent.ACTION_SET_WALLPAPER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                view.context
                    .getString(R.string.config_wallpaperPickerPackage)
                    .takeIf { it.isNotEmpty() }
                    ?.let { packageName -> setPackage(packageName) }
            },
            /* delay= */ 0,
            /* animationController= */ ActivityLaunchAnimator.Controller.fromView(view),
            /* customMessage= */ view.context.getString(R.string.keyguard_unlock_to_customize_ls)
        )
    }

    private data class ConfigurationBasedDimensions(
        val defaultBurnInPreventionYOffsetPx: Int,
        val indicationAreaPaddingPx: Int,
        val indicationTextSizePx: Int,
        val buttonSizePx: Size,
    )
}
