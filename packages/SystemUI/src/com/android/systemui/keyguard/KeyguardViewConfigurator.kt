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
 *
 */

package com.android.systemui.keyguard

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.keyguard.KeyguardStatusView
import com.android.keyguard.KeyguardStatusViewController
import com.android.keyguard.dagger.KeyguardStatusViewComponent
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.communal.ui.adapter.CommunalWidgetViewAdapter
import com.android.systemui.communal.ui.binder.CommunalWidgetViewBinder
import com.android.systemui.communal.ui.viewmodel.CommunalWidgetViewModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.binder.KeyguardAmbientIndicationAreaViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardBlueprintViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder
import com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardSettingsViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardIndicationArea
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.KeyguardBlueprintCommandListener
import com.android.systemui.keyguard.ui.viewmodel.KeyguardAmbientIndicationViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordancesCombinedViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSettingsMenuViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Binds keyguard views on startup, and also exposes methods to allow rebinding if views change */
@ExperimentalCoroutinesApi
@SysUISingleton
class KeyguardViewConfigurator
@Inject
constructor(
    private val keyguardRootView: KeyguardRootView,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    private val keyguardIndicationAreaViewModel: KeyguardIndicationAreaViewModel,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val keyguardAmbientIndicationViewModel: KeyguardAmbientIndicationViewModel,
    private val notificationShadeWindowView: NotificationShadeWindowView,
    private val featureFlags: FeatureFlags,
    private val indicationController: KeyguardIndicationController,
    private val keyguardQuickAffordancesCombinedViewModel:
        KeyguardQuickAffordancesCombinedViewModel,
    private val falsingManager: FalsingManager,
    private val vibratorHelper: VibratorHelper,
    private val keyguardStateController: KeyguardStateController,
    private val keyguardSettingsMenuViewModel: KeyguardSettingsMenuViewModel,
    private val activityStarter: ActivityStarter,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
    private val keyguardBlueprintCommandListener: KeyguardBlueprintCommandListener,
    private val keyguardBlueprintViewModel: KeyguardBlueprintViewModel,
    private val keyguardStatusViewComponentFactory: KeyguardStatusViewComponent.Factory,
    private val keyguardBlueprintInteractor: KeyguardBlueprintInteractor,
    private val communalWidgetViewModel: CommunalWidgetViewModel,
    private val communalWidgetViewAdapter: CommunalWidgetViewAdapter,
    private val notificationStackScrollerLayoutController: NotificationStackScrollLayoutController,
    private val context: Context,
    private val keyguardIndicationController: KeyguardIndicationController,
) : CoreStartable {

    private var rootViewHandle: DisposableHandle? = null
    private var indicationAreaHandle: DisposableHandle? = null
    private var leftShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null
    private var rightShortcutHandle: KeyguardQuickAffordanceViewBinder.Binding? = null
    private var ambientIndicationAreaHandle: KeyguardAmbientIndicationAreaViewBinder.Binding? = null
    private var settingsPopupMenuHandle: DisposableHandle? = null
    var keyguardStatusViewController: KeyguardStatusViewController? = null
        get() {
            if (field == null) {
                val statusViewComponent =
                    keyguardStatusViewComponentFactory.build(
                        LayoutInflater.from(context).inflate(R.layout.keyguard_status_view, null)
                            as KeyguardStatusView
                    )
                val controller = statusViewComponent.keyguardStatusViewController
                controller.init()
                field = controller
            }

            return field
        }
        private set

    override fun start() {
        initializeViews()
        bindKeyguardRootView()
        val notificationPanel =
            notificationShadeWindowView.requireViewById(R.id.notification_panel) as ViewGroup
        unbindKeyguardBottomArea(notificationPanel)
        bindIndicationArea()
        bindLockIconView(notificationPanel)
        bindKeyguardStatusView(notificationPanel)
        setupNotificationStackScrollLayout(notificationPanel)
        bindLeftShortcut()
        bindRightShortcut()
        bindAmbientIndicationArea()
        bindSettingsPopupMenu()
        bindCommunalWidgetArea()

        KeyguardBlueprintViewBinder.bind(keyguardRootView, keyguardBlueprintViewModel)
        keyguardBlueprintCommandListener.start()
    }

    fun setupNotificationStackScrollLayout(legacyParent: ViewGroup) {
        if (featureFlags.isEnabled(Flags.MIGRATE_NSSL)) {
            // This moves the existing NSSL view to a different parent, as the controller is a
            // singleton and recreating it has other bad side effects
            val nssl =
                legacyParent.requireViewById<View>(R.id.notification_stack_scroller).also {
                    (it.getParent() as ViewGroup).removeView(it)
                }
            sharedNotificationContainer.addNotificationStackScrollLayout(nssl)
            SharedNotificationContainerBinder.bind(
                sharedNotificationContainer,
                sharedNotificationContainerViewModel,
                notificationStackScrollerLayoutController,
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        leftShortcutHandle?.onConfigurationChanged()
        rightShortcutHandle?.onConfigurationChanged()
        ambientIndicationAreaHandle?.onConfigurationChanged()
    }

    fun bindIndicationArea() {
        indicationAreaHandle?.dispose()

        if (!featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            keyguardRootView.findViewById<View?>(R.id.keyguard_indication_area)?.let {
                keyguardRootView.removeView(it)
            }
        }

        indicationAreaHandle =
            KeyguardIndicationAreaBinder.bind(
                notificationShadeWindowView,
                keyguardIndicationAreaViewModel,
                keyguardRootViewModel,
                indicationController,
                featureFlags,
            )
    }

    /** Initialize views so that corresponding controllers have a view set. */
    private fun initializeViews() {
        val indicationArea = KeyguardIndicationArea(context, null)
        keyguardIndicationController.setIndicationArea(indicationArea)
    }

    private fun bindKeyguardRootView() {
        rootViewHandle?.dispose()
        rootViewHandle =
            KeyguardRootViewBinder.bind(
                keyguardRootView,
                keyguardRootViewModel,
                featureFlags,
                occludingAppDeviceEntryMessageViewModel,
                chipbarCoordinator,
                keyguardStateController,
            )
    }

    private fun bindLockIconView(legacyParent: ViewGroup) {
        if (featureFlags.isEnabled(Flags.MIGRATE_LOCK_ICON)) {
            legacyParent.requireViewById<View>(R.id.lock_icon_view).let {
                legacyParent.removeView(it)
            }
        } else {
            keyguardRootView.findViewById<View?>(R.id.lock_icon_view)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    private fun bindAmbientIndicationArea() {
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            ambientIndicationAreaHandle?.destroy()
            ambientIndicationAreaHandle =
                KeyguardAmbientIndicationAreaViewBinder.bind(
                    notificationShadeWindowView,
                    keyguardAmbientIndicationViewModel,
                    keyguardRootViewModel,
                )
        } else {
            keyguardRootView.findViewById<View?>(R.id.ambient_indication_container)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    private fun bindSettingsPopupMenu() {
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            settingsPopupMenuHandle?.dispose()
            settingsPopupMenuHandle =
                KeyguardSettingsViewBinder.bind(
                    keyguardRootView,
                    keyguardSettingsMenuViewModel,
                    vibratorHelper,
                    activityStarter,
                )
        } else {
            keyguardRootView.findViewById<View?>(R.id.keyguard_settings_button)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    private fun unbindKeyguardBottomArea(legacyParent: ViewGroup) {
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            legacyParent.requireViewById<View>(R.id.keyguard_bottom_area).let {
                legacyParent.removeView(it)
            }
        }
    }

    private fun bindLeftShortcut() {
        leftShortcutHandle?.destroy()
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            leftShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    keyguardRootView.requireViewById(R.id.start_button),
                    keyguardQuickAffordancesCombinedViewModel.startButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
        } else {
            keyguardRootView.findViewById<View?>(R.id.start_button)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    private fun bindRightShortcut() {
        rightShortcutHandle?.destroy()
        if (featureFlags.isEnabled(Flags.MIGRATE_SPLIT_KEYGUARD_BOTTOM_AREA)) {
            rightShortcutHandle =
                KeyguardQuickAffordanceViewBinder.bind(
                    keyguardRootView.requireViewById(R.id.end_button),
                    keyguardQuickAffordancesCombinedViewModel.endButton,
                    keyguardRootViewModel.alpha,
                    falsingManager,
                    vibratorHelper,
                ) {
                    indicationController.showTransientIndication(it)
                }
        } else {
            keyguardRootView.findViewById<View?>(R.id.end_button)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    fun bindKeyguardStatusView(legacyParent: ViewGroup) {
        // At startup, 2 views with the ID `R.id.keyguard_status_view` will be available.
        // Disable one of them
        if (featureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_VIEW)) {
            legacyParent.findViewById<View>(R.id.keyguard_status_view)?.let {
                legacyParent.removeView(it)
            }

            val keyguardStatusView = keyguardRootView.addStatusView()
            val statusViewComponent = keyguardStatusViewComponentFactory.build(keyguardStatusView)
            val controller = statusViewComponent.getKeyguardStatusViewController()
            controller.init()
            keyguardStatusViewController = controller
        } else {
            keyguardRootView.findViewById<View?>(R.id.keyguard_status_view)?.let {
                keyguardRootView.removeView(it)
            }
        }
    }

    private fun bindCommunalWidgetArea() {
        if (!featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)) {
            return
        }

        CommunalWidgetViewBinder.bind(
            keyguardRootView,
            communalWidgetViewModel,
            communalWidgetViewAdapter,
            keyguardBlueprintInteractor,
        )
    }

    /**
     * Temporary, to allow NotificationPanelViewController to use the same instance while code is
     * migrated: b/288242803
     */
    fun getKeyguardRootView() = keyguardRootView
}
