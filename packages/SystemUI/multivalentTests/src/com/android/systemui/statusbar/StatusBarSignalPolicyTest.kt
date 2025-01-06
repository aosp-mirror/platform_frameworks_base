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
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.connectivity.IconState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.airplaneModeInteractor
import com.android.systemui.statusbar.pipeline.ethernet.domain.ethernetInteractor
import com.android.systemui.statusbar.pipeline.ethernet.shared.StatusBarSignalPolicyRefactorEthernet
import com.android.systemui.statusbar.pipeline.shared.data.repository.connectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.fake
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.testKosmos
import com.android.systemui.tuner.tunerService
import com.android.systemui.util.kotlin.JavaAdapter
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatusBarSignalPolicyTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val javaAdapter = JavaAdapter(kosmos.testScope.backgroundScope)
    private val securityController = mock<SecurityController>()
    private val statusBarIconController = mock<StatusBarIconController>()
    private val networkController = mock<NetworkController>()

    private val Kosmos.underTest by
        Kosmos.Fixture {
            StatusBarSignalPolicy(
                mContext,
                statusBarIconController,
                networkController,
                securityController,
                tunerService,
                javaAdapter,
                airplaneModeInteractor,
                ethernetInteractor,
            )
        }

    private lateinit var slotAirplane: String
    private lateinit var slotEthernet: String

    @Before
    fun setup() {
        slotAirplane = mContext.getString(R.string.status_bar_airplane)
        slotEthernet = mContext.getString(R.string.status_bar_ethernet)
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaInteractor_statusBarSignalPolicyRefactorFlagEnabled_iconUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            airplaneModeInteractor.setIsAirplaneMode(true)
            verify(statusBarIconController).setIconVisibility(slotAirplane, true)

            airplaneModeInteractor.setIsAirplaneMode(false)
            verify(statusBarIconController).setIconVisibility(slotAirplane, false)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaSignalCallback_statusBarSignalPolicyRefactorFlagEnabled_iconNotUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            // Make sure the legacy code path does not change airplane mode when the refactor
            // flag is enabled.
            underTest.setIsAirplaneMode(IconState(true, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            verifyNoMoreInteractions(statusBarIconController)

            underTest.setIsAirplaneMode(IconState(false, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            verifyNoMoreInteractions(statusBarIconController)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun statusBarSignalPolicyInitialization_statusBarSignalPolicyRefactorFlagEnabled_initNoOp() =
        kosmos.runTest {
            // Make sure StatusBarSignalPolicy.init does no initialization when
            // the refactor flag is disabled.
            underTest.init()
            verifyNoMoreInteractions(securityController, networkController, tunerService)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaSignalCallback_statusBarSignalPolicyRefactorFlagDisabled_iconUpdated() =
        kosmos.runTest {
            underTest.init()

            underTest.setIsAirplaneMode(IconState(true, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            verify(statusBarIconController).setIconVisibility(slotAirplane, true)

            underTest.setIsAirplaneMode(IconState(false, TelephonyIcons.FLIGHT_MODE_ICON, ""))
            verify(statusBarIconController).setIconVisibility(slotAirplane, false)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun airplaneModeViaInteractor_statusBarSignalPolicyRefactorFlagDisabled_iconNotUpdated() =
        kosmos.runTest {
            underTest.init()

            // Make sure changing airplane mode from airplaneModeRepository does nothing
            // if the StatusBarSignalPolicyRefactor is not enabled.
            airplaneModeInteractor.setIsAirplaneMode(true)
            verifyNoMoreInteractions(statusBarIconController)

            airplaneModeInteractor.setIsAirplaneMode(false)
            verifyNoMoreInteractions(statusBarIconController)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    fun statusBarSignalPolicyInitialization_statusBarSignalPolicyRefactorFlagDisabled_startNoOp() =
        kosmos.runTest {
            // Make sure StatusBarSignalPolicy.start does no initialization when
            // the refactor flag is disabled.
            underTest.start()
            verifyNoMoreInteractions(securityController, networkController, tunerService)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    @DisableFlags(StatusBarSignalPolicyRefactorEthernet.FLAG_NAME)
    fun ethernetIconViaSignalCallback_refactorFlagDisabled_iconUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            underTest.setEthernetIndicators(
                IconState(/* visible= */ true, /* icon= */ 1, /* contentDescription= */ "Ethernet")
            )
            verify(statusBarIconController).setIconVisibility(slotEthernet, true)

            underTest.setEthernetIndicators(
                IconState(
                    /* visible= */ false,
                    /* icon= */ 0,
                    /* contentDescription= */ "No ethernet",
                )
            )
            verify(statusBarIconController).setIconVisibility(slotEthernet, false)
        }

    @Test
    @EnableFlags(
        FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR,
        StatusBarSignalPolicyRefactorEthernet.FLAG_NAME,
    )
    fun ethernetIconViaSignalCallback_refactorFlagEnabled_iconNotUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            underTest.setEthernetIndicators(
                IconState(/* visible= */ true, /* icon= */ 1, /* contentDescription= */ "Ethernet")
            )
            verifyNoMoreInteractions(statusBarIconController)

            underTest.setEthernetIndicators(
                IconState(
                    /* visible= */ false,
                    /* icon= */ 0,
                    /* contentDescription= */ "No ethernet",
                )
            )
            verifyNoMoreInteractions(statusBarIconController)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR)
    @DisableFlags(StatusBarSignalPolicyRefactorEthernet.FLAG_NAME)
    fun ethernetIconViaInteractor_refactorFlagDisabled_iconNotUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            connectivityRepository.fake.setEthernetConnected(default = true, validated = true)
            verifyNoMoreInteractions(statusBarIconController)

            connectivityRepository.fake.setEthernetConnected(default = false, validated = false)
            verifyNoMoreInteractions(statusBarIconController)

            connectivityRepository.fake.setEthernetConnected(default = true, validated = false)
            verifyNoMoreInteractions(statusBarIconController)
        }

    @Test
    @EnableFlags(
        FLAG_STATUS_BAR_SIGNAL_POLICY_REFACTOR,
        StatusBarSignalPolicyRefactorEthernet.FLAG_NAME,
    )
    fun ethernetIconViaInteractor_refactorFlagEnabled_iconUpdated() =
        kosmos.runTest {
            underTest.start()
            clearInvocations(statusBarIconController)

            connectivityRepository.fake.setEthernetConnected(default = true, validated = true)
            verify(statusBarIconController).setIconVisibility(slotEthernet, true)

            connectivityRepository.fake.setEthernetConnected(default = false, validated = false)
            verify(statusBarIconController).setIconVisibility(slotEthernet, false)

            clearInvocations(statusBarIconController)

            connectivityRepository.fake.setEthernetConnected(default = true, validated = false)
            verify(statusBarIconController).setIconVisibility(slotEthernet, true)
        }
}
