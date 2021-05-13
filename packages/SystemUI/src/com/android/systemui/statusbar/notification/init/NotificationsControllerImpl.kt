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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.people.widget.PeopleSpaceWidgetManager
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.AnimatedImageNotificationManager
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.NotificationClicker
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.NotificationListController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager
import com.android.systemui.statusbar.notification.collection.TargetSdkResolver
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl
import com.android.systemui.statusbar.notification.collection.init.NotifPipelineInitializer
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy
import com.android.systemui.statusbar.notification.interruption.HeadsUpController
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.row.NotifBindPipelineInitializer
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.NotificationGroupAlertTransferHelper
import com.android.systemui.statusbar.phone.StatusBar
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.RemoteInputUriController
import com.android.wm.shell.bubbles.Bubbles
import dagger.Lazy
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject

/**
 * Master controller for all notifications-related work
 *
 * At the moment exposes a number of event-handler-esque methods; these are for historical reasons.
 * Once we migrate away from the need for such things, this class becomes primarily a place to do
 * any initialization work that notifications require.
 */
@SysUISingleton
class NotificationsControllerImpl @Inject constructor(
    private val featureFlags: FeatureFlags,
    private val notificationListener: NotificationListener,
    private val entryManager: NotificationEntryManager,
    private val legacyRanker: NotificationRankingManager,
    private val notifPipeline: Lazy<NotifPipeline>,
    private val targetSdkResolver: TargetSdkResolver,
    private val newNotifPipeline: Lazy<NotifPipelineInitializer>,
    private val notifBindPipelineInitializer: NotifBindPipelineInitializer,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val notificationRowBinder: NotificationRowBinderImpl,
    private val remoteInputUriController: RemoteInputUriController,
    private val groupManagerLegacy: Lazy<NotificationGroupManagerLegacy>,
    private val groupAlertTransferHelper: NotificationGroupAlertTransferHelper,
    private val headsUpManager: HeadsUpManager,
    private val headsUpController: HeadsUpController,
    private val headsUpViewBinder: HeadsUpViewBinder,
    private val clickerBuilder: NotificationClicker.Builder,
    private val animatedImageNotificationManager: AnimatedImageNotificationManager,
    private val peopleSpaceWidgetManager: PeopleSpaceWidgetManager
) : NotificationsController {

    override fun initialize(
        statusBar: StatusBar,
        bubblesOptional: Optional<Bubbles>,
        presenter: NotificationPresenter,
        listContainer: NotificationListContainer,
        notificationActivityStarter: NotificationActivityStarter,
        bindRowCallback: NotificationRowBinderImpl.BindRowCallback
    ) {
        notificationListener.registerAsSystemService()

        val listController =
                NotificationListController(
                        entryManager,
                        listContainer,
                        deviceProvisionedController)
        listController.bind()

        notificationRowBinder.setNotificationClicker(
                clickerBuilder.build(
                        Optional.of(statusBar), bubblesOptional, notificationActivityStarter))
        notificationRowBinder.setUpWithPresenter(
                presenter,
                listContainer,
                bindRowCallback)
        headsUpViewBinder.setPresenter(presenter)
        notifBindPipelineInitializer.initialize()
        animatedImageNotificationManager.bind()

        if (featureFlags.isNewNotifPipelineEnabled) {
            newNotifPipeline.get().initialize(
                    notificationListener,
                    notificationRowBinder,
                    listContainer)
        }

        if (featureFlags.isNewNotifPipelineRenderingEnabled) {
            targetSdkResolver.initialize(notifPipeline.get())
            // TODO
        } else {
            targetSdkResolver.initialize(entryManager)
            remoteInputUriController.attach(entryManager)
            groupAlertTransferHelper.bind(entryManager, groupManagerLegacy.get())
            headsUpManager.addListener(groupManagerLegacy.get())
            headsUpManager.addListener(groupAlertTransferHelper)
            headsUpController.attach(entryManager, headsUpManager)
            groupManagerLegacy.get().setHeadsUpManager(headsUpManager)
            groupAlertTransferHelper.setHeadsUpManager(headsUpManager)

            entryManager.setRanker(legacyRanker)
            entryManager.attach(notificationListener)
        }

        peopleSpaceWidgetManager.attach(notificationListener)
    }

    override fun dump(
        fd: FileDescriptor,
        pw: PrintWriter,
        args: Array<String>,
        dumpTruck: Boolean
    ) {
        if (dumpTruck) {
            entryManager.dump(pw, "  ")
        }
    }

    // TODO: Convert all functions below this line into listeners instead of public methods

    override fun requestNotificationUpdate(reason: String) {
        entryManager.updateNotifications(reason)
    }

    override fun resetUserExpandedStates() {
        for (entry in entryManager.visibleNotifications) {
            entry.resetUserExpansion()
        }
    }

    override fun setNotificationSnoozed(sbn: StatusBarNotification, snoozeOption: SnoozeOption) {
        if (snoozeOption.snoozeCriterion != null) {
            notificationListener.snoozeNotification(sbn.key, snoozeOption.snoozeCriterion.id)
        } else {
            notificationListener.snoozeNotification(
                    sbn.key,
                    snoozeOption.minutesToSnoozeFor * 60 * 1000.toLong())
        }
    }

    override fun getActiveNotificationsCount(): Int {
        return entryManager.activeNotificationsCount
    }

    override fun setNotificationSnoozed(sbn: StatusBarNotification, hoursToSnooze: Int) {
        notificationListener.snoozeNotification(
                sbn.key,
                hoursToSnooze * 60 * 60 * 1000.toLong())
    }
}
