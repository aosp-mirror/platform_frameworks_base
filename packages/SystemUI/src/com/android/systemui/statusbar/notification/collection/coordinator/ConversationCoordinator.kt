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

import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A coordinator that elevates important conversation notifications
 */
@Singleton
class ConversationCoordinator @Inject constructor() : Coordinator {

    private val notificationPromoter = object : NotifPromoter(TAG) {
        override fun shouldPromoteToTopLevel(entry: NotificationEntry): Boolean {
            return entry.channel?.isImportantConversation == true
        }
    }

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPromoter(notificationPromoter)
    }

    companion object {
        private const val TAG = "ConversationCoordinator"
    }
}
