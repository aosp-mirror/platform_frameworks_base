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

import android.graphics.drawable.Icon
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import javax.inject.Inject
import kotlinx.coroutines.flow.update

private typealias ModelStore = Map<String, ActiveNotificationModel>

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
     * Sets the current list of rendered notification entries as displayed in the notification
     * stack.
     *
     * @see com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository.activeNotifications
     */
    fun setRenderedList(entries: List<ListEntry>) {
        repository.activeNotifications.update { existingModels ->
            entries.associateBy(
                keySelector = { it.key },
                valueTransform = { it.toModel(existingModels) },
            )
        }
    }

    private fun ListEntry.toModel(
        existingModels: ModelStore,
    ): ActiveNotificationModel =
        existingModels.createOrReuse(
            key = key,
            groupKey = representativeEntry?.sbn?.groupKey,
            isAmbient = sectionStyleProvider.isMinimized(this),
            isRowDismissed = representativeEntry?.isRowDismissed == true,
            isSilent = sectionStyleProvider.isSilent(this),
            isLastMessageFromReply = representativeEntry?.isLastMessageFromReply == true,
            isSuppressedFromStatusBar = representativeEntry?.shouldSuppressStatusBar() == true,
            isPulsing = representativeEntry?.showingPulsing() == true,
            aodIcon = representativeEntry?.icons?.aodIcon?.sourceIcon,
            shelfIcon = representativeEntry?.icons?.shelfIcon?.sourceIcon,
            statusBarIcon = representativeEntry?.icons?.statusBarIcon?.sourceIcon,
        )

    private fun ModelStore.createOrReuse(
        key: String,
        groupKey: String?,
        isAmbient: Boolean,
        isRowDismissed: Boolean,
        isSilent: Boolean,
        isLastMessageFromReply: Boolean,
        isSuppressedFromStatusBar: Boolean,
        isPulsing: Boolean,
        aodIcon: Icon?,
        shelfIcon: Icon?,
        statusBarIcon: Icon?
    ): ActiveNotificationModel {
        return this[key]?.takeIf {
            it.isCurrent(
                key = key,
                groupKey = groupKey,
                isAmbient = isAmbient,
                isRowDismissed = isRowDismissed,
                isSilent = isSilent,
                isLastMessageFromReply = isLastMessageFromReply,
                isSuppressedFromStatusBar = isSuppressedFromStatusBar,
                isPulsing = isPulsing,
                aodIcon = aodIcon,
                shelfIcon = shelfIcon,
                statusBarIcon = statusBarIcon
            )
        }
            ?: ActiveNotificationModel(
                key = key,
                groupKey = groupKey,
                isAmbient = isAmbient,
                isRowDismissed = isRowDismissed,
                isSilent = isSilent,
                isLastMessageFromReply = isLastMessageFromReply,
                isSuppressedFromStatusBar = isSuppressedFromStatusBar,
                isPulsing = isPulsing,
                aodIcon = aodIcon,
                shelfIcon = shelfIcon,
                statusBarIcon = statusBarIcon,
            )
    }

    private fun ActiveNotificationModel.isCurrent(
        key: String,
        groupKey: String?,
        isAmbient: Boolean,
        isRowDismissed: Boolean,
        isSilent: Boolean,
        isLastMessageFromReply: Boolean,
        isSuppressedFromStatusBar: Boolean,
        isPulsing: Boolean,
        aodIcon: Icon?,
        shelfIcon: Icon?,
        statusBarIcon: Icon?
    ): Boolean {
        return when {
            key != this.key -> false
            groupKey != this.groupKey -> false
            isAmbient != this.isAmbient -> false
            isRowDismissed != this.isRowDismissed -> false
            isSilent != this.isSilent -> false
            isLastMessageFromReply != this.isLastMessageFromReply -> false
            isSuppressedFromStatusBar != this.isSuppressedFromStatusBar -> false
            isPulsing != this.isPulsing -> false
            aodIcon != this.aodIcon -> false
            shelfIcon != this.shelfIcon -> false
            statusBarIcon != this.statusBarIcon -> false
            else -> true
        }
    }
}
