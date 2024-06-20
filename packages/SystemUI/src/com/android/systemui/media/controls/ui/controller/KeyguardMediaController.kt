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

package com.android.systemui.media.controls.ui.controller

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule.KEYGUARD
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.asIndenting
import com.android.systemui.util.println
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
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
    configurationController: ConfigurationController,
    private val splitShadeStateController: SplitShadeStateController,
    private val logger: KeyguardMediaControllerLogger,
    dumpManager: DumpManager,
) : Dumpable {
    private var lastUsedStatusBarState = -1

    init {
        dumpManager.registerDumpable(this)
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    refreshMediaPosition(reason = "StatusBarState.onStateChanged")
                }

                override fun onDozingChanged(isDozing: Boolean) {
                    refreshMediaPosition(reason = "StatusBarState.onDozingChanged")
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

        // First let's set the desired state that we want for this host
        mediaHost.expansion = MediaHostState.EXPANDED
        mediaHost.showsOnlyActiveMedia = true
        mediaHost.falsingProtectionNeeded = true

        // Let's now initialize this view, which also creates the host view for us.
        mediaHost.init(MediaHierarchyManager.LOCATION_LOCKSCREEN)
        updateResources()
    }

    private fun updateResources() {
        useSplitShade = splitShadeStateController.shouldUseSplitNotificationShade(context.resources)
    }

    @VisibleForTesting
    var useSplitShade = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reattachHostView()
            refreshMediaPosition(reason = "useSplitShade changed")
        }

    /** Is the media player visible? */
    var visible = false
        private set

    var visibilityChangedListener: ((Boolean) -> Unit)? = null

    /**
     * Whether the doze wake up animation is delayed and we are currently waiting for it to start.
     */
    var isDozeWakeUpAnimationWaiting: Boolean = false
        set(value) {
            field = value
            refreshMediaPosition(reason = "isDozeWakeUpAnimationWaiting changed")
        }

    /** single pane media container placed at the top of the notifications list */
    var singlePaneContainer: MediaContainerView? = null
        private set
    private var splitShadeContainer: ViewGroup? = null

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

        singlePaneContainer?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Called whenever the media hosts visibility changes */
    private fun onMediaHostVisibilityChanged(visible: Boolean) {
        refreshMediaPosition(reason = "onMediaHostVisibilityChanged")

        if (visible) {
            if (MigrateClocksToBlueprint.isEnabled && useSplitShade) {
                return
            }
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
        refreshMediaPosition(reason = "attachSplitShadeContainer")
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

    fun refreshMediaPosition(reason: String) {
        val currentState = statusBarStateController.state

        val keyguardOrUserSwitcher = (currentState == StatusBarState.KEYGUARD)
        // mediaHost.visible required for proper animations handling
        val isMediaHostVisible = mediaHost.visible
        val isBypassNotEnabled = !bypassController.bypassEnabled
        val useSplitShade = useSplitShade
        val shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade()
        visible =
            isMediaHostVisible &&
                isBypassNotEnabled &&
                keyguardOrUserSwitcher &&
                shouldBeVisibleForSplitShade
        logger.logRefreshMediaPosition(
            reason = reason,
            visible = visible,
            useSplitShade = useSplitShade,
            currentState = currentState,
            keyguardOrUserSwitcher = keyguardOrUserSwitcher,
            mediaHostVisible = isMediaHostVisible,
            bypassNotEnabled = isBypassNotEnabled,
            shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade,
        )
        val currActiveContainer = activeContainer

        logger.logActiveMediaContainer("before refreshMediaPosition", currActiveContainer)
        if (visible) {
            showMediaPlayer()
        } else {
            hideMediaPlayer()
        }
        logger.logActiveMediaContainer("after refreshMediaPosition", currActiveContainer)

        lastUsedStatusBarState = currentState
    }

    private fun shouldBeVisibleForSplitShade(): Boolean {
        if (!useSplitShade) {
            return true
        }
        // We have to explicitly hide media for split shade when on AOD, as it is a child view of
        // keyguard status view, and nothing hides keyguard status view on AOD.
        // When using the double-line clock, it is not an issue, as media gets implicitly hidden
        // by the clock. This is not the case for single-line clock though.
        // For single shade, we don't need to do it, because media is a child of NSSL, which already
        // gets hidden on AOD.
        // Media also has to be hidden when waking up from dozing, and the doze wake up animation is
        // delayed and waiting to be started.
        // This is to stay in sync with the delaying of the horizontal alignment of the rest of the
        // keyguard container, that is also delayed until the "wait" is over.
        // If we show media during this waiting period, the shade will still be centered, and using
        // the entire width of the screen, and making media show fully stretched.
        return !statusBarStateController.isDozing && !isDozeWakeUpAnimationWaiting
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
        val currentMediaContainer = view ?: return

        val isVisible = newVisibility == View.VISIBLE

        if (currentMediaContainer is MediaContainerView) {
            val previousVisibility = currentMediaContainer.visibility

            currentMediaContainer.setKeyguardVisibility(isVisible)
            if (previousVisibility != newVisibility) {
                visibilityChangedListener?.invoke(isVisible)
            }
        } else {
            currentMediaContainer.visibility = newVisibility
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            println("KeyguardMediaController")
            withIncreasedIndent {
                println("Self", this@KeyguardMediaController)
                println("visible", visible)
                println("useSplitShade", useSplitShade)
                println("bypassController.bypassEnabled", bypassController.bypassEnabled)
                println("isDozeWakeUpAnimationWaiting", isDozeWakeUpAnimationWaiting)
                println("singlePaneContainer", singlePaneContainer)
                println("splitShadeContainer", splitShadeContainer)
                if (lastUsedStatusBarState != -1) {
                    println(
                        "lastUsedStatusBarState",
                        StatusBarState.toString(lastUsedStatusBarState)
                    )
                }
                println(
                    "statusBarStateController.state",
                    StatusBarState.toString(statusBarStateController.state)
                )
            }
        }
    }

    // This field is only used to log current active container.
    private val activeContainer: ViewGroup?
        get() = if (useSplitShade) splitShadeContainer else singlePaneContainer
}
