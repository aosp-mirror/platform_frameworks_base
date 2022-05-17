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

import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.DynamicPrivacyController
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import dagger.BindsInstance
import dagger.Component
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
class SensitiveContentCoordinatorTest : SysuiTestCase() {

    val dynamicPrivacyController: DynamicPrivacyController = mock()
    val lockscreenUserManager: NotificationLockscreenUserManager = mock()
    val pipeline: NotifPipeline = mock()
    val keyguardUpdateMonitor: KeyguardUpdateMonitor = mock()
    val statusBarStateController: StatusBarStateController = mock()
    val keyguardStateController: KeyguardStateController = mock()

    val coordinator: SensitiveContentCoordinator =
        DaggerTestSensitiveContentCoordinatorComponent
                .factory()
                .create(
                        dynamicPrivacyController,
                        lockscreenUserManager,
                        keyguardUpdateMonitor,
                        statusBarStateController,
                        keyguardStateController)
                .coordinator

    @Test
    fun onDynamicPrivacyChanged_invokeInvalidationListener() {
        coordinator.attach(pipeline)
        val invalidator = withArgCaptor<Invalidator> {
            verify(pipeline).addPreRenderInvalidator(capture())
        }
        val dynamicPrivacyListener = withArgCaptor<DynamicPrivacyController.Listener> {
            verify(dynamicPrivacyController).addListener(capture())
        }

        val invalidationListener = mock<Pluggable.PluggableListener<Invalidator>>()
        invalidator.setInvalidationListener(invalidationListener)

        dynamicPrivacyListener.onDynamicPrivacyChanged()

        verify(invalidationListener).onPluggableInvalidated(invalidator)
    }

    @Test
    fun onBeforeRenderList_deviceUnlocked_notifDoesNotNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, false)
    }

    @Test
    fun onBeforeRenderList_deviceUnlocked_notifWouldNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, false)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_userAllowsPublicNotifs() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, false)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_userDisallowsPublicNotifs_notifDoesNotNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_notifNeedsRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifNeedsRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        val entry = fakeNotification(1, true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifUserNeedsWorkChallenge() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        whenever(lockscreenUserManager.needsSeparateWorkChallenge(2)).thenReturn(true)

        val entry = fakeNotification(2, true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_deviceBiometricBypassingLockScreen() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener = withArgCaptor<OnBeforeRenderListListener> {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        whenever(statusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometricAndIsBypassing(any()))
                .thenReturn(true)

        val entry = fakeNotification(2, true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!, never()).setSensitive(any(), any())
    }

    private fun fakeNotification(notifUserId: Int, needsRedaction: Boolean): ListEntry {
        val mockUserHandle = mock<UserHandle>().apply {
            whenever(identifier).thenReturn(notifUserId)
        }
        val mockSbn: StatusBarNotification = mock<StatusBarNotification>().apply {
            whenever(user).thenReturn(mockUserHandle)
        }
        val mockEntry = mock<NotificationEntry>().apply {
            whenever(sbn).thenReturn(mockSbn)
        }
        whenever(lockscreenUserManager.needsRedaction(mockEntry)).thenReturn(needsRedaction)
        whenever(mockEntry.rowExists()).thenReturn(true)
        return object : ListEntry("key", 0) {
            override fun getRepresentativeEntry(): NotificationEntry = mockEntry
        }
    }
}

@CoordinatorScope
@Component(modules = [SensitiveContentCoordinatorModule::class])
interface TestSensitiveContentCoordinatorComponent {
    val coordinator: SensitiveContentCoordinator

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance dynamicPrivacyController: DynamicPrivacyController,
            @BindsInstance lockscreenUserManager: NotificationLockscreenUserManager,
            @BindsInstance keyguardUpdateMonitor: KeyguardUpdateMonitor,
            @BindsInstance statusBarStateController: StatusBarStateController,
            @BindsInstance keyguardStateController: KeyguardStateController
        ): TestSensitiveContentCoordinatorComponent
    }
}