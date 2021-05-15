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
import android.view.ViewGroup
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.dagger.MediaModule.KEYGUARD
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaHeaderView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import javax.inject.Inject
import javax.inject.Named

/**
 * Controls the media notifications on the lock screen, handles its visibility and placement -
 * switches media player positioning between split pane container vs single pane container
 */
@SysUISingleton
class KeyguardMediaController @Inject constructor(
    @param:Named(KEYGUARD) private val mediaHost: MediaHost,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val notifLockscreenUserManager: NotificationLockscreenUserManager
) {

    init {
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                refreshMediaPosition()
            }

            override fun onDozingChanged(isDozing: Boolean) {
                if (!isDozing) {
                    mediaHost.visible = true
                    refreshMediaPosition()
                }
            }
        })

        // First let's set the desired state that we want for this host
        mediaHost.expansion = MediaHostState.COLLAPSED
        mediaHost.showsOnlyActiveMedia = true
        mediaHost.falsingProtectionNeeded = true

        // Let's now initialize this view, which also creates the host view for us.
        mediaHost.init(MediaHierarchyManager.LOCATION_LOCKSCREEN)
    }

    /**
     * Is the media player visible?
     */
    var visible = false
        private set

    var visibilityChangedListener: ((Boolean) -> Unit)? = null

    /**
     * single pane media container placed at the top of the notifications list
     */
    var singlePaneContainer: MediaHeaderView? = null
        private set
    private var splitShadeContainer: ViewGroup? = null
    private var useSplitShadeContainer: () -> Boolean = { false }

    /**
     * Attaches media container in single pane mode, situated at the top of the notifications list
     */
    fun attachSinglePaneContainer(mediaView: MediaHeaderView?) {
        singlePaneContainer = mediaView

        // Required to show it for the first time, afterwards visibility is managed automatically
        mediaHost.visible = true
        mediaHost.addVisibilityChangeListener { visible ->
            refreshMediaPosition()
            if (visible) {
                mediaHost.hostView.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }
        refreshMediaPosition()
    }

    /**
     * Attaches media container in split shade mode, situated to the left of notifications
     */
    fun attachSplitShadeContainer(container: ViewGroup, useContainer: () -> Boolean) {
        splitShadeContainer = container
        useSplitShadeContainer = useContainer
    }

    fun refreshMediaPosition() {
        val keyguardOrUserSwitcher = (statusBarStateController.state == StatusBarState.KEYGUARD ||
                statusBarStateController.state == StatusBarState.FULLSCREEN_USER_SWITCHER)
        // mediaHost.visible required for proper animations handling
        visible = mediaHost.visible &&
                !bypassController.bypassEnabled &&
                keyguardOrUserSwitcher &&
                notifLockscreenUserManager.shouldShowLockscreenNotifications()
        if (visible) {
            showMediaPlayer()
        } else {
            hideMediaPlayer()
        }
    }

    private fun showMediaPlayer() {
        if (useSplitShadeContainer()) {
            showMediaPlayer(
                    activeContainer = splitShadeContainer,
                    inactiveContainer = singlePaneContainer)
        } else {
            showMediaPlayer(
                    activeContainer = singlePaneContainer,
                    inactiveContainer = splitShadeContainer)
        }
    }

    private fun showMediaPlayer(activeContainer: ViewGroup?, inactiveContainer: ViewGroup?) {
        if (inactiveContainer?.childCount == 1) {
            inactiveContainer.removeAllViews()
        }
        // might be called a few times for the same view, no need to add hostView again
        if (activeContainer?.childCount == 0) {
            // Detach the hostView from its parent view if exists
            mediaHost.hostView.parent ?.let {
                (it as? ViewGroup)?.removeView(mediaHost.hostView)
            }
            activeContainer.addView(mediaHost.hostView)
        }
        setVisibility(activeContainer, View.VISIBLE)
        setVisibility(inactiveContainer, View.GONE)
    }

    private fun hideMediaPlayer() {
        if (useSplitShadeContainer()) {
            setVisibility(splitShadeContainer, View.GONE)
        } else {
            setVisibility(singlePaneContainer, View.GONE)
        }
    }

    private fun setVisibility(view: ViewGroup?, newVisibility: Int) {
        val previousVisibility = view?.visibility
        view?.visibility = newVisibility
        if (previousVisibility != newVisibility) {
            visibilityChangedListener?.invoke(newVisibility == View.VISIBLE)
        }
    }
}