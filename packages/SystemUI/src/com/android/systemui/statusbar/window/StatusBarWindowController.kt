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

package com.android.systemui.statusbar.window

import android.view.View
import android.view.ViewGroup
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.fragments.FragmentHostManager
import java.util.Optional

/** Encapsulates all logic for the status bar window state management. */
interface StatusBarWindowController {
    val statusBarHeight: Int

    /** Rereads the status bar height and reapplies the current state if the height is different. */
    fun refreshStatusBarHeight()

    /** Adds the status bar view to the window manager. */
    fun attach()

    /** Adds the given view to the status bar window view. */
    fun addViewToWindow(view: View, layoutParams: ViewGroup.LayoutParams)

    /** Returns the status bar window's background view. */
    val backgroundView: View

    /** Returns a fragment host manager for the status bar window view. */
    val fragmentHostManager: FragmentHostManager

    /**
     * Provides an updated animation controller if we're animating a view in the status bar.
     *
     * This is needed because we have to make sure that the status bar window matches the full
     * screen during the animation and that we are expanding the view below the other status bar
     * text.
     *
     * @param rootView the root view of the animation
     * @param animationController the default animation controller to use
     * @return If the animation is on a view in the status bar, returns an Optional containing an
     *   updated animation controller that handles status-bar-related animation details. Returns an
     *   empty optional if the animation is *not* on a view in the status bar.
     */
    fun wrapAnimationControllerIfInStatusBar(
        rootView: View,
        animationController: ActivityTransitionAnimator.Controller,
    ): Optional<ActivityTransitionAnimator.Controller>

    /** Set force status bar visible. */
    fun setForceStatusBarVisible(forceStatusBarVisible: Boolean)

    /**
     * Sets whether an ongoing process requires the status bar to be forced visible.
     *
     * This method is separate from {@link this#setForceStatusBarVisible} because the ongoing
     * process **takes priority**. For example, if {@link this#setForceStatusBarVisible} is set to
     * false but this method is set to true, then the status bar **will** be visible.
     *
     * TODO(b/195839150): We should likely merge this method and {@link
     *   this#setForceStatusBarVisible} together and use some sort of ranking system instead.
     */
    fun setOngoingProcessRequiresStatusBarVisible(visible: Boolean)
}
