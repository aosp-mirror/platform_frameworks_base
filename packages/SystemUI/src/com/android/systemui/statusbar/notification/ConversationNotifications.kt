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
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import com.android.internal.widget.ConversationLayout
import com.android.internal.widget.MessagingImageMessage
import com.android.internal.widget.MessagingLayout
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.inflation.BindEventManager
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationContentView
import com.android.systemui.statusbar.notification.stack.StackStateAnimator
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener
import com.android.systemui.util.children
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

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
        entry.ranking.conversationShortcutInfo?.let { shortcutInfo ->
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
 * Tracks state related to animated images inside of notifications. Ex: starting and stopping
 * animations to conserve CPU and memory.
 */
@SysUISingleton
class AnimatedImageNotificationManager @Inject constructor(
    private val notifCollection: CommonNotifCollection,
    private val bindEventManager: BindEventManager,
    private val headsUpManager: HeadsUpManager,
    private val statusBarStateController: StatusBarStateController
) {

    private var isStatusBarExpanded = false

    /** Begins listening to state changes and updating animations accordingly. */
    fun bind() {
        headsUpManager.addListener(object : OnHeadsUpChangedListener {
            override fun onHeadsUpStateChanged(entry: NotificationEntry, isHeadsUp: Boolean) {
                updateAnimatedImageDrawables(entry)
            }
        })
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onExpandedChanged(isExpanded: Boolean) {
                isStatusBarExpanded = isExpanded
                notifCollection.allNotifs.forEach(::updateAnimatedImageDrawables)
            }
        })
        bindEventManager.addListener(::updateAnimatedImageDrawables)
    }

    private fun updateAnimatedImageDrawables(entry: NotificationEntry) =
        entry.row?.let { row ->
            updateAnimatedImageDrawables(row, animating = row.isHeadsUp || isStatusBarExpanded)
        }

    private fun updateAnimatedImageDrawables(row: ExpandableNotificationRow, animating: Boolean) =
            (row.layouts?.asSequence() ?: emptySequence())
                    .flatMap { layout -> layout.allViews.asSequence() }
                    .flatMap { view ->
                        (view as? ConversationLayout)?.messagingGroups?.asSequence()
                                ?: (view as? MessagingLayout)?.messagingGroups?.asSequence()
                                ?: emptySequence()
                    }
                    .flatMap { messagingGroup -> messagingGroup.messageContainer.children }
                    .mapNotNull { view ->
                        (view as? MessagingImageMessage)
                                ?.let { imageMessage ->
                                    imageMessage.drawable as? AnimatedImageDrawable
                                }
                    }
                    .forEach { animatedImageDrawable ->
                        if (animating) animatedImageDrawable.start()
                        else animatedImageDrawable.stop()
                    }
}

/**
 * Tracks state related to conversation notifications, and updates the UI of existing notifications
 * when necessary.
 * TODO(b/214083332) Refactor this class to use the right coordinators and controllers
 */
@SysUISingleton
class ConversationNotificationManager @Inject constructor(
    bindEventManager: BindEventManager,
    private val context: Context,
    private val notifCollection: CommonNotifCollection,
    @Main private val mainHandler: Handler
) {
    // Need this state to be thread safe, since it's accessed from the ui thread
    // (NotificationEntryListener) and a bg thread (NotificationContentInflater)
    private val states = ConcurrentHashMap<String, ConversationState>()

    private var notifPanelCollapsed = true

    private fun updateNotificationRanking(rankingMap: RankingMap) {
        fun getLayouts(view: NotificationContentView) =
                sequenceOf(view.contractedChild, view.expandedChild, view.headsUpChild)
        val ranking = Ranking()
        val activeConversationEntries = states.keys.asSequence()
                .mapNotNull { notifCollection.getEntry(it) }
        for (entry in activeConversationEntries) {
            if (rankingMap.getRanking(entry.sbn.key, ranking) && ranking.isConversation) {
                val important = ranking.channel.isImportantConversation
                var changed = false
                entry.row?.layouts?.asSequence()
                        ?.flatMap(::getLayouts)
                        ?.mapNotNull { it as? ConversationLayout }
                        ?.filterNot { it.isImportantConversation == important }
                        ?.forEach { layout ->
                            changed = true
                            if (important && entry.isMarkedForUserTriggeredMovement) {
                                // delay this so that it doesn't animate in until after
                                // the notif has been moved in the shade
                                mainHandler.postDelayed(
                                        {
                                            layout.setIsImportantConversation(
                                                    important,
                                                    true)
                                        },
                                        IMPORTANCE_ANIMATION_DELAY.toLong())
                            } else {
                                layout.setIsImportantConversation(important, false)
                            }
                        }
            }
        }
    }

    fun onEntryViewBound(entry: NotificationEntry) {
        if (!entry.ranking.isConversation) {
            return
        }
        fun updateCount(isExpanded: Boolean) {
            if (isExpanded && (!notifPanelCollapsed || entry.isPinnedAndExpanded)) {
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

    init {
        notifCollection.addCollectionListener(object : NotifCollectionListener {
            override fun onRankingUpdate(ranking: RankingMap) =
                updateNotificationRanking(ranking)

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) =
                removeTrackedEntry(entry)
        })
        bindEventManager.addListener(::onEntryViewBound)
    }

    private fun ConversationState.shouldIncrementUnread(newBuilder: Notification.Builder) =
            if (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) {
                false
            } else {
                val oldBuilder = Notification.Builder.recoverBuilder(context, notification)
                Notification.areStyledNotificationsVisiblyDifferent(oldBuilder, newBuilder)
            }

    fun getUnreadCount(entry: NotificationEntry, recoveredBuilder: Notification.Builder): Int =
            states.compute(entry.key) { _, state ->
                val newCount = state?.run {
                    if (shouldIncrementUnread(recoveredBuilder)) unreadCount + 1 else unreadCount
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
                    notifCollection.getEntry(key)?.let { entry ->
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
                    .flatMap { layout -> layout.allViews.asSequence() }
                    .mapNotNull { view -> view as? ConversationLayout }
                    .forEach { convoLayout -> convoLayout.setUnreadCount(0) }

    private data class ConversationState(val unreadCount: Int, val notification: Notification)

    companion object {
        private const val IMPORTANCE_ANIMATION_DELAY =
                StackStateAnimator.ANIMATION_DURATION_STANDARD +
                StackStateAnimator.ANIMATION_DURATION_PRIORITY_CHANGE +
                100
    }
}
