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
 */

package com.android.systemui.biometrics.ui.binder

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.hardware.biometrics.BiometricOverlayConstants
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.android.systemui.R
import com.android.systemui.biometrics.ui.viewmodel.SideFpsOverlayViewModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.launch

/** Sub-binder for SideFpsOverlayView. */
object SideFpsOverlayViewBinder {

    /** Bind the view. */
    @JvmStatic
    fun bind(
        view: View,
        viewModel: SideFpsOverlayViewModel,
        overlayViewParams: WindowManager.LayoutParams,
        @BiometricOverlayConstants.ShowReason reason: Int,
        @Application context: Context
    ) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val lottie = view.findViewById(R.id.sidefps_animation) as LottieAnimationView

        viewModel.changeDisplay()

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sideFpsAnimationRotation.collect { rotation ->
                        view.rotation = rotation
                    }
                }

                launch {
                    // TODO(b/221037350, wenhuiy): Create a separate ViewBinder for sideFpsAnimation
                    // in order to add scuba tests in the future.
                    viewModel.sideFpsAnimation.collect { animation ->
                        lottie.setAnimation(animation)
                    }
                }

                launch {
                    viewModel.sensorBounds.collect { sensorBounds ->
                        overlayViewParams.x = sensorBounds.left
                        overlayViewParams.y = sensorBounds.top

                        windowManager.updateViewLayout(view, overlayViewParams)
                    }
                }

                launch {
                    viewModel.overlayOffsets.collect { overlayOffsets ->
                        lottie.addLottieOnCompositionLoadedListener {
                            viewModel.updateSensorBounds(
                                it.bounds,
                                windowManager.maximumWindowMetrics.bounds,
                                overlayOffsets
                            )
                        }
                    }
                }
            }
        }

        lottie.addOverlayDynamicColor(context, reason)

        /**
         * Intercepts TYPE_WINDOW_STATE_CHANGED accessibility event, preventing Talkback from
         * speaking @string/accessibility_fingerprint_label twice when sensor location indicator is
         * in focus
         */
        view.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun dispatchPopulateAccessibilityEvent(
                    host: View,
                    event: AccessibilityEvent
                ): Boolean {
                    return if (
                        event.getEventType() === AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    ) {
                        true
                    } else {
                        super.dispatchPopulateAccessibilityEvent(host, event)
                    }
                }
            }
    }
}

private fun LottieAnimationView.addOverlayDynamicColor(
    context: Context,
    @BiometricOverlayConstants.ShowReason reason: Int
) {
    fun update() {
        val isKeyguard = reason == BiometricOverlayConstants.REASON_AUTH_KEYGUARD
        if (isKeyguard) {
            val color = context.getColor(R.color.numpad_key_color_secondary) // match bouncer color
            val chevronFill =
                com.android.settingslib.Utils.getColorAttrDefaultColor(
                    context,
                    android.R.attr.textColorPrimaryInverse
                )
            for (key in listOf(".blue600", ".blue400")) {
                addValueCallback(KeyPath(key, "**"), LottieProperty.COLOR_FILTER) {
                    PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                }
            }
            addValueCallback(KeyPath(".black", "**"), LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(chevronFill, PorterDuff.Mode.SRC_ATOP)
            }
        } else if (!isDarkMode(context)) {
            addValueCallback(KeyPath(".black", "**"), LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            }
        } else if (isDarkMode(context)) {
            for (key in listOf(".blue600", ".blue400")) {
                addValueCallback(KeyPath(key, "**"), LottieProperty.COLOR_FILTER) {
                    PorterDuffColorFilter(
                        context.getColor(R.color.settingslib_color_blue400),
                        PorterDuff.Mode.SRC_ATOP
                    )
                }
            }
        }
    }

    if (composition != null) {
        update()
    } else {
        addLottieOnCompositionLoadedListener { update() }
    }
}

private fun isDarkMode(context: Context): Boolean {
    val darkMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return darkMode == Configuration.UI_MODE_NIGHT_YES
}
