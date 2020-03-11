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
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.PeopleNotificationType
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_IMPORTANT_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_NON_PERSON
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier.Companion.TYPE_PERSON
import com.android.systemui.statusbar.phone.NotificationGroupManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

interface PeopleNotificationIdentifier {

    /**
     * Identifies if the given notification can be classified as a "People" notification.
     *
     * @return [TYPE_NON_PERSON] if not a people notification, [TYPE_PERSON] if a standard people
     *  notification, and [TYPE_IMPORTANT_PERSON] if an "important" people notification.
     */
    @PeopleNotificationType
    fun getPeopleNotificationType(sbn: StatusBarNotification, ranking: Ranking): Int

    companion object {

        @Retention(AnnotationRetention.SOURCE)
        @IntDef(prefix = ["TYPE_"], value = [TYPE_NON_PERSON, TYPE_PERSON, TYPE_IMPORTANT_PERSON])
        annotation class PeopleNotificationType

        const val TYPE_NON_PERSON = 0
        const val TYPE_PERSON = 1
        const val TYPE_IMPORTANT_PERSON = 2
    }
}

@Singleton
class PeopleNotificationIdentifierImpl @Inject constructor(
    private val personExtractor: NotificationPersonExtractor,
    private val groupManager: NotificationGroupManager
) : PeopleNotificationIdentifier {

    @PeopleNotificationType
    override fun getPeopleNotificationType(sbn: StatusBarNotification, ranking: Ranking): Int =
            when (val type = ranking.personTypeInfo) {
                TYPE_IMPORTANT_PERSON -> TYPE_IMPORTANT_PERSON
                else -> {
                    when (val type = upperBound(type, extractPersonTypeInfo(sbn))) {
                        TYPE_IMPORTANT_PERSON -> TYPE_IMPORTANT_PERSON
                        else -> upperBound(type, getPeopleTypeOfSummary(sbn))
                    }
                }
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
            channel?.isImportantConversation == true -> TYPE_IMPORTANT_PERSON
            isConversation -> TYPE_PERSON
            else -> TYPE_NON_PERSON
        }

    private fun extractPersonTypeInfo(sbn: StatusBarNotification) =
            if (personExtractor.isPersonNotification(sbn)) TYPE_PERSON else TYPE_NON_PERSON

    private fun getPeopleTypeOfSummary(statusBarNotification: StatusBarNotification): Int {
        if (!groupManager.isSummaryOfGroup(statusBarNotification)) {
            return TYPE_NON_PERSON
        }

        val childTypes = groupManager.getLogicalChildren(statusBarNotification)
                ?.asSequence()
                ?.map { getPeopleNotificationType(it.sbn, it.ranking) }
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
