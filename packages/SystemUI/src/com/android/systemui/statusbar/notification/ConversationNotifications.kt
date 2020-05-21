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

package com.android.systemui.statusbar.notification

import android.app.Notification
import android.content.Context
import android.content.pm.LauncherApps
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import com.android.internal.statusbar.NotificationVisibility
import com.android.internal.widget.ConversationLayout
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationContentView
import com.android.systemui.statusbar.phone.NotificationGroupManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Populates additional information in conversation notifications */
class ConversationNotificationProcessor @Inject constructor(
    private val launcherApps: LauncherApps,
    private val conversationNotificationManager: ConversationNotificationManager
) {
    fun processNotification(entry: NotificationEntry, recoveredBuilder: Notification.Builder) {
        val messagingStyle = recoveredBuilder.style as? Notification.MessagingStyle ?: return
        messagingStyle.conversationType =
                if (entry.ranking.channel.isImportantConversation)
                    Notification.MessagingStyle.CONVERSATION_TYPE_IMPORTANT
                else
                    Notification.MessagingStyle.CONVERSATION_TYPE_NORMAL
        entry.ranking.shortcutInfo?.let { shortcutInfo ->
            messagingStyle.shortcutIcon = launcherApps.getShortcutIcon(shortcutInfo)
            shortcutInfo.label?.let { label ->
                messagingStyle.conversationTitle = label
            }
        }
        messagingStyle.unreadMessageCount =
                conversationNotificationManager.getUnreadCount(entry, recoveredBuilder)
    }
}

/**
 * Tracks state related to conversation notifications, and updates the UI of existing notifications
 * when necessary.
 */
@Singleton
class ConversationNotificationManager @Inject constructor(
    private val notificationEntryManager: NotificationEntryManager,
    private val notificationGroupManager: NotificationGroupManager,
    private val context: Context
) {
    // Need this state to be thread safe, since it's accessed from the ui thread
    // (NotificationEntryListener) and a bg thread (NotificationContentInflater)
    private val states = ConcurrentHashMap<String, ConversationState>()

    private var notifPanelCollapsed = true

    init {
        notificationEntryManager.addNotificationEntryListener(object : NotificationEntryListener {

            override fun onNotificationRankingUpdated(rankingMap: RankingMap) {
                fun getLayouts(view: NotificationContentView) =
                        sequenceOf(view.contractedChild, view.expandedChild, view.headsUpChild)
                val ranking = Ranking()
                states.keys.asSequence()
                        .mapNotNull { notificationEntryManager.getActiveNotificationUnfiltered(it) }
                        .forEach { entry ->
                            if (rankingMap.getRanking(entry.sbn.key, ranking) &&
                                    ranking.isConversation) {
                                val important = ranking.channel.isImportantConversation
                                var changed = false
                                entry.row?.layouts?.asSequence()
                                        ?.flatMap(::getLayouts)
                                        ?.mapNotNull { it as? ConversationLayout }
                                        ?.forEach {
                                            if (important != it.isImportantConversation) {
                                                it.setIsImportantConversation(important)
                                                changed = true
                                            }
                                        }
                                if (changed) {
                                    notificationGroupManager.updateIsolation(entry)
                                }
                            }
                        }
            }

            override fun onEntryInflated(entry: NotificationEntry) {
                if (!entry.ranking.isConversation) return
                fun updateCount(isExpanded: Boolean) {
                    if (isExpanded && (!notifPanelCollapsed || entry.isPinnedAndExpanded())) {
                        resetCount(entry.key)
                        entry.row?.let(::resetBadgeUi)
                    }
                }
                entry.row?.setOnExpansionChangedListener { isExpanded ->
                    if (entry.row?.isShown == true && isExpanded) {
                        entry.row.performOnIntrinsicHeightReached {
                            updateCount(isExpanded)
                        }
                    } else {
                        updateCount(isExpanded)
                    }
                }
                updateCount(entry.row?.isExpanded == true)
            }

            override fun onEntryReinflated(entry: NotificationEntry) = onEntryInflated(entry)

            override fun onEntryRemoved(
                entry: NotificationEntry,
                visibility: NotificationVisibility?,
                removedByUser: Boolean,
                reason: Int
            ) = removeTrackedEntry(entry)
        })
    }

    fun getUnreadCount(entry: NotificationEntry, recoveredBuilder: Notification.Builder): Int =
            states.compute(entry.key) { _, state ->
                val newCount = state?.run {
                    val old = Notification.Builder.recoverBuilder(context, notification)
                    val increment = Notification
                            .areStyledNotificationsVisiblyDifferent(old, recoveredBuilder)
                    if (increment) unreadCount + 1 else unreadCount
                } ?: 1
                ConversationState(newCount, entry.sbn.notification)
            }!!.unreadCount

    fun onNotificationPanelExpandStateChanged(isCollapsed: Boolean) {
        notifPanelCollapsed = isCollapsed
        if (isCollapsed) return

        // When the notification panel is expanded, reset the counters of any expanded
        // conversations
        val expanded = states
                .asSequence()
                .mapNotNull { (key, _) ->
                    notificationEntryManager.getActiveNotificationUnfiltered(key)
                            ?.let { entry ->
                                if (entry.row?.isExpanded == true) key to entry
                                else null
                            }
                }
                .toMap()
        states.replaceAll { key, state ->
            if (expanded.contains(key)) state.copy(unreadCount = 0)
            else state
        }
        // Update UI separate from the replaceAll call, since ConcurrentHashMap may re-run the
        // lambda if threads are in contention.
        expanded.values.asSequence().mapNotNull { it.row }.forEach(::resetBadgeUi)
    }

    private fun resetCount(key: String) {
        states.compute(key) { _, state -> state?.copy(unreadCount = 0) }
    }

    private fun removeTrackedEntry(entry: NotificationEntry) {
        states.remove(entry.key)
    }

    private fun resetBadgeUi(row: ExpandableNotificationRow): Unit =
            (row.layouts?.asSequence() ?: emptySequence())
                    .flatMap { layout -> layout.allViews.asSequence()}
                    .mapNotNull { view -> view as? ConversationLayout }
                    .forEach { convoLayout -> convoLayout.setUnreadCount(0) }

    private data class ConversationState(val unreadCount: Int, val notification: Notification)
}
