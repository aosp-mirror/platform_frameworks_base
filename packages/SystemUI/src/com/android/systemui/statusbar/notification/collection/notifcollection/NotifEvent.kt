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

package com.android.systemui.statusbar.notification.collection.notifcollection

import android.app.NotificationChannel
import android.os.UserHandle
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.NamedListenerSet
import com.android.app.tracing.traceSection

/**
 * Set of classes that represent the various events that [NotifCollection] can dispatch to
 * [NotifCollectionListener]s.
 *
 * These events build up in a queue and are periodically emitted in chunks by the collection.
 */

sealed class NotifEvent(private val traceName: String) {
    fun dispatchTo(listeners: NamedListenerSet<NotifCollectionListener>) {
        traceSection(traceName) {
            listeners.forEachTraced(::dispatchToListener)
        }
    }

    abstract fun dispatchToListener(listener: NotifCollectionListener)
}

data class BindEntryEvent(
    val entry: NotificationEntry,
    val sbn: StatusBarNotification
) : NotifEvent("onEntryBind") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryBind(entry, sbn)
    }
}

data class InitEntryEvent(
    val entry: NotificationEntry
) : NotifEvent("onEntryInit") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryInit(entry)
    }
}

data class EntryAddedEvent(
    val entry: NotificationEntry
) : NotifEvent("onEntryAdded") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryAdded(entry)
    }
}

data class EntryUpdatedEvent(
    val entry: NotificationEntry,
    val fromSystem: Boolean
) : NotifEvent(if (fromSystem) "onEntryUpdated" else "onEntryUpdated fromSystem=true") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryUpdated(entry, fromSystem)
    }
}

data class EntryRemovedEvent(
    val entry: NotificationEntry,
    val reason: Int
) : NotifEvent("onEntryRemoved ${cancellationReasonDebugString(reason)}") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryRemoved(entry, reason)
    }
}

data class CleanUpEntryEvent(
    val entry: NotificationEntry
) : NotifEvent("onEntryCleanUp") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onEntryCleanUp(entry)
    }
}

data class RankingUpdatedEvent(
    val rankingMap: RankingMap
) : NotifEvent("onRankingUpdate") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onRankingUpdate(rankingMap)
    }
}

class RankingAppliedEvent : NotifEvent("onRankingApplied") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onRankingApplied()
    }
}

data class ChannelChangedEvent(
    val pkgName: String,
    val user: UserHandle,
    val channel: NotificationChannel,
    val modificationType: Int
) : NotifEvent("onNotificationChannelModified") {
    override fun dispatchToListener(listener: NotifCollectionListener) {
        listener.onNotificationChannelModified(pkgName, user, channel, modificationType)
    }
}
