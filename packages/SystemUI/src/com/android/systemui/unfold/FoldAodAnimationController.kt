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
import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.view.OneShotPreDrawListener
import com.android.internal.util.LatencyTracker
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.ToAodFoldTransitionInteractor
import com.android.systemui.shade.ShadeFoldAnimator
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.ScreenOffAnimation
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.unfold.FoldAodAnimationController.FoldAodAnimationStatus
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.GlobalSettings
import dagger.Lazy
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Controls folding to AOD animation: when AOD is enabled and foldable device is folded we play a
 * special AOD animation on the outer screen
 */
@SysUIUnfoldScope
class FoldAodAnimationController
@Inject
constructor(
    @Main private val mainExecutor: DelayableExecutor,
    private val context: Context,
    private val deviceStateManager: DeviceStateManager,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val globalSettings: GlobalSettings,
    private val latencyTracker: LatencyTracker,
    private val keyguardInteractor: Lazy<KeyguardInteractor>,
    private val foldTransitionInteractor: Lazy<ToAodFoldTransitionInteractor>,
) : CallbackController<FoldAodAnimationStatus>, ScreenOffAnimation, WakefulnessLifecycle.Observer {

    private lateinit var shadeViewController: ShadeViewController

    private var isFolded = false
    private var isFoldHandled = true

    private var alwaysOnEnabled: Boolean = false
    private var isScrimOpaque: Boolean = false
    private var pendingScrimReadyCallback: Runnable? = null

    private var shouldPlayAnimation = false
    private var isAnimationPlaying = false
    private var cancelAnimation: Runnable? = null

    private val statusListeners = arrayListOf<FoldAodAnimationStatus>()
    private val foldToAodLatencyTracker = FoldToAodLatencyTracker()

    private val startAnimationRunnable = Runnable {
        shadeFoldAnimator.startFoldToAodAnimation(
            /* startAction= */ { foldToAodLatencyTracker.onAnimationStarted() },
            /* endAction= */ { setAnimationState(playing = false) },
            /* cancelAction= */ { setAnimationState(playing = false) },
        )
    }

    override fun initialize(
        centralSurfaces: CentralSurfaces,
        shadeViewController: ShadeViewController,
        lightRevealScrim: LightRevealScrim,
    ) {
        this.shadeViewController = shadeViewController
        foldTransitionInteractor.get().initialize(shadeViewController.shadeFoldAnimator)

        deviceStateManager.registerCallback(mainExecutor, FoldListener())
        wakefulnessLifecycle.addObserver(this)
    }

    /** Returns true if we should run fold to AOD animation */
    override fun shouldPlayAnimation(): Boolean = shouldPlayAnimation

    private fun shouldStartAnimation(): Boolean =
        alwaysOnEnabled &&
            wakefulnessLifecycle.lastSleepReason == PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD &&
            globalSettings.getString(Settings.Global.ANIMATOR_DURATION_SCALE) != "0"

    override fun startAnimation(): Boolean =
        if (shouldStartAnimation()) {
            setAnimationState(playing = true)
            shadeFoldAnimator.prepareFoldToAodAnimation()
            true
        } else {
            setAnimationState(playing = false)
            false
        }

    override fun onStartedWakingUp() {
        if (isAnimationPlaying) {
            foldToAodLatencyTracker.cancel()
            cancelAnimation?.run()
            shadeFoldAnimator.cancelFoldToAodAnimation()
        }

        setAnimationState(playing = false)
    }

    private val shadeFoldAnimator: ShadeFoldAnimator
        get() {
            return if (MigrateClocksToBlueprint.isEnabled) {
                foldTransitionInteractor.get().foldAnimator
            } else {
                shadeViewController.shadeFoldAnimator
            }
        }

    private fun setAnimationState(playing: Boolean) {
        shouldPlayAnimation = playing
        isAnimationPlaying = playing
        statusListeners.forEach(FoldAodAnimationStatus::onFoldToAodAnimationChanged)
    }

    /**
     * Called when screen starts turning on, the contents of the screen might not be visible yet.
     * This method reports back that the animation is ready in [onReady] callback.
     *
     * @param onReady callback when the animation is ready
     * @see [com.android.systemui.keyguard.KeyguardViewMediator]
     */
    @BinderThread
    fun onScreenTurningOn(onReady: Runnable) =
        mainExecutor.execute {
            if (shouldPlayAnimation) {
                // The device was not dozing and going to sleep after folding, play the animation
                if (isScrimOpaque) {
                    onReady.run()
                } else {
                    pendingScrimReadyCallback = onReady
                }
            } else if (
                isFolded &&
                    !isFoldHandled &&
                    alwaysOnEnabled &&
                    keyguardInteractor.get().isDozing.value
            ) {
                setAnimationState(playing = true)
                shadeFoldAnimator.prepareFoldToAodAnimation()

                // We don't need to wait for the scrim as it is already displayed
                // but we should wait for the initial animation preparations to be drawn
                // (setting initial alpha/translation)
                // TODO(b/254878364): remove this call to NPVC.getView()
                if (!MigrateClocksToBlueprint.isEnabled) {
                    shadeFoldAnimator.view?.let { OneShotPreDrawListener.add(it, onReady) }
                } else {
                    onReady.run()
                }
            } else {
                // No animation, call ready callback immediately
                onReady.run()
            }

            if (isFolded) {
                // Any time the screen turns on, this state needs to be reset if the device has been
                // folded. Reaching this line implies AOD has been shown in one way or another,
                // if enabled
                isFoldHandled = true
            }
        }

    /** Called when keyguard scrim opaque changed */
    override fun onScrimOpaqueChanged(isOpaque: Boolean) {
        isScrimOpaque = isOpaque

        if (isOpaque) {
            pendingScrimReadyCallback?.run()
            pendingScrimReadyCallback = null
        }
    }

    @BinderThread
    fun onScreenTurnedOn() =
        mainExecutor.execute {
            if (shouldPlayAnimation) {
                cancelAnimation?.run()

                // Post starting the animation to the next frame to avoid junk due to inset changes
                cancelAnimation =
                    mainExecutor.executeDelayed(startAnimationRunnable, /* delayMillis= */ 0)
                shouldPlayAnimation = false
            }
        }

    override fun isAnimationPlaying(): Boolean = isAnimationPlaying

    override fun isKeyguardHideDelayed(): Boolean = isAnimationPlaying()

    override fun shouldShowAodIconsWhenShade(): Boolean = shouldPlayAnimation()

    override fun shouldAnimateAodIcons(): Boolean = !shouldPlayAnimation()

    override fun shouldAnimateDozingChange(): Boolean = !shouldPlayAnimation()

    override fun shouldAnimateClockChange(): Boolean = !isAnimationPlaying()

    override fun shouldDelayDisplayDozeTransition(): Boolean = shouldPlayAnimation()

    /** Called when AOD status is changed */
    override fun onAlwaysOnChanged(alwaysOn: Boolean) {
        alwaysOnEnabled = alwaysOn
    }

    override fun addCallback(listener: FoldAodAnimationStatus) {
        statusListeners += listener
    }

    override fun removeCallback(listener: FoldAodAnimationStatus) {
        statusListeners.remove(listener)
    }

    interface FoldAodAnimationStatus {
        fun onFoldToAodAnimationChanged()
    }

    private inner class FoldListener :
        DeviceStateManager.FoldStateListener(
            context,
            Consumer { isFolded ->
                if (!isFolded) {
                    // We are unfolded now, reset the fold handle status
                    isFoldHandled = false
                }
                this.isFolded = isFolded
                if (isFolded) {
                    foldToAodLatencyTracker.onFolded()
                }
            }
        )

    /**
     * Tracks the latency of fold to AOD using [LatencyTracker].
     *
     * Events that trigger start and end are:
     * - Start: Once [DeviceStateManager] sends the folded signal [FoldToAodLatencyTracker.onFolded]
     *   is called and latency tracking starts.
     * - End: Once the fold -> AOD animation starts, [FoldToAodLatencyTracker.onAnimationStarted] is
     *   called, and latency tracking stops.
     */
    private inner class FoldToAodLatencyTracker {

        /** Triggers the latency logging, if needed. */
        fun onFolded() {
            if (shouldStartAnimation()) {
                latencyTracker.onActionStart(LatencyTracker.ACTION_FOLD_TO_AOD)
            }
        }
        /**
         * Called once the Fold -> AOD animation is started.
         *
         * For latency tracking, this determines the end of the fold to aod action.
         */
        fun onAnimationStarted() {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_FOLD_TO_AOD)
        }

        fun cancel() {
            latencyTracker.onActionCancel(LatencyTracker.ACTION_FOLD_TO_AOD)
        }
    }
}
