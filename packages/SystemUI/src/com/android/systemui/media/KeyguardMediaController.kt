/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.view.View
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaHeaderView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class that controls the media notifications on the lock screen, handles its visibility and
 * is responsible for the embedding of he media experience.
 */
@Singleton
class KeyguardMediaController @Inject constructor(
    private val mediaHost: MediaHost,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val notifLockscreenUserManager: NotificationLockscreenUserManager
) {

    init {
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                updateVisibility()
            }
        })
    }
    private var view: MediaHeaderView? = null

    /**
     * Attach this controller to a media view, initializing its state
     */
    fun attach(mediaView: MediaHeaderView) {
        view = mediaView
        // First let's set the desired state that we want for this host
        mediaHost.visibleChangedListener = { updateVisibility() }
        mediaHost.expansion = 0.0f
        mediaHost.showsOnlyActiveMedia = true

        // Let's now initialize this view, which also creates the host view for us.
        mediaHost.init(MediaHierarchyManager.LOCATION_LOCKSCREEN)
        mediaView.setContentView(mediaHost.hostView)
    }

    private fun updateVisibility() {
        val keyguardOrUserSwitcher = (statusBarStateController.state == StatusBarState.KEYGUARD ||
                statusBarStateController.state == StatusBarState.FULLSCREEN_USER_SWITCHER)
        val shouldBeVisible = mediaHost.visible &&
                !bypassController.bypassEnabled &&
                keyguardOrUserSwitcher &&
                notifLockscreenUserManager.shouldShowLockscreenNotifications()
        view?.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
    }
}