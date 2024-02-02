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

package com.android.systemui.back.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.QuickSettingsController
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import javax.inject.Inject

/** Handles requests to go back either from a button or gesture. */
@SysUISingleton
class BackActionInteractor
@Inject
constructor(
    private val statusBarStateController: StatusBarStateController,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val shadeController: ShadeController
) {
    private lateinit var shadeViewController: ShadeViewController
    private lateinit var qsController: QuickSettingsController

    fun setup(qsController: QuickSettingsController, svController: ShadeViewController) {
        this.qsController = qsController
        this.shadeViewController = svController
    }

    fun shouldBackBeHandled(): Boolean {
        return statusBarStateController.state != StatusBarState.KEYGUARD &&
            statusBarStateController.state != StatusBarState.SHADE_LOCKED &&
            !statusBarKeyguardViewManager.isBouncerShowingOverDream
    }

    fun onBackRequested(): Boolean {
        if (statusBarKeyguardViewManager.canHandleBackPressed()) {
            statusBarKeyguardViewManager.onBackPressed()
            return true
        }
        if (qsController.isCustomizing) {
            qsController.closeQsCustomizer()
            return true
        }
        if (qsController.expanded) {
            shadeViewController.animateCollapseQs(false)
            return true
        }
        if (shadeViewController.closeUserSwitcherIfOpen()) {
            return true
        }
        if (shouldBackBeHandled()) {
            if (shadeViewController.canBeCollapsed()) {
                // this is the Shade dismiss animation, so make sure QQS closes when it ends.
                shadeViewController.onBackPressed()
                shadeController.animateCollapseShade()
            }
            return true
        }
        return false
    }
}
