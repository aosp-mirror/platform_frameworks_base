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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
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
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.data.repository.fakeShadeRepository
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
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeInteractorImplTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val configurationRepository = kosmos.fakeConfigurationRepository
    val deviceProvisioningRepository = kosmos.fakeDeviceProvisioningRepository
    val disableFlagsRepository = kosmos.fakeDisableFlagsRepository
    val keyguardRepository = kosmos.fakeKeyguardRepository
    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val powerRepository = kosmos.fakePowerRepository
    val sceneInteractor = kosmos.sceneInteractor
    val shadeRepository = kosmos.fakeShadeRepository
    val userRepository = kosmos.fakeUserRepository
    val userSetupRepository = kosmos.fakeUserSetupRepository
    val dozeParameters = kosmos.dozeParameters

    val underTest = kosmos.shadeInteractorImpl

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
    fun fullShadeExpansionWhenShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.SHADE_LOCKED)
            shadeRepository.setLockscreenShadeExpansion(0.5f)

            assertThat(actual).isEqualTo(1f)
        }

    @Test
    fun fullShadeExpansionWhenStatusBarStateIsNotShadeLocked() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)

            shadeRepository.setLockscreenShadeExpansion(0.5f)
            assertThat(actual).isEqualTo(0.5f)

            shadeRepository.setLockscreenShadeExpansion(0.8f)
            assertThat(actual).isEqualTo(0.8f)
        }

    @Test
    fun shadeExpansionWhenInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(.7f)
            runCurrent()

            // THEN legacy shade expansion is passed through
            assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            shadeRepository.setQsExpansion(.5f)
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun shadeExpansionWhenNotInSplitShadeAndQsCollapsed() =
        testScope.runTest {
            val actual by collectLastValue(underTest.shadeExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyShadeExpansion(.6f)

            // THEN shade expansion is zero
            assertThat(actual).isEqualTo(.6f)
        }

    @Test
    fun anyExpansion_shadeGreater() =
        testScope.runTest() {
            // WHEN shade is more expanded than QS
            shadeRepository.setLegacyShadeExpansion(.5f)
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun anyExpansion_qsGreater() =
        testScope.runTest() {
            // WHEN qs is more expanded than shade
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN anyExpansion is .5f
            assertThat(underTest.anyExpansion.value).isEqualTo(.5f)
        }

    @Test
    fun userInteractingWithShade_shadeDraggedUpAndDown() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed but tracking is not stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged halfway and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(.6f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade completes expansion stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeExpanded() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged down halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully expanded and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadePartiallyExpanded() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade collapsed and not tracking input
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade partially expanded
            shadeRepository.setLegacyShadeExpansion(.4f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN tracking is stopped
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade goes back to collapsed
            shadeRepository.setLegacyShadeExpansion(0f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithShade_shadeCollapsed() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithShade)
            // GIVEN shade expanded and not tracking input
            shadeRepository.setLegacyShadeExpansion(1f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN shade tracking starts
            shadeRepository.setLegacyShadeTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade dragged up halfway
            shadeRepository.setLegacyShadeExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN shade fully collapsed and tracking is stopped
            shadeRepository.setLegacyShadeExpansion(0f)
            shadeRepository.setLegacyShadeTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun userInteractingWithQs_qsDraggedUpAndDown() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isUserInteractingWithQs)
            // GIVEN qs collapsed and not tracking input
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()

            // WHEN qs tracking starts
            shadeRepository.setLegacyQsTracking(true)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged down halfway
            shadeRepository.setQsExpansion(.5f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully expanded but tracking is not stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs fully collapsed but tracking is not stopped
            shadeRepository.setQsExpansion(0f)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs dragged halfway and tracking is stopped
            shadeRepository.setQsExpansion(.6f)
            shadeRepository.setLegacyQsTracking(false)
            runCurrent()

            // THEN user is interacting
            assertThat(actual).isTrue()

            // WHEN qs completes expansion stopped
            shadeRepository.setQsExpansion(1f)
            runCurrent()

            // THEN user is not interacting
            assertThat(actual).isFalse()
        }

    @Test
    fun isShadeTouchable_isFalse_whenFrpIsActive() =
        testScope.runTest {
            deviceProvisioningRepository.setFactoryResetProtectionActive(true)
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                )
            )
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            runCurrent()
            assertThat(isShadeTouchable).isFalse()
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
            runCurrent()
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
            runCurrent()
            assertThat(isShadeTouchable).isTrue()
        }

    @Test
    fun isShadeTouchable_isFalse_whenStartingToSleepAndNotControlScreenOff() =
        testScope.runTest {
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
            whenever(dozeParameters.shouldControlScreenOff()).thenReturn(false)
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            runCurrent()
            assertThat(isShadeTouchable).isFalse()
        }

    @Test
    fun isShadeTouchable_isTrue_whenStartingToSleepAndControlScreenOff() =
        testScope.runTest {
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
            whenever(dozeParameters.shouldControlScreenOff()).thenReturn(true)
            val isShadeTouchable by collectLastValue(underTest.isShadeTouchable)
            runCurrent()
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
            runCurrent()
            assertThat(isShadeTouchable).isTrue()
        }
}
