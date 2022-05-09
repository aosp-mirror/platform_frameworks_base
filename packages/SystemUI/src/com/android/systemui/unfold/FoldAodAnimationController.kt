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

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import androidx.core.view.OneShotPreDrawListener
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.ScreenOffAnimation
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.unfold.FoldAodAnimationController.FoldAodAnimationStatus
import com.android.systemui.util.settings.GlobalSettings
import java.util.concurrent.Executor
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
    @Main private val handler: Handler,
    @Main private val executor: Executor,
    private val context: Context,
    private val deviceStateManager: DeviceStateManager,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val globalSettings: GlobalSettings
) : CallbackController<FoldAodAnimationStatus>, ScreenOffAnimation, WakefulnessLifecycle.Observer {

    private lateinit var mCentralSurfaces: CentralSurfaces

    private var isFolded = false
    private var isFoldHandled = true

    private var alwaysOnEnabled: Boolean = false
    private var isDozing: Boolean = false
    private var isScrimOpaque: Boolean = false
    private var pendingScrimReadyCallback: Runnable? = null

    private var shouldPlayAnimation = false
    private var isAnimationPlaying = false

    private val statusListeners = arrayListOf<FoldAodAnimationStatus>()

    private val startAnimationRunnable = Runnable {
        mCentralSurfaces.notificationPanelViewController.startFoldToAodAnimation {
            // End action
            setAnimationState(playing = false)
        }
    }

    override fun initialize(centralSurfaces: CentralSurfaces, lightRevealScrim: LightRevealScrim) {
        this.mCentralSurfaces = centralSurfaces

        deviceStateManager.registerCallback(executor, FoldListener())
        wakefulnessLifecycle.addObserver(this)
    }

    /** Returns true if we should run fold to AOD animation */
    override fun shouldPlayAnimation(): Boolean = shouldPlayAnimation

    override fun startAnimation(): Boolean =
        if (alwaysOnEnabled &&
            wakefulnessLifecycle.lastSleepReason == PowerManager.GO_TO_SLEEP_REASON_DEVICE_FOLD &&
            globalSettings.getString(Settings.Global.ANIMATOR_DURATION_SCALE) != "0"
        ) {
            setAnimationState(playing = true)
            mCentralSurfaces.notificationPanelViewController.prepareFoldToAodAnimation()
            true
        } else {
            setAnimationState(playing = false)
            false
        }

    override fun onStartedWakingUp() {
        if (isAnimationPlaying) {
            handler.removeCallbacks(startAnimationRunnable)
            mCentralSurfaces.notificationPanelViewController.cancelFoldToAodAnimation()
        }

        setAnimationState(playing = false)
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
    fun onScreenTurningOn(onReady: Runnable) {
        if (shouldPlayAnimation) {
            // The device was not dozing and going to sleep after folding, play the animation

            if (isScrimOpaque) {
                onReady.run()
            } else {
                pendingScrimReadyCallback = onReady
            }
        } else if (isFolded && !isFoldHandled && alwaysOnEnabled && isDozing) {
            // Screen turning on for the first time after folding and we are already dozing
            // We should play the folding to AOD animation

            setAnimationState(playing = true)
            mCentralSurfaces.notificationPanelViewController.prepareFoldToAodAnimation()

            // We don't need to wait for the scrim as it is already displayed
            // but we should wait for the initial animation preparations to be drawn
            // (setting initial alpha/translation)
            OneShotPreDrawListener.add(
                mCentralSurfaces.notificationPanelViewController.view, onReady
            )
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

    fun setIsDozing(dozing: Boolean) {
        isDozing = dozing
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
            })
}
