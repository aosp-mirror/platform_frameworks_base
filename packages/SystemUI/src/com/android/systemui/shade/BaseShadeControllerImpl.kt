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

import com.android.systemui.assist.AssistManager
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** A base class for non-empty implementations of ShadeController. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseShadeControllerImpl(
    protected val commandQueue: CommandQueue,
    protected val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    protected val notificationShadeWindowController: NotificationShadeWindowController,
    protected val assistManagerLazy: Lazy<AssistManager>
) : ShadeController {
    protected lateinit var notifPresenter: NotificationPresenter
    /** Runnables to run after completing a collapse of the shade. */
    private val postCollapseActions = ArrayList<Runnable>()

    override fun start() {
        // Do nothing by default
    }

    final override fun animateExpandShade() {
        if (isShadeEnabled) {
            expandToNotifications()
        }
    }

    /** Expand the shade with notifications visible. */
    protected abstract fun expandToNotifications()

    final override fun animateExpandQs() {
        if (isShadeEnabled) {
            expandToQs()
        }
    }

    /** Expand the shade showing only quick settings. */
    protected abstract fun expandToQs()

    final override fun addPostCollapseAction(action: Runnable) {
        postCollapseActions.add(action)
    }

    protected fun runPostCollapseActions() {
        val clonedList: ArrayList<Runnable> = ArrayList(postCollapseActions)
        postCollapseActions.clear()
        for (r in clonedList) {
            r.run()
        }
        statusBarKeyguardViewManager.readyForKeyguardDone()
    }

    final override fun onLaunchAnimationEnd(launchIsFullScreen: Boolean) {
        if (!this.notifPresenter.isCollapsing()) {
            onClosingFinished()
        }
        if (launchIsFullScreen) {
            instantCollapseShade()
        }
    }
    final override fun onLaunchAnimationCancelled(isLaunchForActivity: Boolean) {
        if (
            notifPresenter.isPresenterFullyCollapsed() &&
                !notifPresenter.isCollapsing() &&
                isLaunchForActivity
        ) {
            onClosingFinished()
        } else {
            collapseShade(true /* animate */)
        }
    }

    protected fun onClosingFinished() {
        runPostCollapseActions()
        if (!this.notifPresenter.isPresenterFullyCollapsed()) {
            // if we set it not to be focusable when collapsing, we have to undo it when we aborted
            // the closing
            notificationShadeWindowController.setNotificationShadeFocusable(true)
        }
    }

    override fun setNotificationPresenter(presenter: NotificationPresenter) {
        notifPresenter = presenter
    }
}
