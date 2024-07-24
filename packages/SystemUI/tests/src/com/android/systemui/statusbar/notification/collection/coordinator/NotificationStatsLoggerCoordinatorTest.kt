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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.platform.test.annotations.EnableFlags
import android.service.notification.NotificationListenerService.REASON_CANCEL
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationStatsLogger
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
class NotificationStatsLoggerCoordinatorTest : SysuiTestCase() {

    private lateinit var collectionListener: NotifCollectionListener

    private val pipeline: NotifPipeline = mock()
    private val logger: NotificationStatsLogger = mock()
    private val underTest = NotificationStatsLoggerCoordinator(Optional.of(logger))

    @Before
    fun attachPipeline() {
        underTest.attach(pipeline)
        collectionListener = withArgCaptor { verify(pipeline).addCollectionListener(capture()) }
    }

    @Test
    fun onEntryAdded_loggerCalled() {
        collectionListener.onEntryRemoved(mockEntry("key"), REASON_CANCEL)

        verify(logger).onNotificationRemoved("key")
    }

    @Test
    fun onEntryRemoved_loggerCalled() {
        collectionListener.onEntryUpdated(mockEntry("key"))

        verify(logger).onNotificationUpdated("key")
    }

    private fun mockEntry(key: String): NotificationEntry {
        return mock { whenever(this.key).thenReturn(key) }
    }
}
