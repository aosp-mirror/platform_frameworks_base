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
 * limitations under the License.
 */
package com.android.systemui.statusbar.notification.collection.inflation

import android.database.ContentObserver
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings.Secure.SHOW_NOTIFICATION_SNOOZE
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.server.notification.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotifUiAdjustmentProviderTest : SysuiTestCase() {
    private val lockscreenUserManager: NotificationLockscreenUserManager = mock()
    private val sensitiveNotifProtectionController: SensitiveNotificationProtectionController =
        mock()
    private val sectionStyleProvider: SectionStyleProvider = mock()
    private val handler: Handler = mock()
    private val secureSettings: SecureSettings = mock()
    private val uri = FakeSettings().getUriFor(SHOW_NOTIFICATION_SNOOZE)
    private val dirtyListener: Runnable = mock()
    private val userTracker: UserTracker = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()

    private val section = NotifSection(mock(), 0)
    private val entry = NotificationEntryBuilder()
        .setSection(section)
        .setParent(GroupEntry.ROOT_ENTRY)
        .build()

    private lateinit var contentObserver: ContentObserver

    private val adjustmentProvider = NotifUiAdjustmentProvider(
        handler,
        secureSettings,
        lockscreenUserManager,
        sensitiveNotifProtectionController,
        sectionStyleProvider,
        userTracker,
        groupMembershipManager,
    )

    @Before
    fun setup() {
        verifyNoMoreInteractions(secureSettings)
        adjustmentProvider.addDirtyListener(dirtyListener)
        verify(secureSettings).getIntForUser(eq(SHOW_NOTIFICATION_SNOOZE), any(), any())
        contentObserver = withArgCaptor {
            verify(secureSettings).registerContentObserverForUser(
                eq(SHOW_NOTIFICATION_SNOOZE), capture(), any()
            )
        }
        verifyNoMoreInteractions(secureSettings, dirtyListener)
    }

    @Test
    fun notifLockscreenStateChangeWillNotifDirty() {
        val dirtyListener = mock<Runnable>()
        adjustmentProvider.addDirtyListener(dirtyListener)
        val notifLocksreenStateChangeListener =
            withArgCaptor<NotificationLockscreenUserManager.NotificationStateChangedListener> {
                verify(lockscreenUserManager).addNotificationStateChangedListener(capture())
            }
        notifLocksreenStateChangeListener.onNotificationStateChanged()
        verify(dirtyListener).run()
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun sensitiveNotifProtectionStateChangeWillNotifDirty() {
        val dirtyListener = mock<Runnable>()
        adjustmentProvider.addDirtyListener(dirtyListener)
        val sensitiveStateChangedListener =
            withArgCaptor<Runnable> {
                verify(sensitiveNotifProtectionController).registerSensitiveStateListener(capture())
            }
        sensitiveStateChangedListener.run()
        verify(dirtyListener).run()
    }

    @Test
    fun additionalAddDoesNotRegisterAgain() {
        clearInvocations(secureSettings)
        adjustmentProvider.addDirtyListener(mock())
        verifyNoMoreInteractions(secureSettings)
    }

    @Test
    fun onChangeWillQueryThenNotifyDirty() {
        contentObserver.onChange(false, listOf(uri), 0, 0)
        with(inOrder(secureSettings, dirtyListener)) {
            verify(secureSettings).getIntForUser(eq(SHOW_NOTIFICATION_SNOOZE), any(), any())
            verify(dirtyListener).run()
        }
    }

    @Test
    fun changingSnoozeChangesProvidedAdjustment() {
        whenever(secureSettings.getIntForUser(eq(SHOW_NOTIFICATION_SNOOZE), any(), any()))
            .thenReturn(0)
        val original = adjustmentProvider.calculateAdjustment(entry)
        assertThat(original.isSnoozeEnabled).isFalse()

        whenever(secureSettings.getIntForUser(eq(SHOW_NOTIFICATION_SNOOZE), any(), any()))
            .thenReturn(1)
        contentObserver.onChange(false, listOf(uri), 0, 0)
        val withSnoozing = adjustmentProvider.calculateAdjustment(entry)
        assertThat(withSnoozing.isSnoozeEnabled).isTrue()
        assertThat(withSnoozing).isNotEqualTo(original)
    }

    @Test
    @EnableFlags(AsyncHybridViewInflation.FLAG_NAME)
    fun becomeChildInGroup_asyncHybirdFlagEnabled_needReInflation() {
        // Given: an Entry that is not child in group
        // AsyncHybridViewInflation flag is enabled
        val oldAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(oldAdjustment.isChildInGroup).isFalse()

        // When: the Entry becomes a group child
        entry.markAsGroupChild()
        val newAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(newAdjustment.isChildInGroup).isTrue()
        assertThat(newAdjustment).isNotEqualTo(oldAdjustment)

        // Then: need re-inflation
        assertTrue(NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment))
    }

    @Test
    @DisableFlags(AsyncHybridViewInflation.FLAG_NAME)
    fun becomeChildInGroup_asyncHybirdFlagDisabled_noNeedForReInflation() {
        // Given: an Entry that is not child in group
        // AsyncHybridViewInflation flag is disabled
        val oldAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(oldAdjustment.isChildInGroup).isFalse()

        // When: the Entry becomes a group child
        entry.markAsGroupChild()
        val newAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(newAdjustment.isChildInGroup).isTrue()
        assertThat(newAdjustment).isNotEqualTo(oldAdjustment)

        // Then: need no re-inflation
        assertFalse(NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment))
    }

    @Test
    @EnableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    fun changeIsGroupSummary_needReInflation() {
        // Given: an Entry that is not a group summary
        val oldAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(oldAdjustment.isGroupSummary).isFalse()

        // When: the Entry becomes a group summary
        entry.markAsGroupSummary()
        val newAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertThat(newAdjustment.isGroupSummary).isTrue()
        assertThat(newAdjustment).isNotEqualTo(oldAdjustment)

        // Then: Need re-inflation
        assertTrue(NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment))
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun changeSensitiveNotifProtection_screenshareNotificationHidingEnabled_needReinflate() {
        whenever(sensitiveNotifProtectionController.shouldProtectNotification(entry))
            .thenReturn(false)
        val oldAdjustment: NotifUiAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertFalse(oldAdjustment.needsRedaction)

        whenever(sensitiveNotifProtectionController.shouldProtectNotification(entry))
            .thenReturn(true)
        val newAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertTrue(newAdjustment.needsRedaction)

        // Then: need re-inflation
        assertTrue(NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment))
    }

    @Test
    @DisableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun changeSensitiveNotifProtection_screenshareNotificationHidingDisabled_noNeedReinflate() {
        whenever(sensitiveNotifProtectionController.shouldProtectNotification(entry))
            .thenReturn(false)
        val oldAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertFalse(oldAdjustment.needsRedaction)

        whenever(sensitiveNotifProtectionController.shouldProtectNotification(entry))
            .thenReturn(true)
        val newAdjustment = adjustmentProvider.calculateAdjustment(entry)
        assertFalse(newAdjustment.needsRedaction)

        // Then: need no re-inflation
        assertFalse(NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment))
    }
}
