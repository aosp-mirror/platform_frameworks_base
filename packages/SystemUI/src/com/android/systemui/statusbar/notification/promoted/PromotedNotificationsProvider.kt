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

package com.android.systemui.statusbar.notification.promoted

import android.app.Notification.FLAG_PROMOTED_ONGOING
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import javax.inject.Inject

/** A provider for making decisions on which notifications should be promoted. */
interface PromotedNotificationsProvider {
    /** Returns true if the given notification should be promoted and false otherwise. */
    fun shouldPromote(entry: NotificationEntry): Boolean
}

@SysUISingleton
open class PromotedNotificationsProviderImpl @Inject constructor() : PromotedNotificationsProvider {
    override fun shouldPromote(entry: NotificationEntry): Boolean {
        if (!PromotedNotificationContentModel.featureFlagEnabled()) {
            return false
        }
        return (entry.sbn.notification.flags and FLAG_PROMOTED_ONGOING) != 0
    }
}
