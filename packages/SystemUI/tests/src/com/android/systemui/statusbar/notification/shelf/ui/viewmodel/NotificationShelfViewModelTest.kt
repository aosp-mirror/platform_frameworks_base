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

package com.android.systemui.statusbar.notification.shelf.ui.viewmodel

import android.os.PowerManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.runTest
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModelModule
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationShelfViewModelTest : SysuiTestCase() {

    @Component(modules = [SysUITestModule::class, ActivatableNotificationViewModelModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<NotificationShelfViewModel> {

        val deviceEntryFaceAuthRepository: FakeDeviceEntryFaceAuthRepository
        val keyguardRepository: FakeKeyguardRepository
        val powerRepository: FakePowerRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                mocks: TestMocksModule,
            ): TestComponent
        }
    }

    private val keyguardTransitionController: LockscreenShadeTransitionController = mock()
    private val screenOffAnimationController: ScreenOffAnimationController = mock {
        whenever(allowWakeUpIfDozing()).thenReturn(true)
    }
    private val statusBarStateController: SysuiStatusBarStateController = mock()

    private val testComponent: TestComponent =
        DaggerNotificationShelfViewModelTest_TestComponent.factory()
            .create(
                test = this,
                mocks =
                    TestMocksModule(
                        lockscreenShadeTransitionController = keyguardTransitionController,
                        screenOffAnimationController = screenOffAnimationController,
                        statusBarStateController = statusBarStateController,
                    )
            )

    @Test
    fun canModifyColorOfNotifications_whenKeyguardNotShowing() =
        testComponent.runTest {
            val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

            keyguardRepository.setKeyguardShowing(false)

            assertThat(canModifyNotifColor).isTrue()
        }

    @Test
    fun canModifyColorOfNotifications_whenKeyguardShowingAndNotBypass() =
        testComponent.runTest {
            val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

            keyguardRepository.setKeyguardShowing(true)
            deviceEntryFaceAuthRepository.isBypassEnabled.value = false

            assertThat(canModifyNotifColor).isTrue()
        }

    @Test
    fun cannotModifyColorOfNotifications_whenBypass() =
        testComponent.runTest {
            val canModifyNotifColor by collectLastValue(underTest.canModifyColorOfNotifications)

            keyguardRepository.setKeyguardShowing(true)
            deviceEntryFaceAuthRepository.isBypassEnabled.value = true

            assertThat(canModifyNotifColor).isFalse()
        }

    @Test
    fun isClickable_whenKeyguardShowing() =
        testComponent.runTest {
            val isClickable by collectLastValue(underTest.isClickable)

            keyguardRepository.setKeyguardShowing(true)

            assertThat(isClickable).isTrue()
        }

    @Test
    fun isNotClickable_whenKeyguardNotShowing() =
        testComponent.runTest {
            val isClickable by collectLastValue(underTest.isClickable)

            keyguardRepository.setKeyguardShowing(false)

            assertThat(isClickable).isFalse()
        }

    @Test
    fun onClicked_goesToLockedShade() =
        with(testComponent) {
            whenever(statusBarStateController.isDozing).thenReturn(true)

            underTest.onShelfClicked()

            assertThat(powerRepository.lastWakeReason).isNotNull()
            assertThat(powerRepository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_GESTURE)
            verify(keyguardTransitionController).goToLockedShade(Mockito.isNull(), eq(true))
        }
}
