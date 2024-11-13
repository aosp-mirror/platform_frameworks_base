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

import android.app.AutomaticZenRule
import android.app.Flags
import android.app.NotificationManager.Policy
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.provider.Settings.Secure.ZEN_DURATION
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import android.service.notification.SystemZenRules
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.settingslib.notification.data.repository.updateNotificationPolicy
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.settings.data.repository.secureSettingsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class ZenModeInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val zenModeRepository = kosmos.fakeZenModeRepository
    private val settingsRepository = kosmos.secureSettingsRepository
    private val deviceProvisioningRepository = kosmos.fakeDeviceProvisioningRepository

    private val underTest = kosmos.zenModeInteractor

    @Test
    fun isZenAvailable_off() =
        testScope.runTest {
            val isZenAvailable by collectLastValue(underTest.isZenAvailable)
            deviceProvisioningRepository.setDeviceProvisioned(false)
            runCurrent()

            assertThat(isZenAvailable).isFalse()
        }

    @Test
    fun isZenAvailable_on() =
        testScope.runTest {
            val isZenAvailable by collectLastValue(underTest.isZenAvailable)
            deviceProvisioningRepository.setDeviceProvisioned(true)
            runCurrent()

            assertThat(isZenAvailable).isTrue()
        }

    @Test
    fun isZenModeEnabled_off() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun isZenModeEnabled_alarms() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_ALARMS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_importantInterruptions() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_noInterruptions() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
            runCurrent()

            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_unknown() =
        testScope.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)

            // this should fail if we ever add another zen mode type
            zenModeRepository.updateZenMode(4)
            runCurrent()

            assertThat(enabled).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_noPolicy() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(null)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOffShadeSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeNotSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_STATUS_BAR
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeSuppressed() =
        testScope.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            runCurrent()

            assertThat(hidden).isTrue()
        }

    @Test
    fun shouldAskForZenDuration_falseForNonManualDnd() =
        testScope.runTest {
            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_PROMPT)
            runCurrent()

            assertThat(underTest.shouldAskForZenDuration(TestModeBuilder.EXAMPLE)).isFalse()
        }

    @Test
    fun shouldAskForZenDuration_changesWithSetting() =
        testScope.runTest {
            val manualDnd = TestModeBuilder.MANUAL_DND_ACTIVE

            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_FOREVER)
            runCurrent()

            assertThat(underTest.shouldAskForZenDuration(manualDnd)).isFalse()

            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_PROMPT)
            runCurrent()

            assertThat(underTest.shouldAskForZenDuration(manualDnd)).isTrue()
        }

    @Test
    fun activateMode_nonManualDnd() =
        testScope.runTest {
            val mode = TestModeBuilder().setActive(false).build()
            zenModeRepository.addModes(listOf(mode))
            settingsRepository.setInt(ZEN_DURATION, 60)
            runCurrent()

            underTest.activateMode(mode)
            assertThat(zenModeRepository.getMode(mode.id)?.isActive).isTrue()
            assertThat(zenModeRepository.getModeActiveDuration(mode.id)).isNull()
        }

    @Test
    fun activateMode_usesCorrectDuration() =
        testScope.runTest {
            val manualDnd = TestModeBuilder.MANUAL_DND_ACTIVE
            zenModeRepository.addModes(listOf(manualDnd))
            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_FOREVER)
            runCurrent()

            underTest.activateMode(manualDnd)
            assertThat(zenModeRepository.getModeActiveDuration(manualDnd.id)).isNull()

            zenModeRepository.deactivateMode(manualDnd.id)
            settingsRepository.setInt(ZEN_DURATION, 60)
            runCurrent()

            underTest.activateMode(manualDnd)
            assertThat(zenModeRepository.getModeActiveDuration(manualDnd.id))
                .isEqualTo(Duration.ofMinutes(60))
        }

    @Test
    fun activeModes_computesMainActiveMode() =
        testScope.runTest {
            val activeModes by collectLastValue(underTest.activeModes)

            zenModeRepository.addMode(id = "Bedtime", type = AutomaticZenRule.TYPE_BEDTIME)
            zenModeRepository.addMode(id = "Other", type = AutomaticZenRule.TYPE_OTHER)

            runCurrent()
            assertThat(activeModes?.modeNames).hasSize(0)
            assertThat(activeModes?.mainMode).isNull()

            zenModeRepository.activateMode("Other")
            runCurrent()
            assertThat(activeModes?.modeNames).containsExactly("Mode Other")
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Other")

            zenModeRepository.activateMode("Bedtime")
            runCurrent()
            assertThat(activeModes?.modeNames)
                .containsExactly("Mode Bedtime", "Mode Other")
                .inOrder()
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Bedtime")

            zenModeRepository.deactivateMode("Other")
            runCurrent()
            assertThat(activeModes?.modeNames).containsExactly("Mode Bedtime")
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Bedtime")

            zenModeRepository.deactivateMode("Bedtime")
            runCurrent()
            assertThat(activeModes?.modeNames).hasSize(0)
            assertThat(activeModes?.mainMode).isNull()
        }

    @Test
    fun getActiveModes_computesMainActiveMode() = runTest {
        zenModeRepository.addMode(id = "Bedtime", type = AutomaticZenRule.TYPE_BEDTIME)
        zenModeRepository.addMode(id = "Other", type = AutomaticZenRule.TYPE_OTHER)

        var activeModes = underTest.getActiveModes()
        assertThat(activeModes.modeNames).hasSize(0)
        assertThat(activeModes.mainMode).isNull()

        zenModeRepository.activateMode("Other")
        activeModes = underTest.getActiveModes()
        assertThat(activeModes.modeNames).containsExactly("Mode Other")
        assertThat(activeModes.mainMode?.name).isEqualTo("Mode Other")

        zenModeRepository.activateMode("Bedtime")
        activeModes = underTest.getActiveModes()
        assertThat(activeModes.modeNames).containsExactly("Mode Bedtime", "Mode Other").inOrder()
        assertThat(activeModes.mainMode?.name).isEqualTo("Mode Bedtime")

        zenModeRepository.deactivateMode("Other")
        activeModes = underTest.getActiveModes()
        assertThat(activeModes.modeNames).containsExactly("Mode Bedtime")
        assertThat(activeModes.mainMode?.name).isEqualTo("Mode Bedtime")

        zenModeRepository.deactivateMode("Bedtime")
        activeModes = underTest.getActiveModes()
        assertThat(activeModes.modeNames).hasSize(0)
        assertThat(activeModes.mainMode).isNull()
    }

    @Test
    fun mainActiveMode_flows() =
        testScope.runTest {
            val mainActiveMode by collectLastValue(underTest.mainActiveMode)

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId("Bedtime")
                        .setName("Mode Bedtime")
                        .setType(AutomaticZenRule.TYPE_BEDTIME)
                        .setActive(false)
                        .setPackage(mContext.packageName)
                        .setIconResId(R.drawable.ic_zen_mode_type_bedtime)
                        .build(),
                    TestModeBuilder()
                        .setId("Other")
                        .setName("Mode Other")
                        .setType(AutomaticZenRule.TYPE_OTHER)
                        .setActive(false)
                        .setPackage(SystemZenRules.PACKAGE_ANDROID)
                        .setIconResId(R.drawable.ic_zen_mode_type_other)
                        .build(),
                )
            )

            runCurrent()
            assertThat(mainActiveMode).isNull()

            zenModeRepository.activateMode("Other")
            runCurrent()
            assertThat(mainActiveMode?.name).isEqualTo("Mode Other")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_other)

            zenModeRepository.activateMode("Bedtime")
            runCurrent()
            assertThat(mainActiveMode?.name).isEqualTo("Mode Bedtime")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            zenModeRepository.deactivateMode("Other")
            runCurrent()
            assertThat(mainActiveMode?.name).isEqualTo("Mode Bedtime")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            zenModeRepository.deactivateMode("Bedtime")
            runCurrent()
            assertThat(mainActiveMode).isNull()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun dndMode_flows() =
        testScope.runTest {
            val dndMode by collectLastValue(underTest.dndMode)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            runCurrent()

            assertThat(dndMode!!.isActive).isFalse()

            zenModeRepository.removeMode(TestModeBuilder.MANUAL_DND_INACTIVE.id)
            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_ACTIVE)
            runCurrent()

            assertThat(dndMode!!.isActive).isTrue()
        }
}
