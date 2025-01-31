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

package com.android.systemui.statusbar.notification.promoted.ui.viewmodel

import android.graphics.drawable.Icon
import com.android.internal.widget.NotificationProgressModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.Style
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.When
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PromotedNotificationViewModel(
    identity: PromotedNotificationContentModel.Identity,
    content: Flow<PromotedNotificationContentModel>,
) {
    // for all styles:

    val key: String = identity.key
    val style: Style = identity.style

    val skeletonSmallIcon: Flow<Icon?> = content.map { it.skeletonSmallIcon }
    val appName: Flow<CharSequence?> = content.map { it.appName }
    val subText: Flow<CharSequence?> = content.map { it.subText }

    private val time: Flow<When?> = content.map { it.time }
    val whenTime: Flow<Long?> = time.map { it?.time }
    val whenMode: Flow<When.Mode?> = time.map { it?.mode }

    val lastAudiblyAlertedMs: Flow<Long> = content.map { it.lastAudiblyAlertedMs }
    val profileBadgeResId: Flow<Int?> = content.map { it.profileBadgeResId }
    val title: Flow<CharSequence?> = content.map { it.title }
    val text: Flow<CharSequence?> = content.map { it.text }
    val skeletonLargeIcon: Flow<Icon?> = content.map { it.skeletonLargeIcon }

    // for CallStyle:
    val personIcon: Flow<Icon?> = content.map { it.personIcon }
    val personName: Flow<CharSequence?> = content.map { it.personName }
    val verificationIcon: Flow<Icon?> = content.map { it.verificationIcon }
    val verificationText: Flow<CharSequence?> = content.map { it.verificationText }

    // for ProgressStyle:
    val progress: Flow<NotificationProgressModel?> = content.map { it.newProgress }
}
