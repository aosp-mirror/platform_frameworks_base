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
import android.graphics.drawable.Animatable2
import android.os.VibrationEffect
import android.util.Size
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.animation.CycleInterpolator
import androidx.core.animation.ObjectAnimator
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.Interpolators
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardBottomAreaViewModel,
        falsingManager: FalsingManager?,
        vibratorHelper: VibratorHelper?,
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
                    com.android.internal.R.attr.textColorPrimaryInverse
                } else {
                    com.android.internal.R.attr.textColorPrimary
                },
            )
        )

        view.backgroundTintList =
            if (!viewModel.isSelected) {
                Utils.getColorAttr(
                    view.context,
                    if (viewModel.isActivated) {
                        com.android.internal.R.attr.colorAccentPrimary
                    } else {
                        com.android.internal.R.attr.colorSurface
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
                view.setOnTouchListener(
                    OnTouchListener(
                        view,
                        viewModel,
                        messageDisplayer,
                        vibratorHelper,
                        falsingManager,
                    )
                )
            } else {
                view.setOnClickListener(OnClickListener(viewModel, checkNotNull(falsingManager)))
            }
        } else {
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

    private class OnTouchListener(
        private val view: View,
        private val viewModel: KeyguardQuickAffordanceViewModel,
        private val messageDisplayer: (Int) -> Unit,
        private val vibratorHelper: VibratorHelper?,
        private val falsingManager: FalsingManager?,
    ) : View.OnTouchListener {

        private val longPressDurationMs = ViewConfiguration.getLongPressTimeout().toLong()
        private var longPressAnimator: ViewPropertyAnimator? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent?): Boolean {
            return when (event?.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (viewModel.configKey != null) {
                        if (isUsingAccurateTool(event)) {
                            // For accurate tool types (stylus, mouse, etc.), we don't require a
                            // long-press.
                        } else {
                            // When not using a stylus, we require a long-press to activate the
                            // quick affordance, mostly to do "falsing" (e.g. protect from false
                            // clicks in the pocket/bag).
                            longPressAnimator =
                                view
                                    .animate()
                                    .scaleX(PRESSED_SCALE)
                                    .scaleY(PRESSED_SCALE)
                                    .setDuration(longPressDurationMs)
                                    .withEndAction {
                                        if (
                                            falsingManager
                                                ?.isFalseLongTap(
                                                    FalsingManager.MODERATE_PENALTY
                                                ) == false
                                        ) {
                                            dispatchClick(viewModel.configKey)
                                        }
                                        cancel()
                                    }
                        }
                        true
                    } else {
                        false
                    }
                MotionEvent.ACTION_MOVE -> {
                    if (!isUsingAccurateTool(event)) {
                        // Moving too far while performing a long-press gesture cancels that
                        // gesture.
                        val distanceMoved = distanceMoved(event)
                        if (distanceMoved > ViewConfiguration.getTouchSlop()) {
                            cancel()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isUsingAccurateTool(event)) {
                        // When using an accurate tool type (stylus, mouse, etc.), we don't require
                        // a long-press gesture to activate the quick affordance. Therefore, lifting
                        // the pointer performs a click.
                        if (
                            viewModel.configKey != null &&
                                distanceMoved(event) <= ViewConfiguration.getTouchSlop() &&
                                falsingManager?.isFalseTap(FalsingManager.NO_PENALTY) == false
                        ) {
                            dispatchClick(viewModel.configKey)
                        }
                    } else {
                        // When not using a stylus, lifting the finger/pointer will actually cancel
                        // the long-press gesture. Calling cancel after the quick affordance was
                        // already long-press activated is a no-op, so it's safe to call from here.
                        cancel(
                            onAnimationEnd =
                                if (event.eventTime - event.downTime < longPressDurationMs) {
                                    Runnable {
                                        messageDisplayer.invoke(
                                            R.string.keyguard_affordance_press_too_short
                                        )
                                        val amplitude =
                                            view.context.resources
                                                .getDimensionPixelSize(
                                                    R.dimen.keyguard_affordance_shake_amplitude
                                                )
                                                .toFloat()
                                        val shakeAnimator =
                                            ObjectAnimator.ofFloat(
                                                view,
                                                "translationX",
                                                -amplitude / 2,
                                                amplitude / 2,
                                            )
                                        shakeAnimator.duration =
                                            ShakeAnimationDuration.inWholeMilliseconds
                                        shakeAnimator.interpolator =
                                            CycleInterpolator(ShakeAnimationCycles)
                                        shakeAnimator.start()

                                        vibratorHelper?.vibrate(Vibrations.Shake)
                                    }
                                } else {
                                    null
                                }
                        )
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancel()
                    true
                }
                else -> false
            }
        }

        private fun dispatchClick(
            configKey: String,
        ) {
            view.setOnClickListener {
                vibratorHelper?.vibrate(
                    if (viewModel.isActivated) {
                        Vibrations.Activated
                    } else {
                        Vibrations.Deactivated
                    }
                )
                viewModel.onClicked(
                    KeyguardQuickAffordanceViewModel.OnClickedParameters(
                        configKey = configKey,
                        expandable = Expandable.fromView(view),
                    )
                )
            }
            view.performClick()
            view.setOnClickListener(null)
        }

        private fun cancel(onAnimationEnd: Runnable? = null) {
            longPressAnimator?.cancel()
            longPressAnimator = null
            view.animate().scaleX(1f).scaleY(1f).withEndAction(onAnimationEnd)
        }

        companion object {
            private const val PRESSED_SCALE = 1.5f

            /**
             * Returns `true` if the tool type at the given pointer index is an accurate tool (like
             * stylus or mouse), which means we can trust it to not be a false click; `false`
             * otherwise.
             */
            private fun isUsingAccurateTool(
                event: MotionEvent,
                pointerIndex: Int = 0,
            ): Boolean {
                return when (event.getToolType(pointerIndex)) {
                    MotionEvent.TOOL_TYPE_STYLUS -> true
                    MotionEvent.TOOL_TYPE_MOUSE -> true
                    else -> false
                }
            }

            /**
             * Returns the amount of distance the pointer moved since the historical record at the
             * [since] index.
             */
            private fun distanceMoved(
                event: MotionEvent,
                since: Int = 0,
            ): Float {
                return if (event.historySize > 0) {
                    sqrt(
                        (event.y - event.getHistoricalY(since)).pow(2) +
                            (event.x - event.getHistoricalX(since)).pow(2)
                    )
                } else {
                    0f
                }
            }
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
                        expandable = Expandable.fromView(view),
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

    private val ShakeAnimationDuration = 300.milliseconds
    private val ShakeAnimationCycles = 5f

    object Vibrations {

        private const val SmallVibrationScale = 0.3f
        private const val BigVibrationScale = 0.6f

        val Shake =
            VibrationEffect.startComposition()
                .apply {
                    val vibrationDelayMs =
                        (ShakeAnimationDuration.inWholeMilliseconds / (ShakeAnimationCycles * 2))
                            .toInt()
                    val vibrationCount = ShakeAnimationCycles.toInt() * 2
                    repeat(vibrationCount) {
                        addPrimitive(
                            VibrationEffect.Composition.PRIMITIVE_TICK,
                            SmallVibrationScale,
                            vibrationDelayMs,
                        )
                    }
                }
                .compose()

        val Activated =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    BigVibrationScale,
                    0,
                )
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                    0.1f,
                    0,
                )
                .compose()

        val Deactivated =
            VibrationEffect.startComposition()
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    BigVibrationScale,
                    0,
                )
                .addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                    0.1f,
                    0,
                )
                .compose()
    }
}
