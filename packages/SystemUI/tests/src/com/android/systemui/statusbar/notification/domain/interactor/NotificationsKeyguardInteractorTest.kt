/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.statusbar.notification.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.statusbar.notification.data.repository.FakeNotificationsKeyguardViewStateRepository
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test

@SmallTest
class NotificationsKeyguardInteractorTest : SysuiTestCase() {

    @SysUISingleton
    @Component(modules = [SysUITestModule::class])
    interface TestComponent : SysUITestComponent<NotificationsKeyguardInteractor> {

        val repository: FakeNotificationsKeyguardViewStateRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerNotificationsKeyguardInteractorTest_TestComponent.factory().create(test = this)

    @Test
    fun areNotifsFullyHidden_reflectsRepository() =
        testComponent.runTest {
            repository.setNotificationsFullyHidden(false)
            val notifsFullyHidden by collectLastValue(underTest.areNotificationsFullyHidden)
            runCurrent()

            assertThat(notifsFullyHidden).isFalse()

            repository.setNotificationsFullyHidden(true)
            runCurrent()

            assertThat(notifsFullyHidden).isTrue()
        }

    @Test
    fun isPulseExpanding_reflectsRepository() =
        testComponent.runTest {
            repository.setPulseExpanding(false)
            val isPulseExpanding by collectLastValue(underTest.isPulseExpanding)
            runCurrent()

            assertThat(isPulseExpanding).isFalse()

            repository.setPulseExpanding(true)
            runCurrent()

            assertThat(isPulseExpanding).isTrue()
        }
}
