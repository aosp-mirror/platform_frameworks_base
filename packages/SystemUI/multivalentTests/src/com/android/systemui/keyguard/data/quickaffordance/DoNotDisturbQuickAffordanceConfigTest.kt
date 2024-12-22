/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.android.systemui.keyguard.data.quickaffordance

import android.app.Flags
import android.net.Uri
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.provider.Settings.Secure.ZEN_DURATION_FOREVER
import android.provider.Settings.Secure.ZEN_DURATION_PROMPT
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.EnableZenModeDialog
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.settings.data.repository.secureSettingsRepository
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DoNotDisturbQuickAffordanceConfigTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testDispatcher = kosmos.testDispatcher
    private val testScope = kosmos.testScope

    private val settings = kosmos.fakeSettings

    private val zenModeRepository = kosmos.fakeZenModeRepository
    private val deviceProvisioningRepository = kosmos.fakeDeviceProvisioningRepository
    private val secureSettingsRepository = kosmos.secureSettingsRepository

    @Mock private lateinit var zenModeController: ZenModeController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var conditionUri: Uri
    @Mock private lateinit var enableZenModeDialog: EnableZenModeDialog
    @Captor private lateinit var spyZenMode: ArgumentCaptor<Int>
    @Captor private lateinit var spyConditionId: ArgumentCaptor<Uri?>

    private lateinit var underTest: DoNotDisturbQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            DoNotDisturbQuickAffordanceConfig(
                context,
                zenModeController,
                kosmos.zenModeInteractor,
                settings,
                userTracker,
                testDispatcher,
                testScope.backgroundScope,
                conditionUri,
                enableZenModeDialog,
            )
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun dndNotAvailable_pickerStateHidden() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(false)
            runCurrent()

            val result = underTest.getPickerScreenState()
            runCurrent()

            assertEquals(
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice,
                result,
            )
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun controllerDndNotAvailable_pickerStateHidden() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(false)

            // when
            val result = underTest.getPickerScreenState()

            // then
            assertEquals(
                KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice,
                result,
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun dndAvailable_pickerStateVisible() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            runCurrent()

            val result = underTest.getPickerScreenState()
            runCurrent()

            assertThat(result)
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Default::class.java)
            val defaultPickerState =
                result as KeyguardQuickAffordanceConfig.PickerScreenState.Default
            assertThat(defaultPickerState.configureIntent).isNotNull()
            assertThat(defaultPickerState.configureIntent?.action)
                .isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun controllerDndAvailable_pickerStateVisible() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)

            // when
            val result = underTest.getPickerScreenState()

            // then
            assertThat(result)
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Default::class.java)
            val defaultPickerState =
                result as KeyguardQuickAffordanceConfig.PickerScreenState.Default
            assertThat(defaultPickerState.configureIntent).isNotNull()
            assertThat(defaultPickerState.configureIntent?.action)
                .isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_dndModeIsNotOff_setToOff() =
        testScope.runTest {
            val currentModes by collectLastValue(zenModeRepository.modes)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_ACTIVE)
            secureSettingsRepository.setInt(Settings.Secure.ZEN_DURATION, -2)
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            val result = underTest.onTriggered(null)
            runCurrent()

            val dndMode = currentModes!!.single()
            assertThat(dndMode.isActive).isFalse()
            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_controllerDndModeIsNotZEN_MODE_OFF_setToZEN_MODE_OFF() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            whenever(zenModeController.zen).thenReturn(-1)
            settings.putInt(Settings.Secure.ZEN_DURATION, -2)
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            // when
            val result = underTest.onTriggered(null)

            verify(zenModeController)
                .setZen(
                    spyZenMode.capture(),
                    spyConditionId.capture(),
                    eq(DoNotDisturbQuickAffordanceConfig.TAG),
                )

            // then
            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
            assertEquals(ZEN_MODE_OFF, spyZenMode.value)
            assertNull(spyConditionId.value)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_dndModeIsOff_settingFOREVER_setZenWithoutCondition() =
        testScope.runTest {
            val currentModes by collectLastValue(zenModeRepository.modes)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            secureSettingsRepository.setInt(Settings.Secure.ZEN_DURATION, ZEN_DURATION_FOREVER)
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            val result = underTest.onTriggered(null)
            runCurrent()

            val dndMode = currentModes!!.single()
            assertThat(dndMode.isActive).isTrue()
            assertThat(zenModeRepository.getModeActiveDuration(dndMode.id)).isNull()
            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_controllerDndModeIsZEN_MODE_OFF_settingFOREVER_setZenWithoutCondition() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            whenever(zenModeController.zen).thenReturn(ZEN_MODE_OFF)
            settings.putInt(Settings.Secure.ZEN_DURATION, ZEN_DURATION_FOREVER)
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            // when
            val result = underTest.onTriggered(null)
            verify(zenModeController)
                .setZen(
                    spyZenMode.capture(),
                    spyConditionId.capture(),
                    eq(DoNotDisturbQuickAffordanceConfig.TAG),
                )

            // then
            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
            assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, spyZenMode.value)
            assertNull(spyConditionId.value)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_dndModeIsOff_settingNotFOREVERorPROMPT_dndWithDuration() =
        testScope.runTest {
            val currentModes by collectLastValue(zenModeRepository.modes)
            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            secureSettingsRepository.setInt(Settings.Secure.ZEN_DURATION, -900)
            runCurrent()

            val result = underTest.onTriggered(null)
            runCurrent()

            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
            val dndMode = currentModes!!.single()
            assertThat(dndMode.isActive).isTrue()
            assertThat(zenModeRepository.getModeActiveDuration(dndMode.id))
                .isEqualTo(Duration.ofMinutes(-900))
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_controllerDndZEN_MODE_OFF_settingNotFOREVERorPROMPT_zenWithCondition() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            whenever(zenModeController.zen).thenReturn(ZEN_MODE_OFF)
            settings.putInt(Settings.Secure.ZEN_DURATION, -900)
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            // when
            val result = underTest.onTriggered(null)
            verify(zenModeController)
                .setZen(
                    spyZenMode.capture(),
                    spyConditionId.capture(),
                    eq(DoNotDisturbQuickAffordanceConfig.TAG),
                )

            // then
            assertEquals(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled, result)
            assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, spyZenMode.value)
            assertEquals(conditionUri, spyConditionId.value)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_dndModeIsOff_settingIsPROMPT_showDialog() =
        testScope.runTest {
            val expandable: Expandable = mock()
            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            secureSettingsRepository.setInt(Settings.Secure.ZEN_DURATION, ZEN_DURATION_PROMPT)
            whenever(enableZenModeDialog.createDialog()).thenReturn(mock())
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            val result = underTest.onTriggered(expandable)

            assertTrue(result is KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog)
            assertEquals(
                expandable,
                (result as KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog).expandable,
            )
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun onTriggered_controllerDndModeIsZEN_MODE_OFF_settingIsPROMPT_showDialog() =
        testScope.runTest {
            // given
            val expandable: Expandable = mock()
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            whenever(zenModeController.zen).thenReturn(ZEN_MODE_OFF)
            settings.putInt(Settings.Secure.ZEN_DURATION, ZEN_DURATION_PROMPT)
            whenever(enableZenModeDialog.createDialog()).thenReturn(mock())
            collectLastValue(underTest.lockScreenState)
            runCurrent()

            // when
            val result = underTest.onTriggered(expandable)

            // then
            assertTrue(result is KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog)
            assertEquals(
                expandable,
                (result as KeyguardQuickAffordanceConfig.OnTriggeredResult.ShowDialog).expandable,
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun lockScreenState_dndAvailableStartsAsTrue_changeToFalse_StateIsHidden() =
        testScope.runTest {
            deviceProvisioningRepository.setDeviceProvisioned(true)
            val valueSnapshot = collectLastValue(underTest.lockScreenState)
            val secondLastValue = valueSnapshot()
            runCurrent()

            deviceProvisioningRepository.setDeviceProvisioned(false)
            runCurrent()
            val lastValue = valueSnapshot()

            assertTrue(secondLastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
            assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun lockScreenState_controllerDndAvailableStartsAsTrue_changeToFalse_StateIsHidden() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            val callbackCaptor: ArgumentCaptor<ZenModeController.Callback> = argumentCaptor()
            val valueSnapshot = collectLastValue(underTest.lockScreenState)
            val secondLastValue = valueSnapshot()
            verify(zenModeController).addCallback(callbackCaptor.capture())

            // when
            callbackCaptor.value.onZenAvailableChanged(false)
            val lastValue = valueSnapshot()

            // then
            assertTrue(secondLastValue is KeyguardQuickAffordanceConfig.LockScreenState.Visible)
            assertTrue(lastValue is KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI)
    fun lockScreenState_dndModeStartsAsOff_changeToOn_StateVisible() =
        testScope.runTest {
            val lockScreenState by collectLastValue(underTest.lockScreenState)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            runCurrent()

            assertThat(lockScreenState)
                .isEqualTo(
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        Icon.Resource(
                            R.drawable.qs_dnd_icon_off,
                            ContentDescription.Resource(R.string.dnd_is_off),
                        ),
                        ActivationState.Inactive,
                    )
                )

            zenModeRepository.removeMode(TestModeBuilder.MANUAL_DND_INACTIVE.id)
            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_ACTIVE)
            runCurrent()

            assertThat(lockScreenState)
                .isEqualTo(
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        Icon.Resource(
                            R.drawable.qs_dnd_icon_on,
                            ContentDescription.Resource(R.string.dnd_is_on),
                        ),
                        ActivationState.Active,
                    )
                )
        }

    @Test
    @DisableFlags(Flags.FLAG_MODES_UI)
    fun lockScreenState_controllerDndModeStartsAsZEN_MODE_OFF_changeToNotOFF_StateVisible() =
        testScope.runTest {
            // given
            whenever(zenModeController.isZenAvailable).thenReturn(true)
            whenever(zenModeController.zen).thenReturn(ZEN_MODE_OFF)
            val valueSnapshot = collectLastValue(underTest.lockScreenState)
            val secondLastValue = valueSnapshot()
            val callbackCaptor: ArgumentCaptor<ZenModeController.Callback> = argumentCaptor()
            verify(zenModeController).addCallback(callbackCaptor.capture())

            // when
            callbackCaptor.value.onZenChanged(ZEN_MODE_IMPORTANT_INTERRUPTIONS)
            val lastValue = valueSnapshot()

            // then
            assertEquals(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    Icon.Resource(
                        R.drawable.qs_dnd_icon_off,
                        ContentDescription.Resource(R.string.dnd_is_off),
                    ),
                    ActivationState.Inactive,
                ),
                secondLastValue,
            )
            assertEquals(
                KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                    Icon.Resource(
                        R.drawable.qs_dnd_icon_on,
                        ContentDescription.Resource(R.string.dnd_is_on),
                    ),
                    ActivationState.Active,
                ),
                lastValue,
            )
        }
}
