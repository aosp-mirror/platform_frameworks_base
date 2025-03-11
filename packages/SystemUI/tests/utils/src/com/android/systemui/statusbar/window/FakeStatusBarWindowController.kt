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

class FakeStatusBarWindowController : StatusBarWindowController {

    var isAttached = false
        private set

    var isStopped = false
        private set

    override val statusBarHeight: Int = 0

    override fun refreshStatusBarHeight() {}

    override fun attach() {
        isAttached = true
    }

    override fun stop() {
        isStopped = true
    }

    override fun addViewToWindow(view: View, layoutParams: ViewGroup.LayoutParams) {}

    override val backgroundView: View
        get() = throw NotImplementedError()

    override val fragmentHostManager: FragmentHostManager
        get() = throw NotImplementedError()

    override fun wrapAnimationControllerIfInStatusBar(
        rootView: View,
        animationController: ActivityTransitionAnimator.Controller,
    ): Optional<ActivityTransitionAnimator.Controller> = Optional.empty()

    override fun setForceStatusBarVisible(forceStatusBarVisible: Boolean) {}

    override fun setOngoingProcessRequiresStatusBarVisible(visible: Boolean) {}
}
