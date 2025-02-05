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

import android.app.Notification
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.row.shared.ImageModelProvider
import org.junit.Assert

class FakePromotedNotificationContentExtractor : PromotedNotificationContentExtractor {
    @JvmField
    val contentForEntry = mutableMapOf<NotificationEntry, PromotedNotificationContentModel?>()
    @JvmField val extractCalls = mutableListOf<Pair<NotificationEntry, Notification.Builder>>()

    override fun extractContent(
        entry: NotificationEntry,
        recoveredBuilder: Notification.Builder,
        imageModelProvider: ImageModelProvider,
    ): PromotedNotificationContentModel? {
        extractCalls.add(entry to recoveredBuilder)

        if (contentForEntry.isEmpty()) {
            // If *no* entries are set, just return null for everything.
            return null
        } else {
            // If entries *are* set, fail on unexpected ones.
            Assert.assertTrue(contentForEntry.containsKey(entry))
            return contentForEntry[entry]
        }
    }

    fun resetForEntry(entry: NotificationEntry, content: PromotedNotificationContentModel?) {
        contentForEntry.clear()
        contentForEntry[entry] = content
        extractCalls.clear()
    }

    fun verifyZeroExtractCalls() {
        Assert.assertTrue(extractCalls.isEmpty())
    }

    fun verifyOneExtractCall() {
        Assert.assertEquals(1, extractCalls.size)
    }
}
