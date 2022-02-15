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

import android.os.Handler
import android.service.notification.StatusBarNotification
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.NotificationRemoteInputManager.RemoteInputListener
import com.android.systemui.statusbar.RemoteInputNotificationRebuilder
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.InternalNotifUpdater
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class RemoteInputCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: RemoteInputCoordinator
    private lateinit var listener: RemoteInputListener
    private lateinit var collectionListener: NotifCollectionListener

    private lateinit var entry1: NotificationEntry
    private lateinit var entry2: NotificationEntry

    @Mock private lateinit var lifetimeExtensionCallback: OnEndLifetimeExtensionCallback
    @Mock private lateinit var rebuilder: RemoteInputNotificationRebuilder
    @Mock private lateinit var remoteInputManager: NotificationRemoteInputManager
    @Mock private lateinit var mainHandler: Handler
    @Mock private lateinit var smartReplyController: SmartReplyController
    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var notifUpdater: InternalNotifUpdater
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var sbn: StatusBarNotification

    @Before
    fun setUp() {
        initMocks(this)
        coordinator = RemoteInputCoordinator(
                dumpManager,
                rebuilder,
                remoteInputManager,
                mainHandler,
                smartReplyController
        )
        `when`(pipeline.addNotificationLifetimeExtender(any())).thenAnswer {
            (it.arguments[0] as NotifLifetimeExtender).setCallback(lifetimeExtensionCallback)
        }
        `when`(pipeline.getInternalNotifUpdater(any())).thenReturn(notifUpdater)
        coordinator.attach(pipeline)
        listener = withArgCaptor {
            verify(remoteInputManager).setRemoteInputListener(capture())
        }
        collectionListener = withArgCaptor {
            verify(pipeline).addCollectionListener(capture())
        }
        entry1 = NotificationEntryBuilder().setId(1).build()
        entry2 = NotificationEntryBuilder().setId(2).build()
        `when`(rebuilder.rebuildForCanceledSmartReplies(any())).thenReturn(sbn)
        `when`(rebuilder.rebuildForRemoteInputReply(any())).thenReturn(sbn)
        `when`(rebuilder.rebuildForSendingSmartReply(any(), any())).thenReturn(sbn)
    }

    val remoteInputActiveExtender get() = coordinator.mRemoteInputActiveExtender
    val remoteInputHistoryExtender get() = coordinator.mRemoteInputHistoryExtender
    val smartReplyHistoryExtender get() = coordinator.mSmartReplyHistoryExtender

    @Test
    fun testRemoteInputActive() {
        `when`(remoteInputManager.isRemoteInputActive(entry1)).thenReturn(true)
        assertThat(remoteInputActiveExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(remoteInputHistoryExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(smartReplyHistoryExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(listener.isNotificationKeptForRemoteInputHistory(entry1.key)).isFalse()
    }

    @Test
    fun testRemoteInputHistory() {
        `when`(remoteInputManager.shouldKeepForRemoteInputHistory(entry1)).thenReturn(true)
        assertThat(remoteInputActiveExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(remoteInputHistoryExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(smartReplyHistoryExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(listener.isNotificationKeptForRemoteInputHistory(entry1.key)).isTrue()
    }

    @Test
    fun testSmartReplyHistory() {
        `when`(remoteInputManager.shouldKeepForSmartReplyHistory(entry1)).thenReturn(true)
        assertThat(remoteInputActiveExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(remoteInputHistoryExtender.maybeExtendLifetime(entry1, 0)).isFalse()
        assertThat(smartReplyHistoryExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(listener.isNotificationKeptForRemoteInputHistory(entry1.key)).isTrue()
    }

    @Test
    fun testNotificationWithRemoteInputActiveIsRemovedOnCollapse() {
        `when`(remoteInputManager.isRemoteInputActive(entry1)).thenReturn(true)
        assertThat(remoteInputActiveExtender.isExtending(entry1.key)).isFalse()

        // Nothing should happen on panel collapse before we start extending the lifetime
        listener.onPanelCollapsed()
        assertThat(remoteInputActiveExtender.isExtending(entry1.key)).isFalse()
        verify(lifetimeExtensionCallback, never()).onEndLifetimeExtension(any(), any())

        // Start extending lifetime & validate that the extension is ended
        assertThat(remoteInputActiveExtender.maybeExtendLifetime(entry1, 0)).isTrue()
        assertThat(remoteInputActiveExtender.isExtending(entry1.key)).isTrue()
        listener.onPanelCollapsed()
        verify(lifetimeExtensionCallback).onEndLifetimeExtension(remoteInputActiveExtender, entry1)
        assertThat(remoteInputActiveExtender.isExtending(entry1.key)).isFalse()
    }
}
