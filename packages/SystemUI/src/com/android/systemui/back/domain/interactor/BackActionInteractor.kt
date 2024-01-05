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

import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.window.WindowOnBackInvokedDispatcher
import com.android.systemui.CoreStartable
import com.android.systemui.Flags.predictiveBackAnimateShade
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.shade.QuickSettingsController
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Handles requests to go back either from a button or gesture. */
@SysUISingleton
class BackActionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val statusBarStateController: StatusBarStateController,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val shadeController: ShadeController,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor
) : CoreStartable {

    private var isCallbackRegistered = false

    private val callback =
        if (predictiveBackAnimateShade()) {
            /**
             * New callback that handles back gesture invoked, cancel, progress and provides
             * feedback via Shade animation.
             */
            object : OnBackAnimationCallback {
                override fun onBackInvoked() {
                    onBackRequested()
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    if (shouldBackBeHandled() && shadeViewController.canBeCollapsed()) {
                        shadeViewController.onBackProgressed(backEvent.progress)
                    }
                }
            }
        } else {
            OnBackInvokedCallback { onBackRequested() }
        }

    private val onBackInvokedDispatcher: WindowOnBackInvokedDispatcher?
        get() =
            notificationShadeWindowController.windowRootView?.viewRootImpl?.onBackInvokedDispatcher

    private lateinit var shadeViewController: ShadeViewController
    private lateinit var qsController: QuickSettingsController

    fun setup(qsController: QuickSettingsController, svController: ShadeViewController) {
        this.qsController = qsController
        this.shadeViewController = svController
    }

    override fun start() {
        scope.launch {
            windowRootViewVisibilityInteractor.isLockscreenOrShadeVisibleAndInteractive.collect {
                visible ->
                if (visible) {
                    registerBackCallback()
                } else {
                    unregisterBackCallback()
                }
            }
        }
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

    private fun registerBackCallback() {
        if (isCallbackRegistered) {
            return
        }
        onBackInvokedDispatcher?.let {
            it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            isCallbackRegistered = true
        }
    }

    private fun unregisterBackCallback() {
        if (!isCallbackRegistered) {
            return
        }
        onBackInvokedDispatcher?.let {
            it.unregisterOnBackInvokedCallback(callback)
            isCallbackRegistered = false
        }
    }
}
