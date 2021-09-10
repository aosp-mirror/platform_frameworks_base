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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.PeopleHeader
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import javax.inject.Inject

/**
 * A Conversation/People Coordinator that:
 * - Elevates important conversation notifications
 * - Puts conversations into its own people section. @see [NotifCoordinators] for section ordering.
 */
@SysUISingleton
class ConversationCoordinator @Inject constructor(
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier,
    @PeopleHeader peopleHeaderController: NodeController
) : Coordinator {

    private val notificationPromoter = object : NotifPromoter(TAG) {
        override fun shouldPromoteToTopLevel(entry: NotificationEntry): Boolean {
            return entry.channel?.isImportantConversation == true
        }
    }

    val sectioner = object : NotifSectioner("People") {
        override fun isInSection(entry: ListEntry): Boolean =
                isConversation(entry.representativeEntry!!)
        override fun getHeaderNodeController() =
                // TODO: remove SHOW_ALL_SECTIONS, this redundant method, and peopleHeaderController
                if (RankingCoordinator.SHOW_ALL_SECTIONS) peopleHeaderController else null
    }

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPromoter(notificationPromoter)
    }

    private fun isConversation(entry: NotificationEntry): Boolean =
        peopleNotificationIdentifier.getPeopleNotificationType(entry) != TYPE_NON_PERSON

    companion object {
        private const val TAG = "ConversationCoordinator"
    }
}
