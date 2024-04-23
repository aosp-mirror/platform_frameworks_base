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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.fakeUserSetupRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class ShadeInteractorImplTest(flags: FlagsParameterization?) : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val deviceProvisioningRepository by lazy { kosmos.fakeDeviceProvisioningRepository }
    val disableFlagsRepository by lazy { kosmos.fakeDisableFlagsRepository }
    val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    val powerRepository by lazy { kosmos.fakePowerRepository }
    val shadeTestUtil by lazy { kosmos.shadeTestUtil }
    val userRepository by lazy { kosmos.fakeUserRepository }
    val userSetupRepository by lazy { kosmos.fakeUserSetupRepository }
    val dozeParameters by lazy { kosmos.dozeParameters }

    lateinit var underTest: ShadeInteractorImpl

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        underTest = kosmos.shadeInteractorImpl
    }

    @Test
    fun isShadeEnabled_matchesDisableFlagsRepo() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isShadeEnabled)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = DISABLE2_NOTIFICATION_SHADE)
            assertThat(actual).isFalse()

            disableFlagsRepository.disableFlags.value = DisableFlagsModel(disable2 = DISABLE2_NONE)

            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_deviceNotProvisioned_false() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(false)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_userNotSetupAndSimpleUserSwitcher_false() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)

            userSetupRepository.setUserSetUp(false)
            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = true))

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_shadeNotEnabled_false() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            userSetupRepository.setUserSetUp(true)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NOTIFICATION_SHADE,
                )

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_quickSettingsNotEnabled_false() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            userSetupRepository.setUserSetUp(true)

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_QUICK_SETTINGS,
                )
            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_dozing_false() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            userSetupRepository.setUserSetUp(true)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            keyguardRepository.setIsDozing(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isFalse()
        }

    @Test
    fun isExpandToQsEnabled_userSetup_true() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            userSetupRepository.setUserSetUp(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_notSimpleUserSwitcher_true() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )

            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = false))

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToDozingUpdates() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetUp(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN dozing starts
            keyguardRepository.setIsDozing(true)

            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN dozing stops
            keyguardRepository.setIsDozing(false)

            // THEN expand is enabled
            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToDisableUpdates() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetUp(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN QS is disabled
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_QUICK_SETTINGS,
                )
            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN QS is enabled
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            // THEN expand is enabled
            assertThat(actual).isTrue()
        }

    @Test
    fun isExpandToQsEnabled_respondsToUserUpdates() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            keyguardRepository.setIsDozing(false)
            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 = DISABLE2_NONE,
                )
            userSetupRepository.setUserSetUp(true)

            val actual by collectLastValue(underTest.isExpandToQsEnabled)

            assertThat(actual).isTrue()

            // WHEN the user is no longer setup
            userSetupRepository.setUserSetUp(false)
            userRepository.setSettings(UserSwitcherSettingsModel(isSimpleUserSwitcher = true))

            // THEN expand is disabled
            assertThat(actual).isFalse()

            // WHEN the user is setup again
            userSetupRepository.setUserSetUp(true)

            // THEN expand is enabled
            assertThat(actual).isTrue()
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsCollapsed() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeTestUtil.setShadeAndQsExpansion(.6f, 0f)

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(.6f)
        }

    @Test
    fun anyExpansion_shadeGreater() =
        testScope.runTest() {
            // WHEN shade is more expanded than QS
            shadeTestUtil.setShadeAndQsExpansion(.5f, 0f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun anyExpansion_qsGreater() =
        testScope.runTest() {
            // WHEN qs is more expanded than shade
            shadeTestUtil.setShadeAndQsExpansion(0f, .5f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun isShadeTouchable_isFalse_whenDeviceAsleepAndNotPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            // goingToSleep == false
            // TODO: remove?
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_AOD,
                )
            )
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            assertThat(isShadeTouchable).isFalse()
        }

    @Test
    fun isShadeTouchable_isTrue_whenDeviceAsleepAndPulsing() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            // goingToSleep == false
            // TODO: remove?
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    to = DozeStateModel.DOZE_PULSING,
                )
            )
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            assertThat(isShadeTouchable).isTrue()
        }

    @Test
    fun isShadeTouchable_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        testScope.runTest {
            whenever(dozeParameters.shouldControlScreenOff()).thenReturn(false)
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            // Assert the default condition is true
            assertThat(isShadeTouchable).isTrue()

            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            // goingToSleep == true
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            assertThat(isShadeTouchable).isFalse()
        }

    @Test
    fun isShadeTouchable_isTrue_whenStartingToSleepAndControlScreenOff() =
        testScope.runTest {
            whenever(dozeParameters.shouldControlScreenOff()).thenReturn(true)
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            // Assert the default condition is true
            assertThat(isShadeTouchable).isTrue()

            powerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            // goingToSleep == true
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GONE,
                    to = KeyguardState.AOD,
                    transitionState = TransitionState.STARTED,
                )
            )
            assertThat(isShadeTouchable).isTrue()
        }

    @Test
    fun isShadeTouchable_isTrue_whenNotAsleep() =
        testScope.runTest {
            powerRepository.updateWakefulness(
                rawState = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.OTHER,
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            assertThat(isShadeTouchable).isTrue()
        }
}
