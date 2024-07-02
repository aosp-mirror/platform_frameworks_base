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

package com.android.systemui.navigationbar

import com.android.internal.statusbar.RegisterStatusBarResult
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.statusbar.phone.BarTransitions
import javax.inject.Inject

/** A no-op version of [NavigationBarController] for variants like Arc and TV. */
@SysUISingleton
class NavigationBarControllerEmptyImpl @Inject constructor() : NavigationBarController {
    override fun createNavigationBars(
        includeDefaultDisplay: Boolean,
        result: RegisterStatusBarResult?,
    ) {}
    override fun removeNavigationBar(displayId: Int) {}
    override fun checkNavBarModes(displayId: Int) {}
    override fun finishBarAnimations(displayId: Int) {}
    override fun touchAutoDim(displayId: Int) {}
    override fun transitionTo(
        displayId: Int,
        @BarTransitions.TransitionMode barMode: Int,
        animate: Boolean,
    ) {}
    override fun disableAnimationsDuringHide(displayId: Int, delay: Long) {}
    override fun getDefaultNavigationBarView(): NavigationBarView? = null
    override fun getNavigationBarView(displayId: Int): NavigationBarView? = null
    override fun isOverviewEnabled(displayId: Int) = false
    override fun getDefaultNavigationBar(): NavigationBar? = null
}
