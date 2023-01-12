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

package com.android.systemui.media.controls.ui

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.dagger.MediaModule.KEYGUARD
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import javax.inject.Named

/**
 * Controls the media notifications on the lock screen, handles its visibility and placement -
 * switches media player positioning between split pane container vs single pane container
 */
@SysUISingleton
class KeyguardMediaController
@Inject
constructor(
    @param:Named(KEYGUARD) private val mediaHost: MediaHost,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val context: Context,
    private val secureSettings: SecureSettings,
    @Main private val handler: Handler,
    configurationController: ConfigurationController,
) {

    init {
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    refreshMediaPosition()
                }
            }
        )
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            }
        )

        val settingsObserver: ContentObserver =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    if (uri == lockScreenMediaPlayerUri) {
                        allowMediaPlayerOnLockScreen =
                            secureSettings.getBoolForUser(
                                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                                true,
                                UserHandle.USER_CURRENT
                            )
                        refreshMediaPosition()
                    }
                }
            }
        secureSettings.registerContentObserverForUser(
            Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
            settingsObserver,
            UserHandle.USER_ALL
        )

        // First let's set the desired state that we want for this host
        mediaHost.expansion = MediaHostState.EXPANDED
        mediaHost.showsOnlyActiveMedia = true
        mediaHost.falsingProtectionNeeded = true

        // Let's now initialize this view, which also creates the host view for us.
        mediaHost.init(MediaHierarchyManager.LOCATION_LOCKSCREEN)
        updateResources()
    }

    private fun updateResources() {
        useSplitShade = LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)
    }

    @VisibleForTesting
    var useSplitShade = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reattachHostView()
            refreshMediaPosition()
        }

    /** Is the media player visible? */
    var visible = false
        private set

    var visibilityChangedListener: ((Boolean) -> Unit)? = null

    /** single pane media container placed at the top of the notifications list */
    var singlePaneContainer: MediaContainerView? = null
        private set
    private var splitShadeContainer: ViewGroup? = null

    /** Track the media player setting status on lock screen. */
    private var allowMediaPlayerOnLockScreen: Boolean =
        secureSettings.getBoolForUser(
            Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
            true,
            UserHandle.USER_CURRENT
        )
    private val lockScreenMediaPlayerUri =
        secureSettings.getUriFor(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN)

    /**
     * Attaches media container in single pane mode, situated at the top of the notifications list
     */
    fun attachSinglePaneContainer(mediaView: MediaContainerView?) {
        val needsListener = singlePaneContainer == null
        singlePaneContainer = mediaView
        if (needsListener) {
            // On reinflation we don't want to add another listener
            mediaHost.addVisibilityChangeListener(this::onMediaHostVisibilityChanged)
        }
        reattachHostView()
        onMediaHostVisibilityChanged(mediaHost.visible)
    }

    /** Called whenever the media hosts visibility changes */
    private fun onMediaHostVisibilityChanged(visible: Boolean) {
        refreshMediaPosition()
        if (visible) {
            mediaHost.hostView.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    /** Attaches media container in split shade mode, situated to the left of notifications */
    fun attachSplitShadeContainer(container: ViewGroup) {
        splitShadeContainer = container
        reattachHostView()
        refreshMediaPosition()
    }

    private fun reattachHostView() {
        val inactiveContainer: ViewGroup?
        val activeContainer: ViewGroup?
        if (useSplitShade) {
            activeContainer = splitShadeContainer
            inactiveContainer = singlePaneContainer
        } else {
            inactiveContainer = splitShadeContainer
            activeContainer = singlePaneContainer
        }
        if (inactiveContainer?.childCount == 1) {
            inactiveContainer.removeAllViews()
        }
        if (activeContainer?.childCount == 0) {
            // Detach the hostView from its parent view if exists
            mediaHost.hostView.parent?.let { (it as? ViewGroup)?.removeView(mediaHost.hostView) }
            activeContainer.addView(mediaHost.hostView)
        }
    }

    fun refreshMediaPosition() {
        val keyguardOrUserSwitcher = (statusBarStateController.state == StatusBarState.KEYGUARD)
        // mediaHost.visible required for proper animations handling
        visible =
            mediaHost.visible &&
                !bypassController.bypassEnabled &&
                keyguardOrUserSwitcher &&
                allowMediaPlayerOnLockScreen
        if (visible) {
            showMediaPlayer()
        } else {
            hideMediaPlayer()
        }
    }

    private fun showMediaPlayer() {
        if (useSplitShade) {
            setVisibility(splitShadeContainer, View.VISIBLE)
            setVisibility(singlePaneContainer, View.GONE)
        } else {
            setVisibility(singlePaneContainer, View.VISIBLE)
            setVisibility(splitShadeContainer, View.GONE)
        }
    }

    private fun hideMediaPlayer() {
        // always hide splitShadeContainer as it's initially visible and may influence layout
        setVisibility(splitShadeContainer, View.GONE)
        setVisibility(singlePaneContainer, View.GONE)
    }

    private fun setVisibility(view: ViewGroup?, newVisibility: Int) {
        val previousVisibility = view?.visibility
        view?.visibility = newVisibility
        if (previousVisibility != newVisibility) {
            visibilityChangedListener?.invoke(newVisibility == View.VISIBLE)
        }
    }
}
