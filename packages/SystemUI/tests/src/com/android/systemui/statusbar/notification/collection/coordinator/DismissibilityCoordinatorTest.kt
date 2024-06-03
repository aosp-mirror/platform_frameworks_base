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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProviderImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class DismissibilityCoordinatorTest : SysuiTestCase() {

    private lateinit var coordinator: DismissibilityCoordinator
    private lateinit var dismissibilityProvider: NotificationDismissibilityProviderImpl
    private lateinit var onBeforeRenderListListener: OnBeforeRenderListListener
    private val keyguardStateController: KeyguardStateController = mock()
    private val pipeline: NotifPipeline = mock()
    private val dumpManager: DumpManager = mock()

    @Before
    fun setUp() {
        dismissibilityProvider = NotificationDismissibilityProviderImpl(dumpManager)
        coordinator = DismissibilityCoordinator(keyguardStateController, dismissibilityProvider)
        coordinator.attach(pipeline)
        onBeforeRenderListListener = withArgCaptor {
            Mockito.verify(pipeline).addOnBeforeRenderListListener(capture())
        }
    }

    @Test
    fun testNotif() {
        val entry = NotificationEntryBuilder().setTag("entry").build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        assertTrue(
            "Notifs without any flags should be dismissible",
            dismissibilityProvider.isDismissable(entry)
        )
    }

    @Test
    fun testProviderCleared() {
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))
        onBeforeRenderListListener.onBeforeRenderList(emptyList()) // provider should be updated

        assertTrue(dismissibilityProvider.nonDismissableEntryKeys.isEmpty())
    }

    @Test
    fun testNonDismissableEntry() {
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        assertFalse(
            "Non-dismiss Notifs should NOT be dismissible",
            dismissibilityProvider.isDismissable(entry)
        )
    }

    @Test
    fun testOngoingNotifWhenPhoneIsLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        assertFalse(
            "Ongoing Notifs should NOT be dismissible when the device is locked",
            dismissibilityProvider.isDismissable(entry)
        )
    }

    @Test
    fun testOngoingNotifWhenPhoneIsUnLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        assertTrue(
            "Ongoing Notifs should be dismissible when the device is unlocked",
            dismissibilityProvider.isDismissable(entry)
        )
    }

    @Test
    fun testOngoingNondismissNotifWhenPhoneIsUnLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        assertFalse(
            "Non-dismiss Notifs should NOT be dismissible",
            dismissibilityProvider.isDismissable(entry)
        )
    }

    @Test
    fun testMultipleEntries() {
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        val noFlagEntry = NotificationEntryBuilder().setTag("noFlagEntry").build()
        val ongoingEntry =
            NotificationEntryBuilder()
                .setTag("ongoingEntry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()
        val nonDismissEntry =
            NotificationEntryBuilder()
                .setTag("nonDismissEntry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()

        onBeforeRenderListListener.onBeforeRenderList(
            listOf(noFlagEntry, ongoingEntry, nonDismissEntry)
        )

        assertTrue(
            "Notifs without any flags should be dismissible",
            dismissibilityProvider.isDismissable(noFlagEntry)
        )
        assertTrue(
            "Ongoing Notifs should be dismissible when the device is unlocked",
            dismissibilityProvider.isDismissable(ongoingEntry)
        )

        assertFalse(
            "Non-dismiss Notifs should NOT be dismissible",
            dismissibilityProvider.isDismissable(nonDismissEntry)
        )
    }

    @Test
    fun testNonDismissableEntryInGroup() {
        val summary = NotificationEntryBuilder().setTag("summary").build()
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()
        val group = GroupEntryBuilder().setSummary(summary).addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertFalse("Child should be non-dismissible", dismissibilityProvider.isDismissable(entry))
        assertFalse(
            "Summary should be non-dismissible",
            dismissibilityProvider.isDismissable(summary)
        )
    }

    @Test
    fun testOngoingEntryInGroupWhenPhoneIsLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
        val summary = NotificationEntryBuilder().setTag("summary").build()
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()
        val group = GroupEntryBuilder().setSummary(summary).addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertFalse("Child should be non-dismissible", dismissibilityProvider.isDismissable(entry))
        assertFalse(
            "Summary should be non-dismissible",
            dismissibilityProvider.isDismissable(summary)
        )
    }

    @Test
    fun testOngoingEntryInGroupWhenPhoneIsUnLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        val summary = NotificationEntryBuilder().setTag("summary").build()
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()
        val group = GroupEntryBuilder().setSummary(summary).addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertTrue("Child should be dismissible", dismissibilityProvider.isDismissable(entry))
        assertTrue("Summary should be dismissible", dismissibilityProvider.isDismissable(summary))
    }

    @Test
    fun testNonDismissableEntryInGroupWithoutSummary() {
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()
        val group = GroupEntryBuilder().addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertFalse("Child should be non-dismissible", dismissibilityProvider.isDismissable(entry))
    }

    @Test
    fun testOngoingEntryInGroupWithoutSummaryWhenPhoneIsLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(false)
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()
        val group = GroupEntryBuilder().addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertFalse("Child should be non-dismissible", dismissibilityProvider.isDismissable(entry))
    }

    @Test
    fun testOngoingEntryInGroupWithoutSummaryWhenPhoneIsUnLocked() {
        whenever(keyguardStateController.isUnlocked).thenReturn(true)
        val entry =
            NotificationEntryBuilder()
                .setTag("entry")
                .setFlag(mContext, Notification.FLAG_ONGOING_EVENT, true)
                .build()
        val group = GroupEntryBuilder().addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertTrue("Child should be dismissible", dismissibilityProvider.isDismissable(entry))
    }

    @Test
    fun testNonDismissableSummary() {
        val summary =
            NotificationEntryBuilder()
                .setTag("summary")
                .setFlag(mContext, Notification.FLAG_NO_DISMISS, true)
                .build()
        val entry = NotificationEntryBuilder().setTag("entry").build()
        val group = GroupEntryBuilder().setSummary(summary).addChild(entry).build()

        onBeforeRenderListListener.onBeforeRenderList(listOf(group))

        assertTrue("Child should be dismissible", dismissibilityProvider.isDismissable(entry))
        assertFalse(
            "Summary should be non-dismissible",
            dismissibilityProvider.isDismissable(summary)
        )
    }
}
