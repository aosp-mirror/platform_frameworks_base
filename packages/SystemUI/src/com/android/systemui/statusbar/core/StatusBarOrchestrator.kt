/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.core

import android.view.Display
import android.view.View
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.PluginDependencyProvider
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.shade.NotificationShadeWindowViewController
import com.android.systemui.shade.ShadeSurface
import com.android.systemui.statusbar.AutoHideUiElement
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepository
import com.android.systemui.statusbar.phone.AutoHideController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.PhoneStatusBarTransitions
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.data.model.StatusBarWindowState
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStatePerDisplayRepository
import com.android.wm.shell.bubbles.Bubbles
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter
import java.util.Optional
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull

/**
 * Class responsible for managing the lifecycle and state of the status bar.
 *
 * It is a temporary class, created to pull status bar related logic out of CentralSurfacesImpl. The
 * plan is break it out into individual classes.
 */
class StatusBarOrchestrator
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Assisted private val coroutineScope: CoroutineScope,
    @Assisted private val statusBarWindowStateRepository: StatusBarWindowStatePerDisplayRepository,
    @Assisted private val statusBarModeRepository: StatusBarModePerDisplayRepository,
    @Assisted private val statusBarInitializer: StatusBarInitializer,
    @Assisted private val statusBarWindowController: StatusBarWindowController,
    @Main private val mainContext: CoroutineContext,
    @Assisted private val autoHideController: AutoHideController,
    private val demoModeController: DemoModeController,
    private val pluginDependencyProvider: PluginDependencyProvider,
    private val remoteInputManager: NotificationRemoteInputManager,
    private val notificationShadeWindowViewControllerLazy:
        Lazy<NotificationShadeWindowViewController>,
    private val shadeSurface: ShadeSurface,
    private val bubblesOptional: Optional<Bubbles>,
    private val dumpManager: DumpManager,
    powerInteractor: PowerInteractor,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
) : CoreStartable {

    private val dumpableName: String =
        if (displayId == Display.DEFAULT_DISPLAY) {
            javaClass.simpleName
        } else {
            "${javaClass.simpleName}$displayId"
        }

    private val phoneStatusBarViewController =
        MutableStateFlow<PhoneStatusBarViewController?>(value = null)

    private val phoneStatusBarTransitions =
        MutableStateFlow<PhoneStatusBarTransitions?>(value = null)

    private val shouldAnimateNextBarModeChange =
        combine(
            statusBarModeRepository.isTransientShown,
            powerInteractor.isAwake,
            statusBarWindowStateRepository.windowState,
        ) { isTransientShown, isDeviceAwake, statusBarWindowState ->
            !isTransientShown &&
                isDeviceAwake &&
                statusBarWindowState != StatusBarWindowState.Hidden
        }

    private val controllerAndBouncerShowing =
        combine(
            phoneStatusBarViewController.filterNotNull(),
            primaryBouncerInteractor.isShowing,
            ::Pair,
        )

    private val barTransitionsAndDeviceAsleep =
        combine(phoneStatusBarTransitions.filterNotNull(), powerInteractor.isAsleep, ::Pair)

    private val statusBarVisible =
        combine(
            statusBarModeRepository.statusBarMode,
            statusBarWindowStateRepository.windowState,
        ) { mode, statusBarWindowState ->
            mode != StatusBarMode.LIGHTS_OUT &&
                mode != StatusBarMode.LIGHTS_OUT_TRANSPARENT &&
                statusBarWindowState != StatusBarWindowState.Hidden
        }

    private val barModeUpdate =
        combine(
                shouldAnimateNextBarModeChange,
                phoneStatusBarTransitions.filterNotNull(),
                statusBarModeRepository.statusBarMode,
                ::Triple,
            )
            .distinctUntilChangedBy { (_, barTransitions, statusBarMode) ->
                // We only want to collect when either bar transitions or status bar mode
                // changed.
                Pair(barTransitions, statusBarMode)
            }

    override fun start() {
        StatusBarConnectedDisplays.assertInNewMode()
        coroutineScope
            // Perform animations on the main thread to prevent crashes.
            .launch(context = mainContext) {
                dumpManager.registerCriticalDumpable(dumpableName, this@StatusBarOrchestrator)
                launch {
                    controllerAndBouncerShowing.collect { (controller, bouncerShowing) ->
                        setBouncerShowingForStatusBarComponents(controller, bouncerShowing)
                    }
                }
                launch {
                    barTransitionsAndDeviceAsleep.collect { (barTransitions, deviceAsleep) ->
                        if (deviceAsleep) {
                            barTransitions.finishAnimations()
                        }
                    }
                }
                launch { statusBarVisible.collect { updateBubblesVisibility(it) } }
                launch {
                    barModeUpdate.collect { (animate, barTransitions, statusBarMode) ->
                        updateBarMode(animate, barTransitions, statusBarMode)
                    }
                }
            }
            .invokeOnCompletion { dumpManager.unregisterDumpable(dumpableName) }
        createAndAddWindow()
        setupPluginDependencies()
        setUpAutoHide()
    }

    private fun createAndAddWindow() {
        initializeStatusBarRootView()
        statusBarWindowController.attach()
    }

    private fun initializeStatusBarRootView() {
        statusBarInitializer.statusBarViewUpdatedListener =
            object : StatusBarInitializer.OnStatusBarViewUpdatedListener {
                override fun onStatusBarViewUpdated(
                    statusBarViewController: PhoneStatusBarViewController,
                    statusBarTransitions: PhoneStatusBarTransitions,
                ) {
                    phoneStatusBarViewController.value = statusBarViewController
                    phoneStatusBarTransitions.value = statusBarTransitions

                    if (displayId != Display.DEFAULT_DISPLAY) {
                        return
                    }
                    // TODO(b/373310629): shade should be display id aware
                    notificationShadeWindowViewControllerLazy
                        .get()
                        .setStatusBarViewController(statusBarViewController)
                    // Ensure we re-propagate panel expansion values to the panel controller and
                    // any listeners it may have, such as PanelBar. This will also ensure we
                    // re-display the notification panel if necessary (for example, if
                    // a heads-up notification was being displayed and should continue being
                    // displayed).
                    shadeSurface.updateExpansionAndVisibility()
                }
            }
    }

    private fun setupPluginDependencies() {
        pluginDependencyProvider.allowPluginDependency(DarkIconDispatcher::class.java)
        pluginDependencyProvider.allowPluginDependency(StatusBarStateController::class.java)
    }

    private fun setUpAutoHide() {
        autoHideController.setStatusBar(
            object : AutoHideUiElement {
                override fun synchronizeState() {}

                override fun shouldHideOnTouch(): Boolean {
                    return !remoteInputManager.isRemoteInputActive
                }

                override fun isVisible(): Boolean {
                    return statusBarModeRepository.isTransientShown.value
                }

                override fun hide() {
                    statusBarModeRepository.clearTransient()
                }
            }
        )
    }

    private fun updateBarMode(
        animate: Boolean,
        barTransitions: PhoneStatusBarTransitions,
        barMode: StatusBarMode,
    ) {
        if (!demoModeController.isInDemoMode) {
            barTransitions.transitionTo(barMode.toTransitionModeInt(), animate)
        }
        autoHideController.touchAutoHide()
    }

    private fun updateBubblesVisibility(statusBarVisible: Boolean) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            // Bubbles are currently only supported on the default display.
            return
        }
        bubblesOptional.ifPresent { bubbles: Bubbles ->
            bubbles.onStatusBarVisibilityChanged(statusBarVisible)
        }
    }

    private fun setBouncerShowingForStatusBarComponents(
        controller: PhoneStatusBarViewController,
        bouncerShowing: Boolean,
    ) {
        val importance =
            if (bouncerShowing) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        controller.setImportantForAccessibility(importance)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(statusBarWindowStateRepository.windowState.value)
        CentralSurfaces.dumpBarTransitions(
            pw,
            "PhoneStatusBarTransitions",
            phoneStatusBarTransitions.value,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            displayId: Int,
            displayScope: CoroutineScope,
            statusBarWindowStateRepository: StatusBarWindowStatePerDisplayRepository,
            statusBarModeRepository: StatusBarModePerDisplayRepository,
            statusBarInitializer: StatusBarInitializer,
            statusBarWindowController: StatusBarWindowController,
            autoHideController: AutoHideController,
        ): StatusBarOrchestrator
    }
}
