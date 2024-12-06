/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.chips.notification.domain.interactor

import com.android.systemui.activity.data.repository.ActivityManagerRepository
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.notification.domain.model.NotificationChipModel
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Interactor representing a single notification's status bar chip.
 *
 * [startingModel.key] dictates which notification this interactor corresponds to - all updates sent
 * to this interactor via [setNotification] should only be for the notification with the same key.
 *
 * [StatusBarNotificationChipsInteractor] will collect all the individual instances of this
 * interactor and send all the necessary information to the UI layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleNotificationChipInteractor
@AssistedInject
constructor(
    @Assisted startingModel: ActiveNotificationModel,
    private val activityManagerRepository: ActivityManagerRepository,
    @StatusBarChipsLog private val logBuffer: LogBuffer,
) {
    private val key = startingModel.key
    private val logger = Logger(logBuffer, "Notif".pad())
    // [StatusBarChipLogTag] recommends a max tag length of 20, so [extraLogTag] should NOT be the
    // top-level tag. It should instead be provided as the first string in each log message.
    private val extraLogTag = "SingleChipInteractor[key=$key]"

    private val _notificationModel = MutableStateFlow(startingModel)

    /**
     * Sets the new notification info corresponding to this interactor. The key on [model] *must*
     * match the key on the original [startingModel], otherwise the update won't be processed.
     */
    fun setNotification(model: ActiveNotificationModel) {
        if (model.key != this.key) {
            logger.w({ "$str1: received model for different key $str2" }) {
                str1 = extraLogTag
                str2 = model.key
            }
            return
        }
        _notificationModel.value = model
    }

    private val uid: Flow<Int> = _notificationModel.map { it.uid }

    /** True if the application managing the notification is visible to the user. */
    private val isAppVisible: Flow<Boolean> =
        uid.flatMapLatest { currentUid ->
            activityManagerRepository.createIsAppVisibleFlow(currentUid, logger, extraLogTag)
        }

    /**
     * Emits this notification's status bar chip, or null if this notification shouldn't show a
     * status bar chip.
     */
    val notificationChip: Flow<NotificationChipModel?> =
        combine(_notificationModel, isAppVisible) { notif, isAppVisible ->
            if (isAppVisible) {
                // If the app that posted this notification is visible, we want to hide the chip
                // because information between the status bar chip and the app itself could be
                // out-of-sync (like a timer that's slightly off)
                null
            } else {
                notif.toNotificationChipModel()
            }
        }

    private fun ActiveNotificationModel.toNotificationChipModel(): NotificationChipModel? {
        val statusBarChipIconView = this.statusBarChipIconView
        if (statusBarChipIconView == null) {
            if (!StatusBarConnectedDisplays.isEnabled) {
                logger.w({ "$str1: Can't show chip because status bar chip icon view is null" }) {
                    str1 = extraLogTag
                }
                // When the flag is disabled, we keep the old behavior of returning null.
                // When the flag is enabled, the icon will always be null, and will later be
                // fetched in the UI layer using the notification key.
                return null
            }
        }
        return NotificationChipModel(key, statusBarChipIconView, whenTime)
    }

    @AssistedFactory
    fun interface Factory {
        fun create(startingModel: ActiveNotificationModel): SingleNotificationChipInteractor
    }
}
