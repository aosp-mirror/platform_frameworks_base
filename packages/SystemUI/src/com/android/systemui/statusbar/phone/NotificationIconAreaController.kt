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
package com.android.systemui.statusbar.phone

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.ListEntry

/**
 * A controller for the space in the status bar to the left of the system icons. This area is
 * normally reserved for notifications.
 */
interface NotificationIconAreaController {
    /** Called by the Keyguard*ViewController whose view contains the aod icons. */
    fun setupAodIcons(aodIcons: NotificationIconContainer?)
    fun setShelfIcons(icons: NotificationIconContainer)
    fun onDensityOrFontScaleChanged(context: Context)

    /** Returns the view that represents the notification area. */
    fun getNotificationInnerAreaView(): View?

    /** Updates the notifications with the given list of notifications to display. */
    fun updateNotificationIcons(entries: List<@JvmSuppressWildcards ListEntry>)
    fun updateAodNotificationIcons()
    fun showIconIsolated(icon: StatusBarIconView?, animated: Boolean)
    fun setIsolatedIconLocation(iconDrawingRect: Rect, requireStateUpdate: Boolean)
    fun setAnimationsEnabled(enabled: Boolean)
    fun onThemeChanged()
    fun getHeight(): Int
}
