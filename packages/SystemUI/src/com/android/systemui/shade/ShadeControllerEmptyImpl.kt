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

package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Empty implementation of ShadeController for variants of Android without shades. */
@SysUISingleton
open class ShadeControllerEmptyImpl @Inject constructor() : ShadeController {
    override fun isShadeEnabled() = false
    override fun start() {}
    override fun instantExpandShade() {}
    override fun instantCollapseShade() {}
    override fun animateCollapseShade(
        flags: Int,
        force: Boolean,
        delayed: Boolean,
        speedUpFactor: Float
    ) {}
    override fun collapseWithDuration(animationDuration: Int) {}
    override fun animateExpandShade() {}
    override fun animateExpandQs() {}
    override fun postAnimateCollapseShade() {}
    override fun postAnimateForceCollapseShade() {}
    override fun postAnimateExpandQs() {}
    override fun cancelExpansionAndCollapseShade() {}
    override fun closeShadeIfOpen(): Boolean {
        return false
    }
    override fun isShadeFullyOpen(): Boolean {
        return false
    }
    override fun isExpandingOrCollapsing(): Boolean {
        return false
    }
    override fun postOnShadeExpanded(action: Runnable?) {}
    override fun addPostCollapseAction(action: Runnable?) {}
    override fun collapseShade() {}
    override fun collapseShade(animate: Boolean) {}
    override fun collapseOnMainThread() {}
    override fun collapseShadeForActivityStart() {}
    override fun makeExpandedInvisible() {}
    override fun makeExpandedVisible(force: Boolean) {}
    override fun isExpandedVisible(): Boolean {
        return false
    }
    override fun onStatusBarTouch(event: MotionEvent?) {}
    override fun onLaunchAnimationCancelled(isLaunchForActivity: Boolean) {}
    override fun onLaunchAnimationEnd(launchIsFullScreen: Boolean) {}
}
