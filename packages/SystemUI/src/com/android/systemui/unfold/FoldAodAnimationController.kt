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

import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.ScreenOffAnimation
import com.android.systemui.statusbar.phone.StatusBar
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.unfold.FoldAodAnimationController.FoldAodAnimationStatus
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject

/**
 * Controls folding to AOD animation: when AOD is enabled and foldable device is folded we play a
 * special AOD animation on the outer screen
 */
@SysUIUnfoldScope
class FoldAodAnimationController
@Inject
constructor(
    @Main private val handler: Handler,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val globalSettings: GlobalSettings
) : CallbackController<FoldAodAnimationStatus>, ScreenOffAnimation, WakefulnessLifecycle.Observer {

    private var alwaysOnEnabled: Boolean = false
    private var isScrimOpaque: Boolean = false
    private lateinit var statusBar: StatusBar
    private var pendingScrimReadyCallback: Runnable? = null

    private var shouldPlayAnimation = false
    private val statusListeners = arrayListOf<FoldAodAnimationStatus>()

    private val startAnimationRunnable = Runnable {
        statusBar.notificationPanelViewController.startFoldToAodAnimation {
            // End action
            isAnimationPlaying = false
        }
    }

    private var isAnimationPlaying = false

    override fun initialize(statusBar: StatusBar, lightRevealScrim: LightRevealScrim) {
        this.statusBar = statusBar

        wakefulnessLifecycle.addObserver(this)
    }

    /** Returns true if we should run fold to AOD animation */
    override fun shouldPlayAnimation(): Boolean = shouldPlayAnimation

    override fun startAnimation(): Boolean =
        if (alwaysOnEnabled &&
            wakefulnessLifecycle.lastSleepReason == PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD &&
            globalSettings.getString(Settings.Global.ANIMATOR_DURATION_SCALE) != "0") {
            shouldPlayAnimation = true

            isAnimationPlaying = true
            statusBar.notificationPanelViewController.prepareFoldToAodAnimation()

            statusListeners.forEach(FoldAodAnimationStatus::onFoldToAodAnimationChanged)

            true
        } else {
            shouldPlayAnimation = false
            false
        }

    override fun onStartedWakingUp() {
        if (isAnimationPlaying) {
            handler.removeCallbacks(startAnimationRunnable)
            statusBar.notificationPanelViewController.cancelFoldToAodAnimation();
        }

        shouldPlayAnimation = false
        isAnimationPlaying = false
    }

    /**
     * Called when screen starts turning on, the contents of the screen might not be visible yet.
     * This method reports back that the animation is ready in [onReady] callback.
     *
     * @param onReady callback when the animation is ready
     * @see [com.android.systemui.keyguard.KeyguardViewMediator]
     */
    fun onScreenTurningOn(onReady: Runnable) {
        if (shouldPlayAnimation) {
            if (isScrimOpaque) {
                onReady.run()
            } else {
                pendingScrimReadyCallback = onReady
            }
        } else {
            // No animation, call ready callback immediately
            onReady.run()
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

    fun onScreenTurnedOn() {
        if (shouldPlayAnimation) {
            handler.removeCallbacks(startAnimationRunnable)

            // Post starting the animation to the next frame to avoid junk due to inset changes
            handler.post(startAnimationRunnable)
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
}
