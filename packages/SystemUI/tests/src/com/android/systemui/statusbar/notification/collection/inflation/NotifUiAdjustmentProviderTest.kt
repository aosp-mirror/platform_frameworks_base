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
import android.provider.Settings.Secure.SHOW_NOTIFICATION_SNOOZE
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifUiAdjustmentProviderTest : SysuiTestCase() {
    private val lockscreenUserManager: NotificationLockscreenUserManager = mock()
    private val sectionStyleProvider: SectionStyleProvider = mock()
    private val handler: Handler = mock()
    private val secureSettings: SecureSettings = mock()
    private val uri = FakeSettings().getUriFor(SHOW_NOTIFICATION_SNOOZE)
    private val dirtyListener: Runnable = mock()

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
        sectionStyleProvider,
    )

    @Before
    fun setup() {
        verifyNoMoreInteractions(secureSettings)
        adjustmentProvider.addDirtyListener(dirtyListener)
        verify(secureSettings).getInt(eq(SHOW_NOTIFICATION_SNOOZE), any())
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
    fun additionalAddDoesNotRegisterAgain() {
        clearInvocations(secureSettings)
        adjustmentProvider.addDirtyListener(mock())
        verifyNoMoreInteractions(secureSettings)
    }

    @Test
    fun onChangeWillQueryThenNotifyDirty() {
        contentObserver.onChange(false, listOf(uri), 0, 0)
        with(inOrder(secureSettings, dirtyListener)) {
            verify(secureSettings).getInt(eq(SHOW_NOTIFICATION_SNOOZE), any())
            verify(dirtyListener).run()
        }
    }

    @Test
    fun changingSnoozeChangesProvidedAdjustment() {
        whenever(secureSettings.getInt(eq(SHOW_NOTIFICATION_SNOOZE), any())).thenReturn(0)
        val original = adjustmentProvider.calculateAdjustment(entry)
        assertThat(original.isSnoozeEnabled).isFalse()

        whenever(secureSettings.getInt(eq(SHOW_NOTIFICATION_SNOOZE), any())).thenReturn(1)
        contentObserver.onChange(false, listOf(uri), 0, 0)
        val withSnoozing = adjustmentProvider.calculateAdjustment(entry)
        assertThat(withSnoozing.isSnoozeEnabled).isTrue()
        assertThat(withSnoozing).isNotEqualTo(original)
    }
}
