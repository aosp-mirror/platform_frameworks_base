/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.domain.interactor

import android.app.Notification.CallStyle.CALL_TYPE_INCOMING
import android.app.Notification.CallStyle.CALL_TYPE_ONGOING
import android.app.Notification.CallStyle.CALL_TYPE_SCREENING
import android.app.Notification.CallStyle.CALL_TYPE_UNKNOWN
import android.app.Notification.EXTRA_CALL_TYPE
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.ArrayMap
import com.android.app.tracing.traceSection
import com.android.systemui.Flags
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.shared.ActiveNotificationEntryModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import javax.inject.Inject
import kotlinx.coroutines.flow.update

/**
 * Logic for passing information from the
 * [com.android.systemui.statusbar.notification.collection.NotifPipeline] to the presentation
 * layers.
 */
class RenderNotificationListInteractor
@Inject
constructor(
    private val repository: ActiveNotificationListRepository,
    private val sectionStyleProvider: SectionStyleProvider,
) {
    /**
     * Sets the current list of rendered notification entries as displayed in the notification list.
     */
    fun setRenderedList(entries: List<ListEntry>) {
        traceSection("RenderNotificationListInteractor.setRenderedList") {
            repository.activeNotifications.update { existingModels ->
                buildActiveNotificationsStore(existingModels, sectionStyleProvider) {
                    entries.forEach(::addListEntry)
                    setRankingsMap(entries)
                }
            }
        }
    }
}

private fun buildActiveNotificationsStore(
    existingModels: ActiveNotificationsStore,
    sectionStyleProvider: SectionStyleProvider,
    block: ActiveNotificationsStoreBuilder.() -> Unit
): ActiveNotificationsStore =
    ActiveNotificationsStoreBuilder(existingModels, sectionStyleProvider).apply(block).build()

private class ActiveNotificationsStoreBuilder(
    private val existingModels: ActiveNotificationsStore,
    private val sectionStyleProvider: SectionStyleProvider,
) {
    private val builder = ActiveNotificationsStore.Builder()

    fun build(): ActiveNotificationsStore = builder.build()

    /**
     * Convert a [ListEntry] into [ActiveNotificationEntryModel]s, and add them to the
     * [ActiveNotificationsStore]. Special care is taken to avoid re-allocating models if the result
     * would be identical to an existing model (at the expense of additional computations).
     */
    fun addListEntry(entry: ListEntry) {
        when (entry) {
            is GroupEntry -> {
                entry.summary?.let { summary ->
                    val summaryModel = summary.toModel()
                    val childModels = entry.children.map { it.toModel() }
                    builder.addNotifGroup(
                        existingModels.createOrReuse(
                            key = entry.key,
                            summary = summaryModel,
                            children = childModels
                        )
                    )
                }
            }
            else -> {
                entry.representativeEntry?.let { notifEntry ->
                    builder.addIndividualNotif(notifEntry.toModel())
                }
            }
        }
    }

    fun setRankingsMap(entries: List<ListEntry>) {
        builder.setRankingsMap(flatMapToRankingsMap(entries))
    }

    fun flatMapToRankingsMap(entries: List<ListEntry>): Map<String, Int> {
        val result = ArrayMap<String, Int>()
        for (entry in entries) {
            if (entry is NotificationEntry) {
                entry.representativeEntry?.let { representativeEntry ->
                    result[representativeEntry.key] = representativeEntry.ranking.rank
                }
            } else if (entry is GroupEntry) {
                entry.summary?.let { summary -> result[summary.key] = summary.ranking.rank }
                for (child in entry.children) {
                    result[child.key] = child.ranking.rank
                }
            }
        }
        return result
    }

    private fun NotificationEntry.toModel(): ActiveNotificationModel {
        val statusBarChipIcon =
            if (Flags.statusBarCallChipNotificationIcon()) {
                icons.statusBarChipIcon
            } else {
                null
            }
        return existingModels.createOrReuse(
            key = key,
            groupKey = sbn.groupKey,
            whenTime = sbn.notification.`when`,
            isAmbient = sectionStyleProvider.isMinimized(this),
            isRowDismissed = isRowDismissed,
            isSilent = sectionStyleProvider.isSilent(this),
            isLastMessageFromReply = isLastMessageFromReply,
            isSuppressedFromStatusBar = shouldSuppressStatusBar(),
            isPulsing = showingPulsing(),
            aodIcon = icons.aodIcon?.sourceIcon,
            shelfIcon = icons.shelfIcon?.sourceIcon,
            statusBarIcon = icons.statusBarIcon?.sourceIcon,
            statusBarChipIconView = statusBarChipIcon,
            uid = sbn.uid,
            packageName = sbn.packageName,
            contentIntent = sbn.notification.contentIntent,
            instanceId = sbn.instanceId?.id,
            isGroupSummary = sbn.notification.isGroupSummary,
            bucket = bucket,
            callType = sbn.toCallType(),
        )
    }
}

private fun ActiveNotificationsStore.createOrReuse(
    key: String,
    groupKey: String?,
    whenTime: Long,
    isAmbient: Boolean,
    isRowDismissed: Boolean,
    isSilent: Boolean,
    isLastMessageFromReply: Boolean,
    isSuppressedFromStatusBar: Boolean,
    isPulsing: Boolean,
    aodIcon: Icon?,
    shelfIcon: Icon?,
    statusBarIcon: Icon?,
    statusBarChipIconView: StatusBarIconView?,
    uid: Int,
    packageName: String,
    contentIntent: PendingIntent?,
    instanceId: Int?,
    isGroupSummary: Boolean,
    bucket: Int,
    callType: CallType,
): ActiveNotificationModel {
    return individuals[key]?.takeIf {
        it.isCurrent(
            key = key,
            groupKey = groupKey,
            whenTime = whenTime,
            isAmbient = isAmbient,
            isRowDismissed = isRowDismissed,
            isSilent = isSilent,
            isLastMessageFromReply = isLastMessageFromReply,
            isSuppressedFromStatusBar = isSuppressedFromStatusBar,
            isPulsing = isPulsing,
            aodIcon = aodIcon,
            shelfIcon = shelfIcon,
            statusBarIcon = statusBarIcon,
            statusBarChipIconView = statusBarChipIconView,
            uid = uid,
            instanceId = instanceId,
            isGroupSummary = isGroupSummary,
            packageName = packageName,
            contentIntent = contentIntent,
            bucket = bucket,
            callType = callType,
        )
    }
        ?: ActiveNotificationModel(
            key = key,
            groupKey = groupKey,
            whenTime = whenTime,
            isAmbient = isAmbient,
            isRowDismissed = isRowDismissed,
            isSilent = isSilent,
            isLastMessageFromReply = isLastMessageFromReply,
            isSuppressedFromStatusBar = isSuppressedFromStatusBar,
            isPulsing = isPulsing,
            aodIcon = aodIcon,
            shelfIcon = shelfIcon,
            statusBarIcon = statusBarIcon,
            statusBarChipIconView = statusBarChipIconView,
            uid = uid,
            instanceId = instanceId,
            isGroupSummary = isGroupSummary,
            packageName = packageName,
            contentIntent = contentIntent,
            bucket = bucket,
            callType = callType,
        )
}

private fun ActiveNotificationModel.isCurrent(
    key: String,
    groupKey: String?,
    whenTime: Long,
    isAmbient: Boolean,
    isRowDismissed: Boolean,
    isSilent: Boolean,
    isLastMessageFromReply: Boolean,
    isSuppressedFromStatusBar: Boolean,
    isPulsing: Boolean,
    aodIcon: Icon?,
    shelfIcon: Icon?,
    statusBarIcon: Icon?,
    statusBarChipIconView: StatusBarIconView?,
    uid: Int,
    packageName: String,
    contentIntent: PendingIntent?,
    instanceId: Int?,
    isGroupSummary: Boolean,
    bucket: Int,
    callType: CallType,
): Boolean {
    return when {
        key != this.key -> false
        groupKey != this.groupKey -> false
        whenTime != this.whenTime -> false
        isAmbient != this.isAmbient -> false
        isRowDismissed != this.isRowDismissed -> false
        isSilent != this.isSilent -> false
        isLastMessageFromReply != this.isLastMessageFromReply -> false
        isSuppressedFromStatusBar != this.isSuppressedFromStatusBar -> false
        isPulsing != this.isPulsing -> false
        aodIcon != this.aodIcon -> false
        shelfIcon != this.shelfIcon -> false
        statusBarIcon != this.statusBarIcon -> false
        statusBarChipIconView != this.statusBarChipIconView -> false
        uid != this.uid -> false
        instanceId != this.instanceId -> false
        isGroupSummary != this.isGroupSummary -> false
        packageName != this.packageName -> false
        contentIntent != this.contentIntent -> false
        bucket != this.bucket -> false
        callType != this.callType -> false
        else -> true
    }
}

private fun ActiveNotificationsStore.createOrReuse(
    key: String,
    summary: ActiveNotificationModel,
    children: List<ActiveNotificationModel>,
): ActiveNotificationGroupModel {
    return groups[key]?.takeIf { it.isCurrent(key, summary, children) }
        ?: ActiveNotificationGroupModel(key, summary, children)
}

private fun ActiveNotificationGroupModel.isCurrent(
    key: String,
    summary: ActiveNotificationModel,
    children: List<ActiveNotificationModel>,
): Boolean {
    return when {
        key != this.key -> false
        summary != this.summary -> false
        children != this.children -> false
        else -> true
    }
}

private fun StatusBarNotification.toCallType(): CallType =
    when (this.notification.extras.getInt(EXTRA_CALL_TYPE, -1)) {
        -1 -> CallType.None
        CALL_TYPE_INCOMING -> CallType.Incoming
        CALL_TYPE_ONGOING -> CallType.Ongoing
        CALL_TYPE_SCREENING -> CallType.Screening
        CALL_TYPE_UNKNOWN -> CallType.Unknown
        else -> CallType.Unknown
    }
