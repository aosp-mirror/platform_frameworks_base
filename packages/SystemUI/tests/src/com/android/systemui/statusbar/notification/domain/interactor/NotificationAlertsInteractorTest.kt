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

import android.app.StatusBarManager
import androidx.test.filters.SmallTest
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test

@SmallTest
class NotificationAlertsInteractorTest : SysuiTestCase() {

    @Component(modules = [SysUITestModule::class])
    @SysUISingleton
    interface TestComponent : SysUITestComponent<NotificationAlertsInteractor> {
        val disableFlags: FakeDisableFlagsRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerNotificationAlertsInteractorTest_TestComponent.factory().create(test = this)

    @Test
    fun disableFlags_notifAlertsNotDisabled_notifAlertsEnabledTrue() =
        with(testComponent) {
            disableFlags.disableFlags.value =
                DisableFlagsModel(
                    StatusBarManager.DISABLE_NONE,
                    StatusBarManager.DISABLE2_NONE,
                )
            assertThat(underTest.areNotificationAlertsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_notifAlertsDisabled_notifAlertsEnabledFalse() =
        with(testComponent) {
            disableFlags.disableFlags.value =
                DisableFlagsModel(
                    StatusBarManager.DISABLE_NOTIFICATION_ALERTS,
                    StatusBarManager.DISABLE2_NONE,
                )
            assertThat(underTest.areNotificationAlertsEnabled()).isFalse()
        }
}
