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
import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.provider.Settings.Secure.ZEN_DURATION
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import android.service.notification.SystemZenRules
import android.service.notification.ZenPolicy
import android.service.notification.ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.settingslib.notification.data.repository.updateNotificationPolicy
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.settingslib.notification.modes.TestModeBuilder.MANUAL_DND
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shared.settings.data.repository.secureSettingsRepository
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ZenModeInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val zenModeRepository = kosmos.fakeZenModeRepository
    private val settingsRepository = kosmos.secureSettingsRepository
    private val deviceProvisioningRepository = kosmos.fakeDeviceProvisioningRepository

    private val underTest = kosmos.zenModeInteractor

    @Test
    fun isZenAvailable_off() =
        kosmos.runTest {
            val isZenAvailable by collectLastValue(underTest.isZenAvailable)
            deviceProvisioningRepository.setDeviceProvisioned(false)
            assertThat(isZenAvailable).isFalse()
        }

    @Test
    fun isZenAvailable_on() =
        kosmos.runTest {
            val isZenAvailable by collectLastValue(underTest.isZenAvailable)
            deviceProvisioningRepository.setDeviceProvisioned(true)
            assertThat(isZenAvailable).isTrue()
        }

    @Test
    fun isZenModeEnabled_off() =
        kosmos.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)
            assertThat(enabled).isFalse()
        }

    @Test
    fun isZenModeEnabled_alarms() =
        kosmos.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_ALARMS)
            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_importantInterruptions() =
        kosmos.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            assertThat(enabled).isTrue()
        }

    @Test
    fun isZenModeEnabled_noInterruptions() =
        kosmos.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
            assertThat(enabled).isTrue()
        }

    @Test
    fun testIsZenModeEnabled_unknown() =
        kosmos.runTest {
            val enabled by collectLastValue(underTest.isZenModeEnabled)
            // this should fail if we ever add another zen mode type
            zenModeRepository.updateZenMode(4)
            assertThat(enabled).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_noPolicy() =
        kosmos.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(null)
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOffShadeSuppressed() =
        kosmos.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_OFF)

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeNotSuppressed() =
        kosmos.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_STATUS_BAR
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)

            assertThat(hidden).isFalse()
        }

    @Test
    fun areNotificationsHiddenInShade_zenOnShadeSuppressed() =
        kosmos.runTest {
            val hidden by collectLastValue(underTest.areNotificationsHiddenInShade)

            zenModeRepository.updateNotificationPolicy(
                suppressedVisualEffects = Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
            )
            zenModeRepository.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS)

            assertThat(hidden).isTrue()
        }

    @Test
    fun shouldAskForZenDuration_falseForNonManualDnd() =
        kosmos.runTest {
            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_PROMPT)
            assertThat(underTest.shouldAskForZenDuration(TestModeBuilder.EXAMPLE)).isFalse()
        }

    @Test
    fun shouldAskForZenDuration_changesWithSetting() =
        kosmos.runTest {
            val manualDnd = TestModeBuilder().makeManualDnd().setActive(true).build()

            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_FOREVER)
            assertThat(underTest.shouldAskForZenDuration(manualDnd)).isFalse()

            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_PROMPT)
            assertThat(underTest.shouldAskForZenDuration(manualDnd)).isTrue()
        }

    @Test
    fun activateMode_nonManualDnd() =
        kosmos.runTest {
            val mode = TestModeBuilder().setActive(false).build()
            zenModeRepository.addModes(listOf(mode))
            settingsRepository.setInt(ZEN_DURATION, 60)

            underTest.activateMode(mode)
            assertThat(zenModeRepository.getMode(mode.id)?.isActive).isTrue()
            assertThat(zenModeRepository.getModeActiveDuration(mode.id)).isNull()
        }

    @Test
    fun activateMode_usesCorrectDuration() =
        kosmos.runTest {
            settingsRepository.setInt(ZEN_DURATION, ZEN_DURATION_FOREVER)

            underTest.activateMode(MANUAL_DND)
            assertThat(zenModeRepository.getModeActiveDuration(MANUAL_DND.id)).isNull()

            zenModeRepository.deactivateMode(MANUAL_DND)
            settingsRepository.setInt(ZEN_DURATION, 60)

            underTest.activateMode(MANUAL_DND)
            assertThat(zenModeRepository.getModeActiveDuration(MANUAL_DND.id))
                .isEqualTo(Duration.ofMinutes(60))
        }

    @Test
    fun deactivateAllModes_updatesCorrectModes() =
        kosmos.runTest {
            zenModeRepository.activateMode(MANUAL_DND)
            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder().setName("Inactive").setActive(false).build(),
                    TestModeBuilder().setName("Active").setActive(true).build(),
                )
            )

            underTest.deactivateAllModes()

            assertThat(zenModeRepository.getModes().filter { it.isActive }).isEmpty()
        }

    @Test
    fun activeModes_computesMainActiveMode() =
        kosmos.runTest {
            val activeModes by collectLastValue(underTest.activeModes)

            zenModeRepository.addMode(id = "Bedtime", type = AutomaticZenRule.TYPE_BEDTIME)
            zenModeRepository.addMode(id = "Other", type = AutomaticZenRule.TYPE_OTHER)
            assertThat(activeModes?.modeNames).hasSize(0)
            assertThat(activeModes?.mainMode).isNull()

            zenModeRepository.activateMode("Other")
            assertThat(activeModes?.modeNames).containsExactly("Mode Other")
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Other")

            zenModeRepository.activateMode("Bedtime")
            assertThat(activeModes?.modeNames)
                .containsExactly("Mode Bedtime", "Mode Other")
                .inOrder()
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Bedtime")

            zenModeRepository.deactivateMode("Other")
            assertThat(activeModes?.modeNames).containsExactly("Mode Bedtime")
            assertThat(activeModes?.mainMode?.name).isEqualTo("Mode Bedtime")

            zenModeRepository.deactivateMode("Bedtime")
            assertThat(activeModes?.modeNames).hasSize(0)
            assertThat(activeModes?.mainMode).isNull()
        }

    @Test
    fun getActiveModes_computesMainActiveMode() =
        kosmos.runTest {
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
            assertThat(activeModes.modeNames)
                .containsExactly("Mode Bedtime", "Mode Other")
                .inOrder()
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
        kosmos.runTest {
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
            assertThat(mainActiveMode).isNull()

            zenModeRepository.activateMode("Other")
            assertThat(mainActiveMode?.name).isEqualTo("Mode Other")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_other)

            zenModeRepository.activateMode("Bedtime")
            assertThat(mainActiveMode?.name).isEqualTo("Mode Bedtime")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            zenModeRepository.deactivateMode("Other")
            assertThat(mainActiveMode?.name).isEqualTo("Mode Bedtime")
            assertThat(mainActiveMode?.icon?.key?.resId)
                .isEqualTo(R.drawable.ic_zen_mode_type_bedtime)

            zenModeRepository.deactivateMode("Bedtime")
            assertThat(mainActiveMode).isNull()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun dndMode_flows() =
        kosmos.runTest {
            val dndMode by collectLastValue(underTest.dndMode)
            assertThat(dndMode!!.isActive).isFalse()

            zenModeRepository.activateMode(MANUAL_DND)
            assertThat(dndMode!!.isActive).isTrue()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun activeModesBlockingMedia_hasModesWithPolicyBlockingMedia() =
        kosmos.runTest {
            val blockingMedia by
                collectLastValue(
                    underTest.activeModesBlockingStream(AudioStream(AudioManager.STREAM_MUSIC))
                )

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Blocks media, Not active")
                        .setZenPolicy(ZenPolicy.Builder().allowMedia(false).build())
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Allows media, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowMedia(true).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks media, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowMedia(false).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks media, Active Too")
                        .setZenPolicy(ZenPolicy.Builder().allowMedia(false).build())
                        .setActive(true)
                        .build(),
                )
            )

            assertThat(blockingMedia!!.mainMode!!.name).isEqualTo("Blocks media, Active")
            assertThat(blockingMedia!!.modeNames)
                .containsExactly("Blocks media, Active", "Blocks media, Active Too")
                .inOrder()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun activeModesBlockingAlarms_hasModesWithPolicyBlockingAlarms() =
        kosmos.runTest {
            val blockingAlarms by
                collectLastValue(
                    underTest.activeModesBlockingStream(AudioStream(AudioManager.STREAM_ALARM))
                )

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Blocks alarms, Not active")
                        .setZenPolicy(ZenPolicy.Builder().allowAlarms(false).build())
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Allows alarms, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowAlarms(true).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks alarms, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowAlarms(false).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks alarms, Active Too")
                        .setZenPolicy(ZenPolicy.Builder().allowAlarms(false).build())
                        .setActive(true)
                        .build(),
                )
            )

            assertThat(blockingAlarms!!.mainMode!!.name).isEqualTo("Blocks alarms, Active")
            assertThat(blockingAlarms!!.modeNames)
                .containsExactly("Blocks alarms, Active", "Blocks alarms, Active Too")
                .inOrder()
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun activeModesBlockingAlarms_hasModesWithPolicyBlockingSystem() =
        kosmos.runTest {
            val blockingSystem by
                collectLastValue(
                    underTest.activeModesBlockingStream(AudioStream(AudioManager.STREAM_SYSTEM))
                )

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Blocks system, Not active")
                        .setZenPolicy(ZenPolicy.Builder().allowSystem(false).build())
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Allows system, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowSystem(true).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks system, Active")
                        .setZenPolicy(ZenPolicy.Builder().allowSystem(false).build())
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Blocks system, Active Too")
                        .setZenPolicy(ZenPolicy.Builder().allowSystem(false).build())
                        .setActive(true)
                        .build(),
                )
            )

            assertThat(blockingSystem!!.mainMode!!.name).isEqualTo("Blocks system, Active")
            assertThat(blockingSystem!!.modeNames)
                .containsExactly("Blocks system, Active", "Blocks system, Active Too")
                .inOrder()
        }

    @Test
    @EnableFlags(ModesEmptyShadeFix.FLAG_NAME, Flags.FLAG_MODES_UI, Flags.FLAG_MODES_API)
    fun modesHidingNotifications_onlyIncludesModesWithNotifListSuppression() =
        kosmos.runTest {
            val modesHidingNotifications by collectLastValue(underTest.modesHidingNotifications)

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Not active, no list suppression")
                        .setActive(false)
                        .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ true)
                        .build(),
                    TestModeBuilder()
                        .setName("Not active, has list suppression")
                        .setActive(false)
                        .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                        .build(),
                    TestModeBuilder()
                        .setName("No list suppression")
                        .setActive(true)
                        .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ true)
                        .build(),
                    TestModeBuilder()
                        .setName("Has list suppression 1")
                        .setActive(true)
                        .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                        .build(),
                    TestModeBuilder()
                        .setName("Has list suppression 2")
                        .setActive(true)
                        .setVisualEffect(VISUAL_EFFECT_NOTIFICATION_LIST, /* allowed= */ false)
                        .build(),
                )
            )

            assertThat(modesHidingNotifications?.map { it.name })
                .containsExactly("Has list suppression 1", "Has list suppression 2")
                .inOrder()
        }
}
