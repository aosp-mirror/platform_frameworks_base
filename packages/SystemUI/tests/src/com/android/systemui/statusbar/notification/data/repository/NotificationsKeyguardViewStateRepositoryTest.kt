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

package com.android.systemui.statusbar.notification.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
class NotificationsKeyguardViewStateRepositoryTest : SysuiTestCase() {

    @SysUISingleton
    @Component(modules = [SysUITestModule::class])
    interface TestComponent : SysUITestComponent<NotificationsKeyguardViewStateRepositoryImpl> {

        val mockWakeUpCoordinator: NotificationWakeUpCoordinator

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
            ): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerNotificationsKeyguardViewStateRepositoryTest_TestComponent.factory()
            .create(test = this)

    @Test
    fun areNotifsFullyHidden_reflectsWakeUpCoordinator() =
        testComponent.runTest {
            whenever(mockWakeUpCoordinator.notificationsFullyHidden).thenReturn(false)
            val notifsFullyHidden by collectLastValue(underTest.areNotificationsFullyHidden)
            runCurrent()

            assertThat(notifsFullyHidden).isFalse()

            withArgCaptor { verify(mockWakeUpCoordinator).addListener(capture()) }
                .onFullyHiddenChanged(true)
            runCurrent()

            assertThat(notifsFullyHidden).isTrue()
        }

    @Test
    fun isPulseExpanding_reflectsWakeUpCoordinator() =
        testComponent.runTest {
            whenever(mockWakeUpCoordinator.isPulseExpanding()).thenReturn(false)
            val isPulseExpanding by collectLastValue(underTest.isPulseExpanding)
            runCurrent()

            assertThat(isPulseExpanding).isFalse()

            withArgCaptor { verify(mockWakeUpCoordinator).addListener(capture()) }
                .onPulseExpansionChanged(true)
            runCurrent()

            assertThat(isPulseExpanding).isTrue()
        }
}
