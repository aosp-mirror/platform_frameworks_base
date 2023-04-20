/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade.transition

import android.content.Context
import android.content.res.Configuration
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.shade.PanelState
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.panelStateToString
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.ConfigurationController
import java.io.PrintWriter
import javax.inject.Inject

/** Controls the shade expansion transition on non-lockscreen. */
@SysUISingleton
class ShadeTransitionController
@Inject
constructor(
    configurationController: ConfigurationController,
    shadeExpansionStateManager: ShadeExpansionStateManager,
    dumpManager: DumpManager,
    private val context: Context,
    private val splitShadeOverScrollerFactory: SplitShadeOverScroller.Factory,
    private val noOpOverScroller: NoOpOverScroller,
    private val scrimShadeTransitionController: ScrimShadeTransitionController,
    private val statusBarStateController: SysuiStatusBarStateController,
) {

    lateinit var notificationPanelViewController: NotificationPanelViewController
    lateinit var notificationStackScrollLayoutController: NotificationStackScrollLayoutController
    lateinit var qs: QS

    private var inSplitShade = false
    private var currentPanelState: Int? = null
    private var lastShadeExpansionChangeEvent: ShadeExpansionChangeEvent? = null

    private val splitShadeOverScroller by lazy {
        splitShadeOverScrollerFactory.create({ qs }, { notificationStackScrollLayoutController })
    }
    private val shadeOverScroller: ShadeOverScroller
        get() =
            if (inSplitShade && isScreenUnlocked() && propertiesInitialized()) {
                splitShadeOverScroller
            } else {
                noOpOverScroller
            }

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            })
        shadeExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged)
        shadeExpansionStateManager.addStateListener(this::onPanelStateChanged)
        dumpManager.registerCriticalDumpable("ShadeTransitionController") { printWriter, _ ->
            dump(printWriter)
        }
    }

    private fun updateResources() {
        inSplitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
    }

    private fun onPanelStateChanged(@PanelState state: Int) {
        currentPanelState = state
        shadeOverScroller.onPanelStateChanged(state)
        scrimShadeTransitionController.onPanelStateChanged(state)
    }

    private fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) {
        lastShadeExpansionChangeEvent = event
        shadeOverScroller.onDragDownAmountChanged(event.dragDownPxAmount)
        scrimShadeTransitionController.onPanelExpansionChanged(event)
    }

    private fun propertiesInitialized() =
        this::qs.isInitialized &&
            this::notificationPanelViewController.isInitialized &&
            this::notificationStackScrollLayoutController.isInitialized

    private fun dump(pw: PrintWriter) {
        pw.println(
            """
            ShadeTransitionController:
                inSplitShade: $inSplitShade
                isScreenUnlocked: ${isScreenUnlocked()}
                currentPanelState: ${currentPanelState?.panelStateToString()}
                lastPanelExpansionChangeEvent: $lastShadeExpansionChangeEvent
                qs.isInitialized: ${this::qs.isInitialized}
                npvc.isInitialized: ${this::notificationPanelViewController.isInitialized}
                nssl.isInitialized: ${this::notificationStackScrollLayoutController.isInitialized}
            """.trimIndent())
    }

    private fun isScreenUnlocked() =
        statusBarStateController.currentOrUpcomingState == StatusBarState.SHADE
}
