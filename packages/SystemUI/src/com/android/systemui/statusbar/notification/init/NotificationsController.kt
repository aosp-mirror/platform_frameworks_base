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

package com.android.systemui.statusbar.notification.init

import android.service.notification.StatusBarNotification
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.StatusBar
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * The master controller for all notifications-related work
 *
 * Split into two implementations: [NotificationsControllerImpl] (most cases) and
 * [NotificationsControllerStub] (for builds that disable notification rendering).
 */
interface NotificationsController {
    fun initialize(
        statusBar: StatusBar,
        presenter: NotificationPresenter,
        listContainer: NotificationListContainer,
        notificationActivityStarter: NotificationActivityStarter,
        bindRowCallback: NotificationRowBinderImpl.BindRowCallback
    )

    fun requestNotificationUpdate(reason: String)
    fun resetUserExpandedStates()
    fun setNotificationSnoozed(sbn: StatusBarNotification, snoozeOption: SnoozeOption)
    fun getActiveNotificationsCount(): Int
    fun setNotificationSnoozed(sbn: StatusBarNotification, hoursToSnooze: Int)
    fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>, dumpTruck: Boolean)
}
