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
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.AnimatedImageNotificationManager
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.NotificationClicker
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.TargetSdkResolver
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl
import com.android.systemui.statusbar.notification.collection.init.NotifPipelineInitializer
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.render.NotifStackController
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import com.android.systemui.statusbar.notification.row.NotifBindPipelineInitializer
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.wm.shell.bubbles.Bubbles
import dagger.Lazy
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
class NotificationsControllerImpl
@Inject
constructor(
    private val notificationListener: NotificationListener,
    private val commonNotifCollection: Lazy<CommonNotifCollection>,
    private val notifPipeline: Lazy<NotifPipeline>,
    private val notifLiveDataStore: NotifLiveDataStore,
    private val targetSdkResolver: TargetSdkResolver,
    private val notifPipelineInitializer: Lazy<NotifPipelineInitializer>,
    private val notifBindPipelineInitializer: NotifBindPipelineInitializer,
    private val notificationLoggerOptional: Optional<NotificationLogger>,
    private val notificationRowBinder: NotificationRowBinderImpl,
    private val notificationsMediaManager: NotificationMediaManager,
    private val headsUpViewBinder: HeadsUpViewBinder,
    private val clickerBuilder: NotificationClicker.Builder,
    private val animatedImageNotificationManager: AnimatedImageNotificationManager,
    private val peopleSpaceWidgetManager: PeopleSpaceWidgetManager,
    private val bubblesOptional: Optional<Bubbles>,
) : NotificationsController {

    override fun initialize(
        presenter: NotificationPresenter,
        listContainer: NotificationListContainer,
        stackController: NotifStackController,
        notificationActivityStarter: NotificationActivityStarter,
    ) {
        notificationListener.registerAsSystemService()

        notifPipeline
            .get()
            .addCollectionListener(
                object : NotifCollectionListener {
                    override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                        listContainer.cleanUpViewStateForEntry(entry)
                    }
                }
            )

        notificationRowBinder.setNotificationClicker(
            clickerBuilder.build(bubblesOptional, notificationActivityStarter)
        )
        notificationRowBinder.setUpWithPresenter(presenter, listContainer)
        headsUpViewBinder.setPresenter(presenter)
        notifBindPipelineInitializer.initialize()
        animatedImageNotificationManager.bind()

        notifPipelineInitializer
            .get()
            .initialize(notificationListener, notificationRowBinder, listContainer, stackController)

        targetSdkResolver.initialize(notifPipeline.get())
        notificationsMediaManager.setUpWithPresenter(presenter)
        if (!NotificationsLiveDataStoreRefactor.isEnabled) {
            notificationLoggerOptional.ifPresent { logger ->
                logger.setUpWithContainer(listContainer)
            }
        }
        peopleSpaceWidgetManager.attach(notificationListener)
    }

    // TODO: Convert all functions below this line into listeners instead of public methods

    override fun resetUserExpandedStates() {
        // TODO: this is a view thing that should be done through the views, but that means doing it
        //  both when this event is fired and any time a row is attached.
        for (entry in commonNotifCollection.get().allNotifs) {
            entry.resetUserExpansion()
        }
    }

    override fun setNotificationSnoozed(sbn: StatusBarNotification, snoozeOption: SnoozeOption) {
        if (snoozeOption.snoozeCriterion != null) {
            notificationListener.snoozeNotification(sbn.key, snoozeOption.snoozeCriterion.id)
        } else {
            notificationListener.snoozeNotification(
                sbn.key,
                snoozeOption.minutesToSnoozeFor * 60 * 1000.toLong()
            )
        }
    }

    override fun getActiveNotificationsCount(): Int {
        NotificationsLiveDataStoreRefactor.assertInLegacyMode()
        return notifLiveDataStore.activeNotifCount.value
    }
}
