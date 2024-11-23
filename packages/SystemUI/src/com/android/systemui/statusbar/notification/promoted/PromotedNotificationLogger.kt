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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel.ERROR
import com.android.systemui.log.core.LogLevel.INFO
import com.android.systemui.log.dagger.NotificationLog
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.logKey
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import javax.inject.Inject

class PromotedNotificationLogger
@Inject
constructor(@NotificationLog private val buffer: LogBuffer) {
    fun logExtractionSkipped(entry: NotificationEntry, reason: String) {
        buffer.log(
            EXTRACTION_TAG,
            INFO,
            {
                str1 = entry.logKey
                str2 = reason
            },
            { "extraction skipped: $str2 for $str1" },
        )
    }

    fun logExtractionFailed(entry: NotificationEntry, reason: String) {
        buffer.log(
            EXTRACTION_TAG,
            ERROR,
            {
                str1 = entry.logKey
                str2 = reason
            },
            { "extraction failed: $str2 for $str1" },
        )
    }

    fun logExtractionSucceeded(
        entry: NotificationEntry,
        content: PromotedNotificationContentModel,
    ) {
        buffer.log(
            EXTRACTION_TAG,
            INFO,
            {
                str1 = entry.logKey
                str2 = content.toString()
            },
            { "extraction succeeded: $str2 for $str1" },
        )
    }
}

private const val EXTRACTION_TAG = "PromotedNotificationContentExtractor"
