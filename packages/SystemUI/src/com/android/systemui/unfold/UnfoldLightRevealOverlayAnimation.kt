/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.unfold

import android.annotation.BinderThread
import android.content.ContentResolver
import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.Trace
import com.android.systemui.Flags.unfoldAnimationBackgroundProgress
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.LinearLightRevealEffect
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_OPAQUE
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.isVerticalRotation
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation.AddOverlayReason.FOLD
import com.android.systemui.unfold.UnfoldLightRevealOverlayAnimation.AddOverlayReason.UNFOLD
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import com.android.systemui.util.concurrency.ThreadFactory
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Provider

@SysUIUnfoldScope
class UnfoldLightRevealOverlayAnimation
@Inject
constructor(
    private val context: Context,
    private val featureFlags: FeatureFlagsClassic,
    private val contentResolver: ContentResolver,
    @UnfoldBg private val unfoldProgressHandler: Handler,
    @UnfoldBg
    private val unfoldTransitionBgProgressProvider: Provider<UnfoldTransitionProgressProvider>,
    private val unfoldTransitionProgressProvider: Provider<UnfoldTransitionProgressProvider>,
    private val deviceStateManager: DeviceStateManager,
    private val threadFactory: ThreadFactory,
    private val fullscreenLightRevealAnimationControllerFactory:
        FullscreenLightRevealAnimationController.Factory
) : FullscreenLightRevealAnimation {

    private val transitionListener = TransitionListener()
    private var isFolded: Boolean = false
    private var isUnfoldHandled: Boolean = true
    private var overlayAddReason: AddOverlayReason = UNFOLD
    private lateinit var controller: FullscreenLightRevealAnimationController
    private lateinit var bgExecutor: Executor

    override fun init() {
        // This method will be called only on devices where this animation is enabled,
        // so normally this thread won't be created

        controller =
            fullscreenLightRevealAnimationControllerFactory.create(
                displaySelector = { maxByOrNull { it.naturalWidth } },
                effectFactory = { LinearLightRevealEffect(it.isVerticalRotation()) },
                overlayContainerName = SURFACE_CONTAINER_NAME,
            )
        controller.init()
        bgExecutor = threadFactory.buildDelayableExecutorOnHandler(unfoldProgressHandler)
        deviceStateManager.registerCallback(bgExecutor, FoldListener())
        if (unfoldAnimationBackgroundProgress()) {
            unfoldTransitionBgProgressProvider.get().addCallback(transitionListener)
        } else {
            unfoldTransitionProgressProvider.get().addCallback(transitionListener)
        }
    }

    /**
     * Called when screen starts turning on, the contents of the screen might not be visible yet.
     * This method reports back that the overlay is ready in [onOverlayReady] callback.
     *
     * @param onOverlayReady callback when the overlay is drawn and visible on the screen
     * @see [com.android.systemui.keyguard.KeyguardViewMediator]
     */
    @BinderThread
    override fun onScreenTurningOn(onOverlayReady: Runnable) {
        executeInBackground {
            Trace.beginSection("$TAG#onScreenTurningOn")
            try {
                // Add the view only if we are unfolding and this is the first screen on
                if (!isFolded && !isUnfoldHandled && contentResolver.areAnimationsEnabled()) {
                    overlayAddReason = UNFOLD
                    controller.addOverlay(calculateRevealAmount(), onOverlayReady)
                    isUnfoldHandled = true
                } else {
                    // No unfold transition, immediately report that overlay is ready
                    controller.ensureOverlayRemoved()
                    onOverlayReady.run()
                }
            } finally {
                Trace.endSection()
            }
        }
    }

    private fun calculateRevealAmount(animationProgress: Float? = null): Float {
        val overlayAddReason = overlayAddReason

        if (animationProgress == null) {
            // Animation progress unknown, calculate the initial value based on the overlay
            // add reason
            return when (overlayAddReason) {
                FOLD -> ALPHA_TRANSPARENT
                UNFOLD -> ALPHA_OPAQUE
            }
        }

        val showVignetteWhenFolding =
            featureFlags.isEnabled(Flags.ENABLE_DARK_VIGNETTE_WHEN_FOLDING)

        return if (!showVignetteWhenFolding && overlayAddReason == FOLD) {
            // Do not darken the content when SHOW_VIGNETTE_WHEN_FOLDING flag is off
            // and we are folding the device. We still add the overlay to block touches
            // while the animation is running but the overlay is transparent.
            ALPHA_TRANSPARENT
        } else {
            animationProgress
        }
    }

    private inner class TransitionListener :
        UnfoldTransitionProgressProvider.TransitionProgressListener {

        override fun onTransitionProgress(progress: Float) = executeInBackground {
            controller.updateRevealAmount(calculateRevealAmount(progress))
            // When unfolding unblock touches a bit earlier than the animation end as the
            // interpolation has a long tail of very slight movement at the end which should not
            // affect much the usage of the device
            controller.isTouchBlocked =
                overlayAddReason == FOLD || progress < UNFOLD_BLOCK_TOUCHES_UNTIL_PROGRESS
        }

        override fun onTransitionFinished() = executeInBackground {
            controller.ensureOverlayRemoved()
        }

        override fun onTransitionStarted() {
            // Add view for folding case (when unfolding the view is added earlier)
            if (controller.isOverlayVisible()) {
                executeInBackground {
                    overlayAddReason = FOLD
                    controller.addOverlay(calculateRevealAmount())
                }
            }
            // Disable input dispatching during transition.
            InputManagerGlobal.getInstance().cancelCurrentTouch()
        }
    }

    private fun executeInBackground(f: () -> Unit) {
        // This is needed to allow progresses to be received both from the main thread (that will
        // schedule a runnable on the bg thread), and from the bg thread directly (no reposting).
        if (unfoldProgressHandler.looper.isCurrentThread) {
            f()
        } else {
            unfoldProgressHandler.post(f)
        }
    }

    private inner class FoldListener :
        DeviceStateManager.FoldStateListener(
            context,
            Consumer { isFolded ->
                if (isFolded) {
                    controller.ensureOverlayRemoved()
                    isUnfoldHandled = false
                }
                this.isFolded = isFolded
            }
        )

    private enum class AddOverlayReason {
        FOLD,
        UNFOLD
    }

    private companion object {
        const val TAG = "UnfoldLightRevealOverlayAnimation"
        const val SURFACE_CONTAINER_NAME = "unfold-overlay-container"
        const val UNFOLD_BLOCK_TOUCHES_UNTIL_PROGRESS = 0.8f
    }
}
