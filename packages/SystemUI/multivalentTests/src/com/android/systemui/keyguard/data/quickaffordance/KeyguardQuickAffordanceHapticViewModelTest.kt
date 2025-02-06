/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.data.quickaffordance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.domain.interactor.keyguardQuickAffordanceHapticViewModelFactory
import com.android.systemui.keyguard.domain.interactor.keyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceHapticViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardQuickAffordanceViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceHapticViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
    private val configKey = "$slotId::home"
    private val keyguardQuickAffordanceInteractor = kosmos.keyguardQuickAffordanceInteractor
    private val viewModelFlow =
        MutableStateFlow(KeyguardQuickAffordanceViewModel(configKey = configKey, slotId = slotId))

    private val underTest =
        kosmos.keyguardQuickAffordanceHapticViewModelFactory.create(viewModelFlow)

    @Test
    fun whenLaunchingFromTriggeredResult_hapticStateIsLaunch() =
        testScope.runTest {
            // GIVEN that the result from triggering the affordance launched an activity or dialog
            val hapticState by collectLastValue(underTest.quickAffordanceHapticState)
            keyguardQuickAffordanceInteractor.setLaunchingFromTriggeredResult(
                KeyguardQuickAffordanceConfig.LaunchingFromTriggeredResult(true, configKey)
            )
            runCurrent()

            // THEN the haptic state indicates that a launch haptics must play
            assertThat(hapticState)
                .isEqualTo(KeyguardQuickAffordanceHapticViewModel.HapticState.LAUNCH)
        }

    @Test
    fun whenNotLaunchFromTriggeredResult_hapticStateDoesNotEmit() =
        testScope.runTest {
            // GIVEN that the result from triggering the affordance did not launch an activity or
            // dialog
            val hapticState by collectLastValue(underTest.quickAffordanceHapticState)
            keyguardQuickAffordanceInteractor.setLaunchingFromTriggeredResult(
                KeyguardQuickAffordanceConfig.LaunchingFromTriggeredResult(false, configKey)
            )
            runCurrent()

            // THEN there is no haptic state to play any feedback
            assertThat(hapticState)
                .isEqualTo(KeyguardQuickAffordanceHapticViewModel.HapticState.NO_HAPTICS)
        }

    @Test
    fun onQuickAffordanceTogglesToActivated_hapticStateIsToggleOn() =
        testScope.runTest {
            // GIVEN that an affordance toggles from deactivated to activated
            val hapticState by collectLastValue(underTest.quickAffordanceHapticState)
            toggleQuickAffordance(on = true)

            // THEN the haptic state reflects that a toggle on haptics should play
            assertThat(hapticState)
                .isEqualTo(KeyguardQuickAffordanceHapticViewModel.HapticState.TOGGLE_ON)
        }

    @Test
    fun onQuickAffordanceTogglesToDeactivated_hapticStateIsToggleOff() =
        testScope.runTest {
            // GIVEN that an affordance toggles from activated to deactivated
            val hapticState by collectLastValue(underTest.quickAffordanceHapticState)
            toggleQuickAffordance(on = false)

            // THEN the haptic state reflects that a toggle off haptics should play
            assertThat(hapticState)
                .isEqualTo(KeyguardQuickAffordanceHapticViewModel.HapticState.TOGGLE_OFF)
        }

    private fun TestScope.toggleQuickAffordance(on: Boolean) {
        underTest.updateActivatedHistory(!on)
        runCurrent()
        underTest.updateActivatedHistory(on)
        runCurrent()
    }
}
