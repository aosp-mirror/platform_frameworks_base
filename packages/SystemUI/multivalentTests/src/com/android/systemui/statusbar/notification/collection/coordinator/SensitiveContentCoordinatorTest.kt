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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.service.notification.StatusBarNotification
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.keyguardUpdateMonitor
import com.android.server.notification.Flags.FLAG_SCREENSHARE_NOTIFICATION_HIDING
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.plugins.statusbar.fakeStatusBarStateController
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.RankingBuilder
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.DynamicPrivacyController
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable
import com.android.systemui.statusbar.notification.collection.notifPipeline
import com.android.systemui.statusbar.notification.dynamicPrivacyController
import com.android.systemui.statusbar.notification.mockDynamicPrivacyController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.statusbar.policy.mockSensitiveNotificationProtectionController
import com.android.systemui.statusbar.policy.sensitiveNotificationProtectionController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.withArgCaptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class SensitiveContentCoordinatorTest(flags: FlagsParameterization) : SysuiTestCase() {

    val kosmos =
        testKosmos().apply {
            // Override some Kosmos objects with mocks or fakes for easier testability
            dynamicPrivacyController = mockDynamicPrivacyController
            sensitiveNotificationProtectionController =
                mockSensitiveNotificationProtectionController
            statusBarStateController = fakeStatusBarStateController
        }

    val dynamicPrivacyController: DynamicPrivacyController = kosmos.mockDynamicPrivacyController
    val lockscreenUserManager: NotificationLockscreenUserManager =
        kosmos.notificationLockscreenUserManager
    val pipeline: NotifPipeline = kosmos.notifPipeline
    val keyguardUpdateMonitor: KeyguardUpdateMonitor = kosmos.keyguardUpdateMonitor
    val statusBarStateController: SysuiStatusBarStateController =
        kosmos.fakeStatusBarStateController
    val sensitiveNotificationProtectionController: SensitiveNotificationProtectionController =
        kosmos.mockSensitiveNotificationProtectionController
    val sceneInteractor: SceneInteractor = kosmos.sceneInteractor

    val coordinator: SensitiveContentCoordinator by lazy { kosmos.sensitiveContentCoordinator }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Test
    fun onDynamicPrivacyChanged_invokeInvalidationListener() {
        coordinator.attach(pipeline)
        val invalidator =
            withArgCaptor<Invalidator> { verify(pipeline).addPreRenderInvalidator(capture()) }
        val dynamicPrivacyListener =
            withArgCaptor<DynamicPrivacyController.Listener> {
                verify(dynamicPrivacyController).addListener(capture())
            }

        val invalidationListener = mock<Pluggable.PluggableListener<Invalidator>>()
        invalidator.setInvalidationListener(invalidationListener)

        dynamicPrivacyListener.onDynamicPrivacyChanged()

        verify(invalidationListener).onPluggableInvalidated(eq(invalidator), any())
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onSensitiveStateChanged_invokeInvalidationListener() {
        coordinator.attach(pipeline)
        val invalidator =
            withArgCaptor<Invalidator> { verify(pipeline).addPreRenderInvalidator(capture()) }
        val onSensitiveStateChangedListener =
            withArgCaptor<Runnable> {
                verify(sensitiveNotificationProtectionController)
                    .registerSensitiveStateListener(capture())
            }

        val invalidationListener = mock<Pluggable.PluggableListener<Invalidator>>()
        invalidator.setInvalidationListener(invalidationListener)

        onSensitiveStateChangedListener.run()

        verify(invalidationListener).onPluggableInvalidated(eq(invalidator), any())
    }

    @Test
    @DisableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun screenshareSecretFilter_flagDisabled_filterNoAdded() {
        coordinator.attach(pipeline)

        verify(pipeline, never()).addFinalizeFilter(any())
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun screenshareSecretFilter_sensitiveInctive_noFiltersSecret() {
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(false)

        coordinator.attach(pipeline)
        val filter = withArgCaptor<NotifFilter> { verify(pipeline).addFinalizeFilter(capture()) }

        val defaultNotification = createNotificationEntry("test", false, false)
        val notificationWithSecretVisibility = createNotificationEntry("test", true, false)
        val notificationOnSecretChannel = createNotificationEntry("test", false, true)

        assertFalse(filter.shouldFilterOut(defaultNotification, 0))
        assertFalse(filter.shouldFilterOut(notificationWithSecretVisibility, 0))
        assertFalse(filter.shouldFilterOut(notificationOnSecretChannel, 0))
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun screenshareSecretFilter_sensitiveActive_filtersSecret() {
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        coordinator.attach(pipeline)
        val filter = withArgCaptor<NotifFilter> { verify(pipeline).addFinalizeFilter(capture()) }

        val defaultNotification = createNotificationEntry("test", false, false)
        val notificationWithSecretVisibility = createNotificationEntry("test", true, false)
        val notificationOnSecretChannel = createNotificationEntry("test", false, true)

        assertFalse(filter.shouldFilterOut(defaultNotification, 0))
        assertTrue(filter.shouldFilterOut(notificationWithSecretVisibility, 0))
        assertTrue(filter.shouldFilterOut(notificationOnSecretChannel, 0))
    }

    @Test
    fun onBeforeRenderList_deviceUnlocked_notifDoesNotNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceUnlocked_notifDoesNotNeedRedaction_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceUnlocked_notifDoesNotNeedRedaction_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, false)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceUnlocked_notifWouldNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceUnlocked_notifWouldNeedRedaction_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceUnlocked_notifWouldNeedRedaction_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(false)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, false)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_userAllowsPublicNotifs() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceLocked_userAllowsPublicNotifs_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceLocked_userAllowsPublicNotifs_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(true)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, false)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_userDisallowsPublicNotifs_notifDoesNotNeedRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Suppress("ktlint:standard:max-line-length")
    fun onBeforeRenderList_deviceLocked_userDisallowsPublicNotifs_notifDoesNotNeedRedaction_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Suppress("ktlint:standard:max-line-length")
    fun onBeforeRenderList_deviceLocked_userDisallowsPublicNotifs_notifDoesNotNeedRedaction_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, false)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceLocked_notifNeedsRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceLocked_notifNeedsRedaction_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceLocked_notifNeedsRedaction_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(false)
        val entry = fakeNotification(1, true)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifNeedsRedaction() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifNeedsRedaction_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        val entry = fakeNotification(1, true)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(false, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Suppress("ktlint:standard:max-line-length")
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifNeedsRedaction_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        val entry = fakeNotification(1, true)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifUserNeedsWorkChallenge() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
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
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifUserNeedsWorkChallenge_sensitiveActive() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        whenever(lockscreenUserManager.needsSeparateWorkChallenge(2)).thenReturn(true)
        val entry = fakeNotification(2, true)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(true)
    }

    @Test
    @EnableFlags(FLAG_SCREENSHARE_NOTIFICATION_HIDING)
    @Suppress("ktlint:standard:max-line-length")
    fun onBeforeRenderList_deviceDynamicallyUnlocked_notifUserNeedsWorkChallenge_shouldProtectNotification() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        whenever(lockscreenUserManager.needsSeparateWorkChallenge(2)).thenReturn(true)
        val entry = fakeNotification(2, true)
        whenever(
                sensitiveNotificationProtectionController.shouldProtectNotification(
                    entry.getRepresentativeEntry()
                )
            )
            .thenReturn(true)

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!).setSensitive(true, true)
        verify(entry.representativeEntry!!.row!!).setPublicExpanderVisible(false)
    }

    @Test
    fun onBeforeRenderList_deviceDynamicallyUnlocked_deviceBiometricBypassingLockScreen() {
        coordinator.attach(pipeline)
        val onBeforeRenderListListener =
            withArgCaptor<OnBeforeRenderListListener> {
                verify(pipeline).addOnBeforeRenderListListener(capture())
            }

        whenever(lockscreenUserManager.currentUserId).thenReturn(1)
        whenever(lockscreenUserManager.isLockscreenPublicMode(1)).thenReturn(true)
        whenever(lockscreenUserManager.userAllowsPrivateNotificationsInPublic(1)).thenReturn(false)
        whenever(dynamicPrivacyController.isDynamicallyUnlocked).thenReturn(true)
        whenever(keyguardUpdateMonitor.getUserUnlockedWithBiometricAndIsBypassing(any()))
            .thenReturn(true)
        val entry = fakeNotification(2, true)
        whenever(sensitiveNotificationProtectionController.isSensitiveStateActive).thenReturn(true)
        whenever(sensitiveNotificationProtectionController.shouldProtectNotification(any()))
            .thenReturn(true)
        statusBarStateController.state = StatusBarState.KEYGUARD

        onBeforeRenderListListener.onBeforeRenderList(listOf(entry))

        verify(entry.representativeEntry!!, never()).setSensitive(any(), any())
        verify(entry.representativeEntry!!.row!!, never()).setPublicExpanderVisible(any())
    }

    private fun fakeNotification(notifUserId: Int, needsRedaction: Boolean): ListEntry {
        val mockUserHandle =
            mock<UserHandle>().apply { whenever(identifier).thenReturn(notifUserId) }
        val mockSbn: StatusBarNotification =
            mock<StatusBarNotification>().apply { whenever(user).thenReturn(mockUserHandle) }
        val mockRow: ExpandableNotificationRow = mock<ExpandableNotificationRow>()
        val mockEntry =
            mock<NotificationEntry>().apply {
                whenever(sbn).thenReturn(mockSbn)
                whenever(row).thenReturn(mockRow)
            }
        whenever(lockscreenUserManager.needsRedaction(mockEntry)).thenReturn(needsRedaction)
        whenever(mockEntry.rowExists()).thenReturn(true)
        return object : ListEntry("key", 0) {
            override fun getRepresentativeEntry(): NotificationEntry = mockEntry
        }
    }

    private fun createNotificationEntry(
        packageName: String,
        secretVisibility: Boolean = false,
        secretChannelVisibility: Boolean = false,
    ): NotificationEntry {
        val notification = Notification()
        if (secretVisibility) {
            // Developer has marked notification as public
            notification.visibility = Notification.VISIBILITY_SECRET
        }
        val notificationEntry =
            NotificationEntryBuilder().setNotification(notification).setPkg(packageName).build()
        val channel = NotificationChannel("1", "1", NotificationManager.IMPORTANCE_HIGH)
        if (secretChannelVisibility) {
            // User doesn't allow notifications at the channel level
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        notificationEntry.setRanking(
            RankingBuilder(notificationEntry.ranking)
                .setChannel(channel)
                .setVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE)
                .build()
        )
        return notificationEntry
    }
}
