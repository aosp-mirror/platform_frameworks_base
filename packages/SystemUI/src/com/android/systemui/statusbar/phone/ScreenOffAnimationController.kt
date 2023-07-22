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
package com.android.systemui.statusbar.phone

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.unfold.FoldAodAnimationController
import com.android.systemui.unfold.SysUIUnfoldComponent
import java.util.Optional
import javax.inject.Inject

@SysUISingleton
class ScreenOffAnimationController @Inject constructor(
    sysUiUnfoldComponent: Optional<SysUIUnfoldComponent>,
    unlockedScreenOffAnimation: UnlockedScreenOffAnimationController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
) : WakefulnessLifecycle.Observer {

    private val foldToAodAnimation: FoldAodAnimationController? = sysUiUnfoldComponent
        .orElse(null)?.getFoldAodAnimationController()

    private val animations: List<ScreenOffAnimation> =
        listOfNotNull(foldToAodAnimation, unlockedScreenOffAnimation)

    fun initialize(
            centralSurfaces: CentralSurfaces,
            shadeViewController: ShadeViewController,
            lightRevealScrim: LightRevealScrim,
    ) {
        animations.forEach { it.initialize(centralSurfaces, shadeViewController, lightRevealScrim) }
        wakefulnessLifecycle.addObserver(this)
    }

    /**
     * Called when system reports that we are going to sleep
     */
    override fun onStartedGoingToSleep() {
        animations.firstOrNull { it.startAnimation() }
    }

    /**
     * Called when opaqueness of the light reveal scrim has change
     * When [isOpaque] is true then scrim is visible and covers the screen
     */
    fun onScrimOpaqueChanged(isOpaque: Boolean) =
        animations.forEach { it.onScrimOpaqueChanged(isOpaque) }

    /**
     * Called when always on display setting changed
     */
    fun onAlwaysOnChanged(alwaysOn: Boolean) =
        animations.forEach { it.onAlwaysOnChanged(alwaysOn) }

    /**
     * If returns true we are taking over the screen off animation from display manager to SysUI.
     * We can play our custom animation instead of default fade out animation.
     */
    fun shouldControlUnlockedScreenOff(): Boolean =
        animations.any { it.shouldPlayAnimation() }

    /**
     * If returns true it fully expands notification shade, it could be used to make
     * the scrims visible
     */
    fun shouldExpandNotifications(): Boolean =
        animations.any { it.isAnimationPlaying() }

    /**
     * If returns true it allows to perform custom animation for showing
     * keyguard in [animateInKeyguard]
     */
    fun shouldAnimateInKeyguard(): Boolean =
        animations.any { it.shouldAnimateInKeyguard() }

    /**
     * Called when keyguard is about to be displayed and allows to perform custom animation
     */
    fun animateInKeyguard(keyguardView: View, after: Runnable) =
        animations.firstOrNull {
            if (it.shouldAnimateInKeyguard()) {
                it.animateInKeyguard(keyguardView, after)
                true
            } else {
                false
            }
        }

    /**
     * If returns true it will disable propagating touches to apps and keyguard
     */
    fun shouldIgnoreKeyguardTouches(): Boolean =
        animations.any { it.isAnimationPlaying() }

    /**
     * If returns true wake up won't be blocked when dozing
     */
    fun allowWakeUpIfDozing(): Boolean =
        animations.all { !it.isAnimationPlaying() }

    /**
     * Do not allow showing keyguard immediately so it could be postponed e.g. to the point when
     * the animation ends
     */
    fun shouldDelayKeyguardShow(): Boolean =
        animations.any { it.shouldDelayKeyguardShow() }

    /**
     * Return true while we want to ignore requests to show keyguard, we need to handle pending
     * keyguard lock requests manually
     *
     * @see [com.android.systemui.keyguard.KeyguardViewMediator.maybeHandlePendingLock]
     */
    fun isKeyguardShowDelayed(): Boolean =
        animations.any { it.isKeyguardShowDelayed() }

    /**
     * Return true to ignore requests to hide keyguard
     */
    fun isKeyguardHideDelayed(): Boolean =
        animations.any { it.isKeyguardHideDelayed() }

    /**
     * Return true to make the status bar expanded so we can animate [LightRevealScrim]
     */
    fun shouldShowLightRevealScrim(): Boolean =
        animations.any { it.shouldPlayAnimation() }

    /**
     * Return true to indicate that we should hide [LightRevealScrim] when waking up
     */
    fun shouldHideLightRevealScrimOnWakeUp(): Boolean =
        animations.any { it.shouldHideScrimOnWakeUp() }

    /**
     * Return true to override the dozing state of the notifications to fully dozing,
     * so the notifications are not visible when playing the animation
     */
    fun overrideNotificationsFullyDozingOnKeyguard(): Boolean =
        animations.any { it.overrideNotificationsDozeAmount() }

    /**
     * Return true to hide the notifications footer ('manage'/'clear all' buttons)
     */
    fun shouldHideNotificationsFooter(): Boolean =
        animations.any { it.isAnimationPlaying() }

    /**
     * Return true to clamp screen brightness to 'dimmed' value when devices times out
     * and goes to sleep
     */
    fun shouldClampDozeScreenBrightness(): Boolean =
        animations.any { it.shouldPlayAnimation() }

    /**
     * Return true to show AOD icons even when status bar is in 'shade' state (unlocked)
     */
    fun shouldShowAodIconsWhenShade(): Boolean =
        animations.any { it.shouldShowAodIconsWhenShade() }

    /**
     * Return true to allow animation of appearing AOD icons
     */
    fun shouldAnimateAodIcons(): Boolean =
        animations.all { it.shouldAnimateAodIcons() }

    /**
     * Return true to animate doze state change, if returns false dozing will be applied without
     * animation (sends only 0.0f or 1.0f dozing progress)
     */
    fun shouldAnimateDozingChange(): Boolean =
        animations.all { it.shouldAnimateDozingChange() }

    /**
     * Returns true when moving display state to power save mode should be
     * delayed for a few seconds. This might be useful to play animations in full quality,
     * without reducing FPS.
     */
    fun shouldDelayDisplayDozeTransition(): Boolean =
        animations.any { it.shouldDelayDisplayDozeTransition() }

    /**
     * Return true to animate large <-> small clock transition
     */
    fun shouldAnimateClockChange(): Boolean =
        animations.all { it.shouldAnimateClockChange() }
}

interface ScreenOffAnimation {
    fun initialize(
            centralSurfaces: CentralSurfaces,
            shadeViewController: ShadeViewController,
            lightRevealScrim: LightRevealScrim,
    ) {}

    /**
     * Called when started going to sleep, should return true if the animation will be played
     */
    fun startAnimation(): Boolean = false

    fun shouldPlayAnimation(): Boolean = false
    fun isAnimationPlaying(): Boolean = false

    fun onScrimOpaqueChanged(isOpaque: Boolean) {}
    fun onAlwaysOnChanged(alwaysOn: Boolean) {}

    fun shouldAnimateInKeyguard(): Boolean = false
    fun animateInKeyguard(keyguardView: View, after: Runnable) = after.run()

    fun shouldDelayKeyguardShow(): Boolean = false
    fun isKeyguardShowDelayed(): Boolean = false
    fun isKeyguardHideDelayed(): Boolean = false
    fun shouldHideScrimOnWakeUp(): Boolean = false
    fun overrideNotificationsDozeAmount(): Boolean = false
    fun shouldShowAodIconsWhenShade(): Boolean = false
    fun shouldDelayDisplayDozeTransition(): Boolean = false
    fun shouldAnimateAodIcons(): Boolean = true
    fun shouldAnimateDozingChange(): Boolean = true
    fun shouldAnimateClockChange(): Boolean = true
}
