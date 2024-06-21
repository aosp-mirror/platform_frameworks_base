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

package com.android.systemui.statusbar.policy.domain.interactor

import android.app.NotificationManager.Policy
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.runCurrent
import com.android.systemui.runTest
import com.android.systemui.statusbar.policy.data.repository.FakeZenModeRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ZenModeInteractorTest : SysuiTestCase() {
    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<ZenModeInteractor> {

        val repository: FakeZenModeRepository

        @Component.Factory
        interface Factory {
            fun create(@BindsInstance test: SysuiTestCase): TestComponent
        }
    }

    private val testComponent: TestComponent =
        DaggerZenModeInteractorTest_TestComponent.factory().create(test = this)

    @Test
    fun testIsZenModeEnabled_off() =
        testComponent.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.zenMode.value = Settings.Global.ZEN_MODE_OFF
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun testIsZenModeEnabled_alarms() =
        testComponent.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.zenMode.value = Settings.Global.ZEN_MODE_ALARMS
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_importantInterruptions() =
        testComponent.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_noInterruptions() =
        testComponent.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.zenMode.value = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_unknown() =
        testComponent.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            repository.zenMode.value = 4 // this should fail if we ever add another zen mode type
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun testAreNotificationsHiddenInShade_noPolicy() =
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.consolidatedNotificationPolicy.value = null
            repository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun testAreNotificationsHiddenInShade_zenOffShadeSuppressed() =
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            repository.zenMode.value = Settings.Global.ZEN_MODE_OFF
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun testAreNotificationsHiddenInShade_zenOnShadeNotSuppressed() =
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_STATUS_BAR)
            repository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun testAreNotificationsHiddenInShade_zenOnShadeSuppressed() =
        testComponent.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            repository.setSuppressedVisualEffects(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)
            repository.zenMode.value = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
            runCurrent()

            assertThat(hidden).isTrue()
        }
}
