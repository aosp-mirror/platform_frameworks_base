/*
 * Copyright (C) 2022 The Android Open Source Project
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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.phone.NotifActivityLaunchEvents
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import dagger.BindsInstance
import dagger.Component
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
class ActivityLaunchAnimCoordinatorTest : SysuiTestCase() {

    val activityLaunchEvents: NotifActivityLaunchEvents = mock()
    val pipeline: NotifPipeline = mock()

    val coordinator: ActivityLaunchAnimCoordinator =
            DaggerTestActivityStarterCoordinatorComponent
                    .factory()
                    .create(activityLaunchEvents)
                    .coordinator

    @Test
    fun testNoLifetimeExtensionIfNoAssociatedActivityLaunch() {
        coordinator.attach(pipeline)
        val lifetimeExtender = withArgCaptor<NotifLifetimeExtender> {
            verify(pipeline).addNotificationLifetimeExtender(capture())
        }
        val fakeEntry = mock<NotificationEntry>().also {
            whenever(it.key).thenReturn("0")
        }
        assertFalse(lifetimeExtender.maybeExtendLifetime(fakeEntry, 0))
    }

    @Test
    fun testNoLifetimeExtensionIfAssociatedActivityLaunchAlreadyEnded() {
        coordinator.attach(pipeline)
        val lifetimeExtender = withArgCaptor<NotifLifetimeExtender> {
            verify(pipeline).addNotificationLifetimeExtender(capture())
        }
        val eventListener = withArgCaptor<NotifActivityLaunchEvents.Listener> {
            verify(activityLaunchEvents).registerListener(capture())
        }
        val fakeEntry = mock<NotificationEntry>().also {
            whenever(it.key).thenReturn("0")
        }
        eventListener.onStartLaunchNotifActivity(fakeEntry)
        eventListener.onFinishLaunchNotifActivity(fakeEntry)
        assertFalse(lifetimeExtender.maybeExtendLifetime(fakeEntry, 0))
    }

    @Test
    fun testLifetimeExtensionWhileActivityLaunchInProgress() {
        coordinator.attach(pipeline)
        val lifetimeExtender = withArgCaptor<NotifLifetimeExtender> {
            verify(pipeline).addNotificationLifetimeExtender(capture())
        }
        val eventListener = withArgCaptor<NotifActivityLaunchEvents.Listener> {
            verify(activityLaunchEvents).registerListener(capture())
        }
        val onEndLifetimeExtensionCallback =
                mock<NotifLifetimeExtender.OnEndLifetimeExtensionCallback>()
        lifetimeExtender.setCallback(onEndLifetimeExtensionCallback)

        val fakeEntry = mock<NotificationEntry>().also {
            whenever(it.key).thenReturn("0")
        }
        eventListener.onStartLaunchNotifActivity(fakeEntry)
        assertTrue(lifetimeExtender.maybeExtendLifetime(fakeEntry, 0))

        eventListener.onFinishLaunchNotifActivity(fakeEntry)
        verify(onEndLifetimeExtensionCallback).onEndLifetimeExtension(lifetimeExtender, fakeEntry)
    }

    @Test
    fun testCancelLifetimeExtensionDoesNotInvokeCallback() {
        coordinator.attach(pipeline)
        val lifetimeExtender = withArgCaptor<NotifLifetimeExtender> {
            verify(pipeline).addNotificationLifetimeExtender(capture())
        }
        val eventListener = withArgCaptor<NotifActivityLaunchEvents.Listener> {
            verify(activityLaunchEvents).registerListener(capture())
        }
        val onEndLifetimeExtensionCallback =
                mock<NotifLifetimeExtender.OnEndLifetimeExtensionCallback>()
        lifetimeExtender.setCallback(onEndLifetimeExtensionCallback)

        val fakeEntry = mock<NotificationEntry>().also {
            whenever(it.key).thenReturn("0")
        }
        eventListener.onStartLaunchNotifActivity(fakeEntry)
        assertTrue(lifetimeExtender.maybeExtendLifetime(fakeEntry, 0))

        lifetimeExtender.cancelLifetimeExtension(fakeEntry)
        eventListener.onFinishLaunchNotifActivity(fakeEntry)
        verify(onEndLifetimeExtensionCallback, never())
                .onEndLifetimeExtension(lifetimeExtender, fakeEntry)
    }
}

@CoordinatorScope
@Component(modules = [ActivityLaunchAnimCoordinatorModule::class])
interface TestActivityStarterCoordinatorComponent {
    val coordinator: ActivityLaunchAnimCoordinator

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance activityLaunchEvents: NotifActivityLaunchEvents
        ): TestActivityStarterCoordinatorComponent
    }
}
