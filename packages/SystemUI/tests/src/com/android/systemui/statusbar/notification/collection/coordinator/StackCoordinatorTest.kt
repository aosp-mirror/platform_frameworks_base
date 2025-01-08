/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.notification.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING
import com.android.systemui.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING_BUG_FIX
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManagerImpl
import com.android.systemui.statusbar.notification.data.model.NotifStats
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.RenderNotificationListInteractor
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class StackCoordinatorTest : SysuiTestCase() {
    private lateinit var entry: NotificationEntry
    private lateinit var coordinator: StackCoordinator
    private lateinit var afterRenderListListener: OnAfterRenderListListener

    private val pipeline: NotifPipeline = mock()
    private val groupExpansionManagerImpl: GroupExpansionManagerImpl = mock()
    private val renderListInteractor: RenderNotificationListInteractor = mock()
    private val activeNotificationsInteractor: ActiveNotificationsInteractor = mock()
    private val sensitiveNotificationProtectionController:
        SensitiveNotificationProtectionController =
        mock()
    private val section: NotifSection = mock()
    private val row: ExpandableNotificationRow = mock()

    @Before
    fun setUp() {
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(false)

        entry = NotificationEntryBuilder().setSection(section).build()
        entry.row = row
        entry.setSensitive(false, false)
        coordinator =
            StackCoordinator(
                groupExpansionManagerImpl,
                renderListInteractor,
                activeNotificationsInteractor,
                sensitiveNotificationProtectionController,
            )
        coordinator.attach(pipeline)
        afterRenderListListener = withArgCaptor {
            verify(pipeline).addOnAfterRenderListListener(capture())
        }
    }

    @Test
    fun testSetRenderedListOnInteractor() {
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(renderListInteractor).setRenderedList(eq(listOf(entry)))
    }

    @Test
    fun testSetNotificationStats_clearableAlerting() {
        whenever(section.bucket).thenReturn(BUCKET_ALERTING)
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(activeNotificationsInteractor)
            .setNotifStats(
                NotifStats(
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = true,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            )
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING, FLAG_SCREENSHARE_NOTIFICATION_HIDING_BUG_FIX)
    fun testSetNotificationStats_isSensitiveStateActive_nonClearableAlerting() {
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)
        whenever(section.bucket).thenReturn(BUCKET_ALERTING)
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(activeNotificationsInteractor)
            .setNotifStats(
                NotifStats(
                    hasNonClearableAlertingNotifs = true,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            )
    }

    @Test
    fun testSetNotificationStats_clearableSilent() {
        whenever(section.bucket).thenReturn(BUCKET_SILENT)
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(activeNotificationsInteractor)
            .setNotifStats(
                NotifStats(
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = true,
                )
            )
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING, FLAG_SCREENSHARE_NOTIFICATION_HIDING_BUG_FIX)
    fun testSetNotificationStats_isSensitiveStateActive_nonClearableSilent() {
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)
        whenever(section.bucket).thenReturn(BUCKET_SILENT)
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(activeNotificationsInteractor)
            .setNotifStats(
                NotifStats(
                    hasNonClearableAlertingNotifs = false,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = true,
                    hasClearableSilentNotifs = false,
                )
            )
    }

    @Test
    fun testSetNotificationStats_nonClearableRedacted() {
        entry.setSensitive(true, true)
        whenever(section.bucket).thenReturn(BUCKET_ALERTING)
        afterRenderListListener.onAfterRenderList(listOf(entry))
        verify(activeNotificationsInteractor)
            .setNotifStats(
                NotifStats(
                    hasNonClearableAlertingNotifs = true,
                    hasClearableAlertingNotifs = false,
                    hasNonClearableSilentNotifs = false,
                    hasClearableSilentNotifs = false,
                )
            )
    }
}
