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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.phone.NotifActivityLaunchEvents
import dagger.Binds
import dagger.Module
import javax.inject.Inject

/** Extends the lifetime of notifications while their activity launch animation is playing. */
interface ActivityLaunchAnimCoordinator : Coordinator

/** Provides an [ActivityLaunchAnimCoordinator] to [CoordinatorScope]. */
@Module(includes = [PrivateActivityStarterCoordinatorModule::class])
object ActivityLaunchAnimCoordinatorModule

@Module
private interface PrivateActivityStarterCoordinatorModule {
    @Binds
    fun bindCoordinator(impl: ActivityLaunchAnimCoordinatorImpl): ActivityLaunchAnimCoordinator
}

/**
 * Listens for [NotifActivityLaunchEvents], and then extends the lifetimes of any notifs while their
 * launch animation is playing.
 */
@CoordinatorScope
private class ActivityLaunchAnimCoordinatorImpl @Inject constructor(
    private val activityLaunchEvents: NotifActivityLaunchEvents
) : ActivityLaunchAnimCoordinator {
    // Tracks notification launches, and whether or not their lifetimes are extended.
    private val notifsLaunchingActivities = mutableMapOf<String, Boolean>()

    private var onEndLifetimeExtensionCallback: OnEndLifetimeExtensionCallback? = null

    override fun attach(pipeline: NotifPipeline) {
        activityLaunchEvents.registerListener(activityStartEventListener)
        pipeline.addNotificationLifetimeExtender(extender)
    }

    private val activityStartEventListener = object : NotifActivityLaunchEvents.Listener {
        override fun onStartLaunchNotifActivity(entry: NotificationEntry) {
            notifsLaunchingActivities[entry.key] = false
        }

        override fun onFinishLaunchNotifActivity(entry: NotificationEntry) {
            if (notifsLaunchingActivities.remove(entry.key) == true) {
                // If we were extending the lifetime of this notification, stop.
                onEndLifetimeExtensionCallback?.onEndLifetimeExtension(extender, entry)
            }
        }
    }

    private val extender = object : NotifLifetimeExtender {
        override fun getName(): String = "ActivityStarterCoordinator"

        override fun setCallback(callback: OnEndLifetimeExtensionCallback) {
            onEndLifetimeExtensionCallback = callback
        }

        override fun maybeExtendLifetime(entry: NotificationEntry, reason: Int): Boolean {
            if (entry.key in notifsLaunchingActivities) {
                // Track that we're now extending this notif
                notifsLaunchingActivities[entry.key] = true
                return true
            }
            return false
        }

        override fun cancelLifetimeExtension(entry: NotificationEntry) {
            if (entry.key in notifsLaunchingActivities) {
                notifsLaunchingActivities[entry.key] = false
            }
        }
    }
}
