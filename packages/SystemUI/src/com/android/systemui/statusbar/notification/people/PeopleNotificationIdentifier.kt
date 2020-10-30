/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.people

import android.annotation.IntDef
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.PeopleNotificationType
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_FULL_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_IMPORTANT_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import javax.inject.Inject
import kotlin.math.max

interface PeopleNotificationIdentifier {

    /**
     * Identifies if the given notification can be classified as a "People" notification.
     *
     * @return [TYPE_NON_PERSON] if not a people notification, [TYPE_PERSON] if it is a people
     *  notification that doesn't use shortcuts, [TYPE_FULL_PERSON] if it is a person notification
     *  that users shortcuts, and [TYPE_IMPORTANT_PERSON] if an "important" people notification
     *  that users shortcuts.
     */
    @PeopleNotificationType
    fun getPeopleNotificationType(entry: NotificationEntry): Int

    fun compareTo(
        @PeopleNotificationType a: Int,
        @PeopleNotificationType b: Int
    ): Int

    companion object {

        @Retention(AnnotationRetention.SOURCE)
        @IntDef(prefix = ["TYPE_"], value = [TYPE_NON_PERSON, TYPE_PERSON, TYPE_FULL_PERSON,
            TYPE_IMPORTANT_PERSON])
        annotation class PeopleNotificationType

        const val TYPE_NON_PERSON = 0
        const val TYPE_PERSON = 1
        const val TYPE_FULL_PERSON = 2
        const val TYPE_IMPORTANT_PERSON = 3
    }
}

@SysUISingleton
class PeopleNotificationIdentifierImpl @Inject constructor(
    private val personExtractor: NotificationPersonExtractor,
    private val groupManager: GroupMembershipManager
) : PeopleNotificationIdentifier {

    @PeopleNotificationType
    override fun getPeopleNotificationType(entry: NotificationEntry): Int =
            when (val type = entry.ranking.personTypeInfo) {
                TYPE_IMPORTANT_PERSON -> TYPE_IMPORTANT_PERSON
                else -> {
                    when (val type = upperBound(type, extractPersonTypeInfo(entry.sbn))) {
                        TYPE_IMPORTANT_PERSON -> TYPE_IMPORTANT_PERSON
                        else -> upperBound(type, getPeopleTypeOfSummary(entry))
                    }
                }
            }

    override fun compareTo(
        @PeopleNotificationType a: Int,
        @PeopleNotificationType b: Int
    ): Int
    {
        return b.compareTo(a)
    }

    /**
     * Given two [PeopleNotificationType]s, determine the upper bound. Used to constrain a
     * notification to a type given multiple signals, i.e. notification groups, where each child
     * has a [PeopleNotificationType] that is used to constrain the summary.
     */
    @PeopleNotificationType
    private fun upperBound(
        @PeopleNotificationType type: Int,
        @PeopleNotificationType other: Int
    ): Int =
            max(type, other)

    private val Ranking.personTypeInfo
        get() = when {
            !isConversation -> TYPE_NON_PERSON
            conversationShortcutInfo == null -> TYPE_PERSON
            channel?.isImportantConversation == true -> TYPE_IMPORTANT_PERSON
            else -> TYPE_FULL_PERSON
        }

    private fun extractPersonTypeInfo(sbn: StatusBarNotification) =
            if (personExtractor.isPersonNotification(sbn)) TYPE_PERSON else TYPE_NON_PERSON

    private fun getPeopleTypeOfSummary(entry: NotificationEntry): Int {
        if (!groupManager.isGroupSummary(entry)) {
            return TYPE_NON_PERSON
        }

        val childTypes = groupManager.getChildren(entry)
                ?.asSequence()
                ?.map { getPeopleNotificationType(it) }
                ?: return TYPE_NON_PERSON

        var groupType = TYPE_NON_PERSON
        for (childType in childTypes) {
            groupType = upperBound(groupType, childType)
            if (groupType == TYPE_IMPORTANT_PERSON)
                break
        }
        return groupType
    }
}
