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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.shelf.ui.viewmodel

import android.os.PowerManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeAccessibilityRepository
import com.android.systemui.accessibility.domain.interactor.AccessibilityInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModel
import com.android.systemui.statusbar.notification.shelf.domain.interactor.NotificationShelfInteractor
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationShelfViewModelTest : SysuiTestCase() {

    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    // mocks
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var keyguardTransitionController: LockscreenShadeTransitionController

    // fakes
    private val keyguardRepository = FakeKeyguardRepository()
    private val deviceEntryFaceAuthRepository = FakeDeviceEntryFaceAuthRepository()
    private val systemClock = FakeSystemClock()
    private val a11yRepo = FakeAccessibilityRepository()

    // real impls
    private val a11yInteractor = AccessibilityInteractor(a11yRepo)
    private val activatableViewModel = ActivatableNotificationViewModel(a11yInteractor)
    private val interactor by lazy {
        NotificationShelfInteractor(
            keyguardRepository,
            deviceEntryFaceAuthRepository,
            centralSurfaces,
            systemClock,
            keyguardTransitionController,
        )
    }
    private val underTest by lazy { NotificationShelfViewModel(interactor, activatableViewModel) }

    @Test
    fun canModifyColorOfNotifications_whenKeyguardNotShowing() = runTest {
        val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

        keyguardRepository.setKeyguardShowing(false)

        assertThat(canModifyNotifColor).isTrue()
    }

    @Test
    fun canModifyColorOfNotifications_whenKeyguardShowingAndNotBypass() = runTest {
        val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

        keyguardRepository.setKeyguardShowing(true)
        deviceEntryFaceAuthRepository.isBypassEnabled.value = false

        assertThat(canModifyNotifColor).isTrue()
    }

    @Test
    fun cannotModifyColorOfNotifications_whenBypass() = runTest {
        val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

        keyguardRepository.setKeyguardShowing(true)
        deviceEntryFaceAuthRepository.isBypassEnabled.value = true

        assertThat(canModifyNotifColor).isFalse()
    }

    @Test
    fun isClickable_whenKeyguardShowing() = runTest {
        val isClickable by collectLastValue(underTest.isClickable)

        keyguardRepository.setKeyguardShowing(true)

        assertThat(isClickable).isTrue()
    }

    @Test
    fun isNotClickable_whenKeyguardNotShowing() = runTest {
        val isClickable by collectLastValue(underTest.isClickable)

        keyguardRepository.setKeyguardShowing(false)

        assertThat(isClickable).isFalse()
    }

    @Test
    fun onClicked_goesToLockedShade() {
        underTest.onShelfClicked()

        verify(centralSurfaces)
            .wakeUpIfDozing(ArgumentMatchers.anyLong(), any(), eq(PowerManager.WAKE_REASON_GESTURE))
        verify(keyguardTransitionController).goToLockedShade(Mockito.isNull(), eq(true))
    }
}
