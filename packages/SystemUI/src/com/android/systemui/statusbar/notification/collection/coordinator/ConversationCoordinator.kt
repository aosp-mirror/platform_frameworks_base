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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.PeopleHeader
import com.android.systemui.statusbar.notification.icon.ConversationIconManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.PeopleNotificationType
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.stack.BUCKET_PEOPLE
import javax.inject.Inject

/**
 * A Conversation/People Coordinator that:
 * - Elevates important conversation notifications
 * - Puts conversations into its own people section. @see [NotifCoordinators] for section ordering.
 */
@CoordinatorScope
class ConversationCoordinator @Inject constructor(
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier,
    private val conversationIconManager: ConversationIconManager,
    @PeopleHeader peopleHeaderController: NodeController
) : Coordinator {

    private val promotedEntriesToSummaryOfSameChannel =
        mutableMapOf<NotificationEntry, NotificationEntry>()

    private val onBeforeRenderListListener = OnBeforeRenderListListener { _ ->
        val unimportantSummaries = promotedEntriesToSummaryOfSameChannel
            .mapNotNull { (promoted, summary) ->
                val originalGroup = summary.parent
                when {
                    originalGroup == null -> null
                    originalGroup == promoted.parent -> null
                    originalGroup.parent == null -> null
                    originalGroup.summary != summary -> null
                    originalGroup.children.any { it.channel == summary.channel } -> null
                    else -> summary.key
                }
            }
        conversationIconManager.setUnimportantConversations(unimportantSummaries)
        promotedEntriesToSummaryOfSameChannel.clear()
    }

    private val notificationPromoter = object : NotifPromoter(TAG) {
        override fun shouldPromoteToTopLevel(entry: NotificationEntry): Boolean {
            val shouldPromote = entry.channel?.isImportantConversation == true
            if (shouldPromote) {
                val summary = entry.parent?.summary
                if (summary != null && entry.channel == summary.channel) {
                    promotedEntriesToSummaryOfSameChannel[entry] = summary
                }
            }
            return shouldPromote
        }
    }

    val sectioner = object : NotifSectioner("People", BUCKET_PEOPLE) {
        override fun isInSection(entry: ListEntry): Boolean =
                isConversation(entry)

        override fun getComparator() = object : NotifComparator("People") {
            override fun compare(entry1: ListEntry, entry2: ListEntry): Int {
                val type1 = getPeopleType(entry1)
                val type2 = getPeopleType(entry2)
                return type2.compareTo(type1)
            }
        }

        override fun getHeaderNodeController() =
                // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and peopleHeaderController
                if (RankingCoordinator.SHOW_ALL_SECTIONS) peopleHeaderController else null
    }

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPromoter(notificationPromoter)
        pipeline.addOnBeforeRenderListListener(onBeforeRenderListListener)
    }

    private fun isConversation(entry: ListEntry): Boolean =
        getPeopleType(entry) != TYPE_NON_PERSON

    @PeopleNotificationType
    private fun getPeopleType(entry: ListEntry): Int =
        entry.representativeEntry?.let {
            peopleNotificationIdentifier.getPeopleNotificationType(it)
        } ?: TYPE_NON_PERSON

    companion object {
        private const val TAG = "ConversationCoordinator"
    }
}
