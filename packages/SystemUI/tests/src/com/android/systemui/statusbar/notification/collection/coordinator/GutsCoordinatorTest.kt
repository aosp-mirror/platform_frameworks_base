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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewListener
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewManager
import com.android.systemui.statusbar.notification.row.NotificationGuts
import com.android.systemui.statusbar.notification.row.NotificationGuts.GutsContent
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class GutsCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: GutsCoordinator
    private lateinit var notifLifetimeExtender: NotifLifetimeExtender
    private lateinit var notifGutsViewListener: NotifGutsViewListener

    private lateinit var entry1: NotificationEntry
    private lateinit var entry2: NotificationEntry

    @Mock private lateinit var notifGutsViewManager: NotifGutsViewManager
    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var dumpManager: DumpManager
    private val logger = GutsCoordinatorLogger(logcatLogBuffer())
    @Mock private lateinit var lifetimeExtenderCallback: OnEndLifetimeExtensionCallback
    @Mock private lateinit var notificationGuts: NotificationGuts

    @Before
    fun setUp() {
        initMocks(this)
        coordinator = GutsCoordinator(notifGutsViewManager, logger, dumpManager)
        coordinator.attach(pipeline)
        notifLifetimeExtender = withArgCaptor {
            verify(pipeline).addNotificationLifetimeExtender(capture())
        }
        notifGutsViewListener = withArgCaptor {
            verify(notifGutsViewManager).setGutsListener(capture())
        }
        notifLifetimeExtender.setCallback(lifetimeExtenderCallback)
        entry1 = NotificationEntryBuilder().setId(1).build()
        entry2 = NotificationEntryBuilder().setId(2).build()
        whenever(notificationGuts.gutsContent).thenReturn(mock(GutsContent::class.java))
    }

    @Test
    fun testSimpleLifetimeExtension() {
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        notifGutsViewListener.onGutsOpen(entry1, notificationGuts)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        notifGutsViewListener.onGutsClose(entry1)
        verify(lifetimeExtenderCallback).onEndLifetimeExtension(notifLifetimeExtender, entry1)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
    }

    @Test
    fun testDoubleOpenLifetimeExtension() {
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        notifGutsViewListener.onGutsOpen(entry1, notificationGuts)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        notifGutsViewListener.onGutsOpen(entry1, notificationGuts)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        notifGutsViewListener.onGutsClose(entry1)
        verify(lifetimeExtenderCallback).onEndLifetimeExtension(notifLifetimeExtender, entry1)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
    }

    @Test
    fun testTwoEntryLifetimeExtension() {
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry2, 0)).isFalse()
        notifGutsViewListener.onGutsOpen(entry1, notificationGuts)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry2, 0)).isFalse()
        notifGutsViewListener.onGutsOpen(entry2, notificationGuts)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry2, 0)).isTrue()
        notifGutsViewListener.onGutsClose(entry1)
        verify(lifetimeExtenderCallback).onEndLifetimeExtension(notifLifetimeExtender, entry1)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry2, 0)).isTrue()
        notifGutsViewListener.onGutsClose(entry2)
        verify(lifetimeExtenderCallback).onEndLifetimeExtension(notifLifetimeExtender, entry2)
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(notifLifetimeExtender.maybeExtendLifetime(entry2, 0)).isFalse()
    }
}
