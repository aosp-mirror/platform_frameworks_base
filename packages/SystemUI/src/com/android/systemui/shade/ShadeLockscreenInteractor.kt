/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.shade

/** Allows the lockscreen to control the shade. */
interface ShadeLockscreenInteractor {

    /**
     * Expand shade so that notifications are visible. Non-split shade: just expanding shade or
     * collapsing QS when they're expanded. Split shade: only expanding shade, notifications are
     * always visible
     *
     * Called when `adb shell cmd statusbar expand-notifications` is executed.
     */
    @Deprecated("Use ShadeInteractor instead") fun expandToNotifications()

    /**
     * Returns whether the shade height is greater than zero (i.e. partially or fully expanded),
     * there is a HUN, the shade is animating, or the shade is instantly expanding.
     */
    @Deprecated("Use ShadeInteractor instead") val isExpanded: Boolean

    /** Called before animating Keyguard dismissal, i.e. the animation dismissing the bouncer. */
    fun startBouncerPreHideAnimation()

    /** Called once every minute while dozing. */
    fun dozeTimeTick()

    /**
     * Do not let the user drag the shade up and down for the current touch session. This is
     * necessary to avoid shade expansion while/after the bouncer is dismissed.
     */
    @Deprecated("Not supported by scenes") fun blockExpansionForCurrentTouch()

    /** Close guts, notification menus, and QS. Set scroll and overscroll to 0. */
    fun resetViews(animate: Boolean)

    /** Sets whether the screen has temporarily woken up to display notifications. */
    @Deprecated("Not supported by scenes") fun setPulsing(pulsing: Boolean)

    /** Animate to expanded shade after a delay in ms. Used for lockscreen to shade transition. */
    fun transitionToExpandedShade(delay: Long)

    /** @see ViewGroupFadeHelper.reset */
    @Deprecated("Not supported by scenes") fun resetViewGroupFade()

    /**
     * Set the alpha and translationY of the keyguard elements which only show on the lockscreen,
     * but not in shade locked / shade. This is used when dragging down to the full shade.
     */
    @Deprecated("Not supported by scenes")
    fun setKeyguardTransitionProgress(keyguardAlpha: Float, keyguardTranslationY: Int)

    /** Sets the overstretch amount in raw pixels when dragging down. */
    @Deprecated("Not supported by scenes") fun setOverStretchAmount(amount: Float)

    /**
     * Sets the alpha value to be set on the keyguard status bar.
     *
     * @param alpha value between 0 and 1. -1 if the value is to be reset.
     */
    @Deprecated("TODO(b/325072511) delete this") fun setKeyguardStatusBarAlpha(alpha: Float)
}
