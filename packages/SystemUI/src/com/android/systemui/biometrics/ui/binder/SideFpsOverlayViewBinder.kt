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

package com.android.systemui.biometrics.ui.binder

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieProperty
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardPINView
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.FpsUnlockTracker
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.SideFpsSensorInteractor
import com.android.systemui.biometrics.shared.SideFpsControllerRefactor
import com.android.systemui.biometrics.shared.model.AuthenticationReason.NotRunning
import com.android.systemui.biometrics.shared.model.LottieCallback
import com.android.systemui.biometrics.ui.viewmodel.SideFpsOverlayViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.DeviceEntrySideFpsOverlayInteractor
import com.android.systemui.keyguard.ui.viewmodel.SideFpsProgressBarViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Binds the side fingerprint sensor indicator view to [SideFpsOverlayViewModel]. */
@SysUISingleton
class SideFpsOverlayViewBinder
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    private val biometricStatusInteractor: Lazy<BiometricStatusInteractor>,
    private val displayStateInteractor: Lazy<DisplayStateInteractor>,
    private val deviceEntrySideFpsOverlayInteractor: Lazy<DeviceEntrySideFpsOverlayInteractor>,
    private val fpsUnlockTracker: Lazy<FpsUnlockTracker>,
    private val layoutInflater: Lazy<LayoutInflater>,
    private val sideFpsProgressBarViewModel: Lazy<SideFpsProgressBarViewModel>,
    private val sfpsSensorInteractor: Lazy<SideFpsSensorInteractor>,
    private val windowManager: Lazy<WindowManager>
) : CoreStartable {

    override fun start() {
        if (!SideFpsControllerRefactor.isEnabled) {
            return
        }

        applicationScope
            .launch {
                sfpsSensorInteractor.get().isAvailable.collect { isSfpsAvailable ->
                    if (isSfpsAvailable) {
                        combine(
                                biometricStatusInteractor.get().sfpsAuthenticationReason,
                                deviceEntrySideFpsOverlayInteractor
                                    .get()
                                    .showIndicatorForDeviceEntry,
                                sideFpsProgressBarViewModel.get().isVisible,
                                ::Triple
                            )
                            .sample(displayStateInteractor.get().isInRearDisplayMode, ::Pair)
                            .collect { (combinedFlows, isInRearDisplayMode: Boolean) ->
                                val (
                                    systemServerAuthReason,
                                    showIndicatorForDeviceEntry,
                                    progressBarIsVisible) =
                                    combinedFlows
                                if (!isInRearDisplayMode) {
                                    if (progressBarIsVisible) {
                                        hide()
                                    } else if (systemServerAuthReason != NotRunning) {
                                        show()
                                    } else if (showIndicatorForDeviceEntry) {
                                        show()
                                    } else {
                                        hide()
                                    }
                                }
                            }
                    }
                }
            }
            .invokeOnCompletion { fpsUnlockTracker.get().stopTracking() }
    }

    private var overlayView: View? = null
    private var lottie: LottieAnimationView? = null

    /** Show the side fingerprint sensor indicator */
    private fun show() {
        overlayView?.let {
            if (it.isAttachedToWindow) {
                lottie = it.requireViewById<LottieAnimationView>(R.id.sidefps_animation)
                lottie?.pauseAnimation()
                windowManager.get().removeView(it)
            }
        }

        overlayView = layoutInflater.get().inflate(R.layout.sidefps_view, null, false)
        val overlayViewModel =
            SideFpsOverlayViewModel(
                applicationContext,
                biometricStatusInteractor.get(),
                deviceEntrySideFpsOverlayInteractor.get(),
                displayStateInteractor.get(),
                sfpsSensorInteractor.get(),
                sideFpsProgressBarViewModel.get()
            )
        bind(overlayView!!, overlayViewModel, fpsUnlockTracker.get(), windowManager.get())
        overlayView!!.visibility = View.INVISIBLE
        windowManager.get().addView(overlayView, overlayViewModel.defaultOverlayViewParams)
    }

    /** Hide the side fingerprint sensor indicator */
    private fun hide() {
        if (overlayView != null) {
            windowManager.get().removeView(overlayView)
            overlayView = null
        }
    }

    companion object {
        private const val TAG = "SideFpsOverlayViewBinder"

        /** Binds overlayView (side fingerprint sensor indicator view) to SideFpsOverlayViewModel */
        fun bind(
            overlayView: View,
            viewModel: SideFpsOverlayViewModel,
            fpsUnlockTracker: FpsUnlockTracker,
            windowManager: WindowManager
        ) {
            overlayView.repeatWhenAttached {
                fpsUnlockTracker.startTracking()

                val lottie = it.requireViewById<LottieAnimationView>(R.id.sidefps_animation)
                lottie.addLottieOnCompositionLoadedListener { composition: LottieComposition ->
                    viewModel.setLottieBounds(composition.bounds)
                    overlayView.visibility = View.VISIBLE
                }
                it.alpha = 0f
                val overlayShowAnimator =
                    it.animate()
                        .alpha(1f)
                        .setDuration(KeyguardPINView.ANIMATION_DURATION)
                        .setInterpolator(Interpolators.ALPHA_IN)

                overlayShowAnimator.start()

                it.setAccessibilityDelegate(
                    object : View.AccessibilityDelegate() {
                        override fun dispatchPopulateAccessibilityEvent(
                            host: View,
                            event: AccessibilityEvent
                        ): Boolean {
                            return if (
                                event.getEventType() ===
                                    android.view.accessibility.AccessibilityEvent
                                        .TYPE_WINDOW_STATE_CHANGED
                            ) {
                                true
                            } else {
                                super.dispatchPopulateAccessibilityEvent(host, event)
                            }
                        }
                    }
                )

                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.lottieCallbacks.collect { callbacks ->
                            lottie.addOverlayDynamicColor(callbacks)
                        }
                    }

                    launch {
                        viewModel.overlayViewParams.collect { params ->
                            windowManager.updateViewLayout(it, params)
                            lottie.resumeAnimation()
                        }
                    }

                    launch {
                        viewModel.overlayViewProperties.collect { properties ->
                            it.rotation = properties.overlayViewRotation
                            lottie.setAnimation(properties.indicatorAsset)
                        }
                    }
                }
            }
        }
    }
}

private fun LottieAnimationView.addOverlayDynamicColor(colorCallbacks: List<LottieCallback>) {
    addLottieOnCompositionLoadedListener {
        for (callback in colorCallbacks) {
            addValueCallback(callback.keypath, LottieProperty.COLOR_FILTER) {
                PorterDuffColorFilter(callback.color, PorterDuff.Mode.SRC_ATOP)
            }
        }
        resumeAnimation()
    }
}
