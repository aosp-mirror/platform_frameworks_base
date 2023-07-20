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

import android.view.View
import android.view.ViewGroup
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder
import com.android.systemui.keyguard.ui.binder.KeyguardRootViewBinder
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.KeyguardLayoutManager
import com.android.systemui.keyguard.ui.view.layout.KeyguardLayoutManagerCommandListener
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludingAppDeviceEntryMessageViewModel
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
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
    private val keyguardIndicationAreaViewModel: KeyguardIndicationAreaViewModel,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val notificationShadeWindowView: NotificationShadeWindowView,
    private val featureFlags: FeatureFlags,
    private val indicationController: KeyguardIndicationController,
    private val keyguardLayoutManager: KeyguardLayoutManager,
    private val keyguardLayoutManagerCommandListener: KeyguardLayoutManagerCommandListener,
    private val occludingAppDeviceEntryMessageViewModel: OccludingAppDeviceEntryMessageViewModel,
    private val chipbarCoordinator: ChipbarCoordinator,
) : CoreStartable {

    private var indicationAreaHandle: DisposableHandle? = null

    override fun start() {
        bindKeyguardRootView()
        val notificationPanel =
            notificationShadeWindowView.requireViewById(R.id.notification_panel) as ViewGroup
        bindIndicationArea(notificationPanel)
        bindLockIconView(notificationPanel)
        setupNotificationStackScrollLayout(notificationPanel)

        keyguardLayoutManager.layoutViews()
        keyguardLayoutManagerCommandListener.start()
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
                sharedNotificationContainerViewModel
            )
        }
    }

    fun bindIndicationArea(legacyParent: ViewGroup) {
        indicationAreaHandle?.dispose()

        // At startup, 2 views with the ID `R.id.keyguard_indication_area` will be available.
        // Disable one of them
        if (featureFlags.isEnabled(Flags.MIGRATE_INDICATION_AREA)) {
            legacyParent.findViewById<View>(R.id.keyguard_indication_area)?.let {
                legacyParent.removeView(it)
            }
        } else {
            keyguardRootView.findViewById<View?>(R.id.keyguard_indication_area)?.let {
                keyguardRootView.removeView(it)
            }
        }

        indicationAreaHandle =
            KeyguardIndicationAreaBinder.bind(
                notificationShadeWindowView,
                keyguardIndicationAreaViewModel,
                indicationController
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

    private fun bindKeyguardRootView() {
        KeyguardRootViewBinder.bind(
            keyguardRootView,
            featureFlags,
            occludingAppDeviceEntryMessageViewModel,
            chipbarCoordinator,
        )
    }
}
