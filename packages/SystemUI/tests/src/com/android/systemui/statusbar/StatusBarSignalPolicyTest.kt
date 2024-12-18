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

package com.android.systemui.statusbar

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.Flags.FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.statusbar.connectivity.IconState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy_Factory
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.airplaneModeInteractor
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.verifyZeroInteractions
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarSignalPolicyTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }

    private lateinit var underTest: StatusBarSignalPolicy

    private val testScope = TestScope()

    private val javaAdapter = JavaAdapter(testScope.backgroundScope)
    private val airplaneModeInteractor = kosmos.airplaneModeInteractor

    private val securityController = mock<SecurityController>()
    private val tunerService = mock<TunerService>()
    private val statusBarIconController = mock<StatusBarIconController>()
    private val networkController = mock<NetworkController>()
    private val carrierConfigTracker = mock<CarrierConfigTracker>()

    private var slotAirplane: String? = null

    @Before
    fun setup() {
        underTest =
            StatusBarSignalPolicy_Factory.newInstance(
                mContext,
                statusBarIconController,
                carrierConfigTracker,
                networkController,
                securityController,
                tunerService,
                javaAdapter,
                airplaneModeInteractor,
            )

        slotAirplane = mContext.getString(R.string.status_bar_airplane)
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaInteractor_statusBarSignalPolicyRefactorFlagEnabled_iconUpdated() =
        testScope.runTest {
            underTest.start()
            airplaneModeInteractor.setIsAirplaneMode(true)
            runCurrent()
            verify(statusBarIconController).setIconVisibility(slotAirplane, true)

            airplaneModeInteractor.setIsAirplaneMode(false)
            runCurrent()
            verify(statusBarIconController).setIconVisibility(slotAirplane, false)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaSignalCallback_statusBarSignalPolicyRefactorFlagEnabled_iconNotUpdated() =
        testScope.runTest {
            underTest.start()
            runCurrent()
            clearInvocations(statusBarIconController)

            // Make sure the legacy code path does not change airplane mode when the refactor
            // flag is enabled.
            underTest.setIsAirplaneMode(IconState(true, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            runCurrent()
            verifyZeroInteractions(statusBarIconController)

            underTest.setIsAirplaneMode(IconState(false, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            runCurrent()
            verifyZeroInteractions(statusBarIconController)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun statusBarSignalPolicyInitialization_statusBarSignalPolicyRefactorFlagEnabled_initNoOp() =
        testScope.runTest {
            // Make sure StatusBarSignalPolicy.init does no initialization when
            // the refactor flag is disabled.
            underTest.init()
            verifyZeroInteractions(securityController, networkController, tunerService)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaSignalCallback_statusBarSignalPolicyRefactorFlagDisabled_iconUpdated() =
        testScope.runTest {
            underTest.init()

            underTest.setIsAirplaneMode(IconState(true, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            runCurrent()
            verify(statusBarIconController).setIconVisibility(slotAirplane, true)

            underTest.setIsAirplaneMode(IconState(false, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            runCurrent()
            verify(statusBarIconController).setIconVisibility(slotAirplane, false)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaInteractor_statusBarSignalPolicyRefactorFlagDisabled_iconNotUpdated() =
        testScope.runTest {
            underTest.init()

            // Make sure changing airplane mode from airplaneModeRepository does nothing
            // if the StatusBarSignalPolicyRefactor is not enabled.
            airplaneModeInteractor.setIsAirplaneMode(true)
            runCurrent()
            verifyZeroInteractions(statusBarIconController)

            airplaneModeInteractor.setIsAirplaneMode(false)
            runCurrent()
            verifyZeroInteractions(statusBarIconController)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun statusBarSignalPolicyInitialization_statusBarSignalPolicyRefactorFlagDisabled_startNoOp() =
        testScope.runTest {
            // Make sure StatusBarSignalPolicy.start does no initialization when
            // the refactor flag is disabled.
            underTest.start()
            verifyZeroInteractions(securityController, networkController, tunerService)
        }
}
