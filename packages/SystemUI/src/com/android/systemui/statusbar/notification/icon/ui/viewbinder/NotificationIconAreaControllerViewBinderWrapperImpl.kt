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
import android.view.View
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.statusbar.NotificationShelfController
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
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
    private val configuration: ConfigurationState,
    private val configurationController: ConfigurationController,
    private val dozeParameters: DozeParameters,
    private val featureFlags: FeatureFlagsClassic,
    private val screenOffAnimationController: ScreenOffAnimationController,
    private val aodIconViewStore: AlwaysOnDisplayNotificationIconViewStore,
    private val aodIconsViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
) : NotificationIconAreaController {

    private var aodIcons: NotificationIconContainer? = null
    private var aodBindJob: DisposableHandle? = null

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

    override fun setShelfIcons(icons: NotificationIconContainer) = unsupported

    override fun onDensityOrFontScaleChanged(context: Context) = unsupported

    /** Returns the view that represents the notification area. */
    override fun getNotificationInnerAreaView(): View? = unsupported

    /** Updates the notifications with the given list of notifications to display. */
    override fun updateNotificationIcons(entries: List<ListEntry>) = unsupported

    override fun updateAodNotificationIcons() = unsupported

    override fun showIconIsolated(icon: StatusBarIconView?, animated: Boolean) = unsupported

    override fun setIsolatedIconLocation(iconDrawingRect: Rect, requireStateUpdate: Boolean) =
        unsupported

    override fun setAnimationsEnabled(enabled: Boolean) = unsupported

    override fun onThemeChanged() = unsupported

    override fun getHeight(): Int {
        return if (aodIcons == null) 0 else aodIcons!!.height
    }

    companion object {
        val unsupported: Nothing
            get() =
                error(
                    "Code path not supported when NOTIFICATION_ICON_CONTAINER_REFACTOR is disabled"
                )
    }
}
