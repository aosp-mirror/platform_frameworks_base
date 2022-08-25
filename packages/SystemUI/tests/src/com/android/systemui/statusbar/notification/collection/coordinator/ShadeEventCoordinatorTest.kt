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

import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_CANCEL
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks
import java.util.concurrent.Executor
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class ShadeEventCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: ShadeEventCoordinator
    private lateinit var notifCollectionListener: NotifCollectionListener
    private lateinit var onBeforeRenderListListener: OnBeforeRenderListListener

    private lateinit var entry1: NotificationEntry
    private lateinit var entry2: NotificationEntry

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var logger: ShadeEventCoordinatorLogger
    @Mock private lateinit var executor: Executor
    @Mock private lateinit var notifRemovedByUserCallback: Runnable
    @Mock private lateinit var shadeEmptiedCallback: Runnable

    @Before
    fun setUp() {
        initMocks(this)
        whenever(executor.execute(any())).then {
            (it.arguments[0] as Runnable).run()
            true
        }
        coordinator = ShadeEventCoordinator(executor, logger)
        coordinator.attach(pipeline)
        notifCollectionListener = withArgCaptor {
            verify(pipeline).addCollectionListener(capture())
        }
        onBeforeRenderListListener = withArgCaptor {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }
        coordinator.setNotifRemovedByUserCallback(notifRemovedByUserCallback)
        coordinator.setShadeEmptiedCallback(shadeEmptiedCallback)
        entry1 = NotificationEntryBuilder().setId(1).build()
        entry2 = NotificationEntryBuilder().setId(2).build()
    }

    @Test
    fun testUserCancelLastNotification() {
        notifCollectionListener.onEntryRemoved(entry1, REASON_CANCEL)
        verify(shadeEmptiedCallback, never()).run()
        verify(notifRemovedByUserCallback, never()).run()
        onBeforeRenderListListener.onBeforeRenderList(listOf())
        verify(shadeEmptiedCallback).run()
        verify(notifRemovedByUserCallback).run()
    }

    @Test
    fun testAppCancelLastNotification() {
        notifCollectionListener.onEntryRemoved(entry1, REASON_APP_CANCEL)
        onBeforeRenderListListener.onBeforeRenderList(listOf())
        verify(shadeEmptiedCallback).run()
        verify(notifRemovedByUserCallback, never()).run()
    }

    @Test
    fun testUserCancelOneOfTwoNotifications() {
        notifCollectionListener.onEntryRemoved(entry1, REASON_CANCEL)
        onBeforeRenderListListener.onBeforeRenderList(listOf(entry2))
        verify(shadeEmptiedCallback, never()).run()
        verify(notifRemovedByUserCallback).run()
    }

    @Test
    fun testAppCancelOneOfTwoNotifications() {
        notifCollectionListener.onEntryRemoved(entry1, REASON_APP_CANCEL)
        onBeforeRenderListListener.onBeforeRenderList(listOf(entry2))
        verify(shadeEmptiedCallback, never()).run()
        verify(notifRemovedByUserCallback, never()).run()
    }
}
