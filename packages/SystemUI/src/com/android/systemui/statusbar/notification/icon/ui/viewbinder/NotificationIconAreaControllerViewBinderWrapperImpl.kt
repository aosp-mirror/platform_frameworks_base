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
package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.RefactorFlag
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewbinder.NotificationShelfViewBinderWrapperControllerImpl
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Controller class for [NotificationIconContainer]. This implementation serves as a temporary
 * wrapper around [NotificationIconContainerViewBinder], so that external code can continue to
 * depend on the [NotificationIconAreaController] interface. Once
 * [LegacyNotificationIconAreaControllerImpl] is removed, this class can go away and the ViewBinder
 * can be used directly.
 */
@SysUISingleton
class NotificationIconAreaControllerViewBinderWrapperImpl
@Inject
constructor(
    context: Context,
    private val configuration: ConfigurationState,
    private val configurationController: ConfigurationController,
    private val dozeParameters: DozeParameters,
    demoModeController: DemoModeController,
    private val featureFlags: FeatureFlagsClassic,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val shelfIconViewStore: ShelfNotificationIconViewStore,
    private val shelfIconsViewModel: NotificationIconContainerShelfViewModel,
    private val aodIconViewStore: AlwaysOnDisplayNotificationIconViewStore,
    private val aodIconsViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val statusBarIconViewStore: StatusBarNotificationIconViewStore,
    private val statusBarIconsViewModel: NotificationIconContainerStatusBarViewModel,
) : NotificationIconAreaController, DemoMode {

    private val shelfRefactor = RefactorFlag(featureFlags, Flags.NOTIFICATION_SHELF_REFACTOR)

    private var notificationIconArea: View? = null
    private var notificationIcons: NotificationIconContainer? = null
    private var shelfIcons: NotificationIconContainer? = null
    private var aodIcons: NotificationIconContainer? = null
    private var aodBindJob: DisposableHandle? = null

    init {
        demoModeController.addCallback(this)
        initializeNotificationAreaViews(context)
    }

    /** Called by the Keyguard*ViewController whose view contains the aod icons. */
    override fun setupAodIcons(aodIcons: NotificationIconContainer) {
        val changed = this.aodIcons != null && aodIcons !== this.aodIcons
        if (changed) {
            this.aodIcons!!.setAnimationsEnabled(false)
            this.aodIcons!!.removeAllViews()
            aodBindJob?.dispose()
        }
        this.aodIcons = aodIcons
        this.aodIcons!!.setOnLockScreen(true)
        aodBindJob =
            NotificationIconContainerViewBinder.bind(
                aodIcons,
                aodIconsViewModel,
                configuration,
                configurationController,
                dozeParameters,
                featureFlags,
                screenOffAnimationController,
                aodIconViewStore,
            )
    }

    override fun setupShelf(notificationShelfController: NotificationShelfController) =
        NotificationShelfViewBinderWrapperControllerImpl.unsupported

    override fun setShelfIcons(icons: NotificationIconContainer) {
        if (shelfRefactor.isUnexpectedlyInLegacyMode()) {
            NotificationIconContainerViewBinder.bind(
                icons,
                shelfIconsViewModel,
                configuration,
                configurationController,
                dozeParameters,
                featureFlags,
                screenOffAnimationController,
                shelfIconViewStore,
            )
            shelfIcons = icons
        }
    }

    override fun onDensityOrFontScaleChanged(context: Context) = unsupported

    /** Returns the view that represents the notification area. */
    override fun getNotificationInnerAreaView(): View? {
        return notificationIconArea
    }

    /** Updates the notifications with the given list of notifications to display. */
    override fun updateNotificationIcons(entries: List<ListEntry>) = unsupported

    override fun updateAodNotificationIcons() = unsupported

    override fun showIconIsolated(icon: StatusBarIconView?, animated: Boolean) {
        notificationIcons!!.showIconIsolated(icon, animated)
    }

    override fun setIsolatedIconLocation(iconDrawingRect: Rect, requireStateUpdate: Boolean) {
        notificationIcons!!.setIsolatedIconLocation(iconDrawingRect, requireStateUpdate)
    }

    override fun setAnimationsEnabled(enabled: Boolean) = unsupported

    override fun onThemeChanged() = unsupported

    override fun getHeight(): Int {
        return if (aodIcons == null) 0 else aodIcons!!.height
    }

    override fun demoCommands(): List<String> {
        val commands = ArrayList<String>()
        commands.add(DemoMode.COMMAND_NOTIFICATIONS)
        return commands
    }

    override fun dispatchDemoCommand(command: String, args: Bundle) {
        if (notificationIconArea != null) {
            val visible = args.getString("visible")
            val vis = if ("false" == visible) View.INVISIBLE else View.VISIBLE
            notificationIconArea?.visibility = vis
        }
    }

    override fun onDemoModeFinished() {
        if (notificationIconArea != null) {
            notificationIconArea?.visibility = View.VISIBLE
        }
    }

    private fun inflateIconArea(inflater: LayoutInflater): View {
        return inflater.inflate(R.layout.notification_icon_area, null)
    }

    /** Initializes the views that will represent the notification area. */
    private fun initializeNotificationAreaViews(context: Context) {
        val layoutInflater = LayoutInflater.from(context)
        notificationIconArea = inflateIconArea(layoutInflater)
        notificationIcons = notificationIconArea?.findViewById(R.id.notificationIcons)
        NotificationIconContainerViewBinder.bind(
            notificationIcons!!,
            statusBarIconsViewModel,
            configuration,
            configurationController,
            dozeParameters,
            featureFlags,
            screenOffAnimationController,
            statusBarIconViewStore,
        )
    }

    companion object {
        val unsupported: Nothing
            get() =
                error(
                    "Code path not supported when NOTIFICATION_ICON_CONTAINER_REFACTOR is disabled"
                )
    }
}
