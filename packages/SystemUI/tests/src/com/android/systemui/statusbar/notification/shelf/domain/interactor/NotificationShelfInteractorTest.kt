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

package com.android.systemui.statusbar.notification.shelf.domain.interactor

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.isNull
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class NotificationShelfInteractorTest : SysuiTestCase() {

    private val keyguardRepository = FakeKeyguardRepository()
    private val deviceEntryFaceAuthRepository = FakeDeviceEntryFaceAuthRepository()

    private val screenOffAnimationController =
        mock<ScreenOffAnimationController>().also {
            whenever(it.allowWakeUpIfDozing()).thenReturn(true)
        }
    private val statusBarStateController: StatusBarStateController = mock()
    private val powerRepository = FakePowerRepository()
    private val powerInteractor =
        PowerInteractorFactory.create(
                repository = powerRepository,
                screenOffAnimationController = screenOffAnimationController,
                statusBarStateController = statusBarStateController,
            )
            .powerInteractor

    private val keyguardTransitionController: LockscreenShadeTransitionController = mock()
    private val underTest =
        NotificationShelfInteractor(
            keyguardRepository,
            deviceEntryFaceAuthRepository,
            powerInteractor,
            keyguardTransitionController,
        )

    @Test
    fun shelfIsNotStatic_whenKeyguardNotShowing() = runTest {
        val shelfStatic by collectLastValue(underTest.isShelfStatic)

        keyguardRepository.setKeyguardShowing(false)

        assertThat(shelfStatic).isFalse()
    }

    @Test
    fun shelfIsNotStatic_whenKeyguardShowingAndNotBypass() = runTest {
        val shelfStatic by collectLastValue(underTest.isShelfStatic)

        keyguardRepository.setKeyguardShowing(true)
        deviceEntryFaceAuthRepository.isBypassEnabled.value = false

        assertThat(shelfStatic).isFalse()
    }

    @Test
    fun shelfIsStatic_whenBypass() = runTest {
        val shelfStatic by collectLastValue(underTest.isShelfStatic)

        keyguardRepository.setKeyguardShowing(true)
        deviceEntryFaceAuthRepository.isBypassEnabled.value = true

        assertThat(shelfStatic).isTrue()
    }

    @Test
    fun shelfOnKeyguard_whenKeyguardShowing() = runTest {
        val onKeyguard by collectLastValue(underTest.isShowingOnKeyguard)

        keyguardRepository.setKeyguardShowing(true)

        assertThat(onKeyguard).isTrue()
    }

    @Test
    fun shelfNotOnKeyguard_whenKeyguardNotShowing() = runTest {
        val onKeyguard by collectLastValue(underTest.isShowingOnKeyguard)

        keyguardRepository.setKeyguardShowing(false)

        assertThat(onKeyguard).isFalse()
    }

    @Test
    fun goToLockedShadeFromShelf_wakesUpFromDoze() {
        whenever(statusBarStateController.isDozing).thenReturn(true)

        underTest.goToLockedShadeFromShelf()

        assertThat(powerRepository.lastWakeReason).isNotNull()
        assertThat(powerRepository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_GESTURE)
    }

    @Test
    fun goToLockedShadeFromShelf_invokesKeyguardTransitionController() {
        underTest.goToLockedShadeFromShelf()

        verify(keyguardTransitionController).goToLockedShade(isNull(), eq(true))
    }
}
