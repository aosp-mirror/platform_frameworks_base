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

package com.android.systemui.statusbar.pipeline.satellite.data.prod

import android.os.OutcomeReceiver
import android.os.Process
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.satellite.NtnSignalStrength
import android.telephony.satellite.NtnSignalStrengthCallback
import android.telephony.satellite.SatelliteCommunicationAllowedStateCallback
import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_RETRYING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_IDLE
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_LISTENING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_NOT_CONNECTED
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_OFF
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_UNKNOWN
import android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_ERROR
import android.telephony.satellite.SatelliteManager.SatelliteException
import android.telephony.satellite.SatelliteModemStateCallback
import android.telephony.satellite.SatelliteProvisionStateCallback
import android.telephony.satellite.SatelliteSupportedStateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl.Companion.MIN_UPTIME
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doThrow

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceBasedSatelliteRepositoryImplTest : SysuiTestCase() {
    private lateinit var underTest: DeviceBasedSatelliteRepositoryImpl

    @Mock private lateinit var satelliteManager: SatelliteManager
    @Mock private lateinit var telephonyManager: TelephonyManager

    private val systemClock = FakeSystemClock()
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun nullSatelliteManager_usesDefaultValues() =
        testScope.runTest {
            setupDefaultRepo()
            underTest =
                DeviceBasedSatelliteRepositoryImpl(
                    Optional.empty(),
                    telephonyManager,
                    dispatcher,
                    testScope.backgroundScope,
                    logBuffer = FakeLogBuffer.Factory.create(),
                    verboseLogBuffer = FakeLogBuffer.Factory.create(),
                    systemClock,
                )

            val connectionState by collectLastValue(underTest.connectionState)
            val strength by collectLastValue(underTest.signalStrength)
            val allowed by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            assertThat(connectionState).isEqualTo(SatelliteConnectionState.Off)
            assertThat(strength).isEqualTo(0)
            assertThat(allowed).isFalse()
        }

    @Test
    fun connectionState_mapsFromSatelliteModemState() =
        testScope.runTest {
            setupDefaultRepo()
            val latest by collectLastValue(underTest.connectionState)
            runCurrent()
            val callback =
                withArgCaptor<SatelliteModemStateCallback> {
                    verify(satelliteManager).registerForModemStateChanged(any(), capture())
                }

            // Mapping from modem state to SatelliteConnectionState is rote, just run all of the
            // possibilities here

            // Off states
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_OFF)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_UNAVAILABLE)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Off)

            // On states
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_IDLE)
            assertThat(latest).isEqualTo(SatelliteConnectionState.On)
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_LISTENING)
            assertThat(latest).isEqualTo(SatelliteConnectionState.On)
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_NOT_CONNECTED)
            assertThat(latest).isEqualTo(SatelliteConnectionState.On)

            // Connected states
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_CONNECTED)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Connected)
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Connected)
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_DATAGRAM_RETRYING)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Connected)

            // Unknown states
            callback.onSatelliteModemStateChanged(SATELLITE_MODEM_STATE_UNKNOWN)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Unknown)
            // Garbage value (for completeness' sake)
            callback.onSatelliteModemStateChanged(123456)
            assertThat(latest).isEqualTo(SatelliteConnectionState.Unknown)
        }

    @Test
    fun signalStrength_readsSatelliteManagerState() =
        testScope.runTest {
            setupDefaultRepo()
            val latest by collectLastValue(underTest.signalStrength)
            runCurrent()
            val callback =
                withArgCaptor<NtnSignalStrengthCallback> {
                    verify(satelliteManager).registerForNtnSignalStrengthChanged(any(), capture())
                }

            assertThat(latest).isEqualTo(0)

            callback.onNtnSignalStrengthChanged(NtnSignalStrength(1))
            assertThat(latest).isEqualTo(1)

            callback.onNtnSignalStrengthChanged(NtnSignalStrength(2))
            assertThat(latest).isEqualTo(2)

            callback.onNtnSignalStrengthChanged(NtnSignalStrength(3))
            assertThat(latest).isEqualTo(3)

            callback.onNtnSignalStrengthChanged(NtnSignalStrength(4))
            assertThat(latest).isEqualTo(4)
        }

    @Test
    fun isSatelliteAllowed_listensToSatelliteManagerCallback() =
        testScope.runTest {
            setupDefaultRepo()

            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)
            runCurrent()

            val callback =
                withArgCaptor<SatelliteCommunicationAllowedStateCallback> {
                    verify(satelliteManager)
                        .registerForCommunicationAllowedStateChanged(any(), capture())
                }

            // WHEN satellite manager says it's not available
            callback.onSatelliteCommunicationAllowedStateChanged(false)

            // THEN it's not!
            assertThat(latest).isFalse()

            // WHEN satellite manager says it's changed to available
            callback.onSatelliteCommunicationAllowedStateChanged(true)

            // THEN it is!
            assertThat(latest).isTrue()
        }

    @Test
    fun isSatelliteAllowed_falseWhenErrorOccurs() =
        testScope.runTest {
            setupDefaultRepo()

            // GIVEN SatelliteManager gon' throw exceptions when we ask to register the callback
            doThrow(RuntimeException("Test exception"))
                .`when`(satelliteManager)
                .registerForCommunicationAllowedStateChanged(any(), any())

            // WHEN the latest value is requested (and thus causes an exception to be thrown)
            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            // THEN the value is just false, and we didn't crash!
            assertThat(latest).isFalse()
        }

    @Test
    fun isSatelliteAllowed_reRegistersOnTelephonyProcessCrash() =
        testScope.runTest {
            setupDefaultRepo()
            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)
            runCurrent()

            val callback =
                withArgCaptor<SatelliteCommunicationAllowedStateCallback> {
                    verify(satelliteManager)
                        .registerForCommunicationAllowedStateChanged(any(), capture())
                }

            val telephonyCallback =
                MobileTelephonyHelpers.getTelephonyCallbackForType<
                    TelephonyCallback.RadioPowerStateListener
                >(
                    telephonyManager
                )

            // GIVEN satellite is currently provisioned
            callback.onSatelliteCommunicationAllowedStateChanged(true)

            assertThat(latest).isTrue()

            // WHEN a crash event happens (detected by radio state change)
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_ON)
            runCurrent()
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_OFF)
            runCurrent()

            // THEN listener is re-registered
            verify(satelliteManager, times(2))
                .registerForCommunicationAllowedStateChanged(any(), any())
        }

    @Test
    fun satelliteProvisioned_notSupported_defaultFalse() =
        testScope.runTest {
            // GIVEN satellite is not supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = false,
            )

            assertThat(underTest.isSatelliteProvisioned.value).isFalse()
        }

    @Test
    fun satelliteProvisioned_supported_defaultFalse() =
        testScope.runTest {
            // GIVEN satellite is supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = true,
            )

            // THEN default provisioned state is false
            assertThat(underTest.isSatelliteProvisioned.value).isFalse()
        }

    @Test
    fun satelliteProvisioned_returnsException_defaultsToFalse() =
        testScope.runTest {
            // GIVEN satellite is supported on device
            doAnswer {
                    val callback: OutcomeReceiver<Boolean, SatelliteException> =
                        it.getArgument(1) as OutcomeReceiver<Boolean, SatelliteException>
                    callback.onResult(true)
                }
                .whenever(satelliteManager)
                .requestIsSupported(any(), any())

            // GIVEN satellite returns an error when asked if provisioned
            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onError(SatelliteException(SATELLITE_RESULT_ERROR))
                    null
                }
                .whenever(satelliteManager)
                .requestIsProvisioned(any(), any<OutcomeReceiver<Boolean, SatelliteException>>())

            // GIVEN we've been up long enough to start querying
            systemClock.setUptimeMillis(Process.getStartUptimeMillis() + MIN_UPTIME)

            underTest =
                DeviceBasedSatelliteRepositoryImpl(
                    Optional.of(satelliteManager),
                    telephonyManager,
                    dispatcher,
                    testScope.backgroundScope,
                    logBuffer = FakeLogBuffer.Factory.create(),
                    verboseLogBuffer = FakeLogBuffer.Factory.create(),
                    systemClock,
                )

            // WHEN we try to check for provisioned status
            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)

            // THEN well, first we don't throw...
            // AND THEN we assume that it's not provisioned
            assertThat(provisioned).isFalse()
        }

    @Test
    fun satelliteProvisioned_throwsWhenQuerying_defaultsToFalse() =
        testScope.runTest {
            // GIVEN satellite is supported on device
            doAnswer {
                    val callback: OutcomeReceiver<Boolean, SatelliteException> =
                        it.getArgument(1) as OutcomeReceiver<Boolean, SatelliteException>
                    callback.onResult(true)
                }
                .whenever(satelliteManager)
                .requestIsSupported(any(), any())

            // GIVEN satellite throws when asked if provisioned
            whenever(satelliteManager.requestIsProvisioned(any(), any()))
                .thenThrow(SecurityException())

            // GIVEN we've been up long enough to start querying
            systemClock.setUptimeMillis(Process.getStartUptimeMillis() + MIN_UPTIME)

            underTest =
                DeviceBasedSatelliteRepositoryImpl(
                    Optional.of(satelliteManager),
                    telephonyManager,
                    dispatcher,
                    testScope.backgroundScope,
                    logBuffer = FakeLogBuffer.Factory.create(),
                    verboseLogBuffer = FakeLogBuffer.Factory.create(),
                    systemClock,
                )

            // WHEN we try to check for provisioned status
            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)

            // THEN well, first we don't throw...
            // AND THEN we assume that it's not provisioned
            assertThat(provisioned).isFalse()
        }

    @Test
    fun satelliteProvisioned_supported_provisioned_queriesInitialStateBeforeCallbacks() =
        testScope.runTest {
            // GIVEN satellite is supported, and provisioned
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = true,
                initialSatelliteIsProvisioned = true,
            )

            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)

            runCurrent()

            // THEN the current state is requested
            verify(satelliteManager, atLeastOnce()).requestIsProvisioned(any(), any())

            // AND the state is correct
            assertThat(provisioned).isTrue()
        }

    @Test
    fun satelliteProvisioned_supported_notProvisioned_queriesInitialStateBeforeCallbacks() =
        testScope.runTest {
            // GIVEN satellite is supported, and provisioned
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = true,
                initialSatelliteIsProvisioned = false,
            )

            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)

            runCurrent()

            // THEN the current state is requested
            verify(satelliteManager, atLeastOnce()).requestIsProvisioned(any(), any())

            // AND the state is correct
            assertThat(provisioned).isFalse()
        }

    @Test
    fun satelliteProvisioned_supported_notInitiallyProvisioned_tracksCallback() =
        testScope.runTest {
            // GIVEN satellite is not supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = true,
                initialSatelliteIsProvisioned = false,
            )

            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)
            runCurrent()

            val callback =
                withArgCaptor<SatelliteProvisionStateCallback> {
                    verify(satelliteManager).registerForProvisionStateChanged(any(), capture())
                }

            // WHEN provisioning state changes
            callback.onSatelliteProvisionStateChanged(true)

            // THEN the value is reflected in the repo
            assertThat(provisioned).isTrue()
        }

    @Test
    fun satelliteProvisioned_supported_tracksCallback_reRegistersOnCrash() =
        testScope.runTest {
            // GIVEN satellite is supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = true,
            )

            val provisioned by collectLastValue(underTest.isSatelliteProvisioned)

            runCurrent()

            val callback =
                withArgCaptor<SatelliteProvisionStateCallback> {
                    verify(satelliteManager).registerForProvisionStateChanged(any(), capture())
                }
            val telephonyCallback =
                MobileTelephonyHelpers.getTelephonyCallbackForType<
                    TelephonyCallback.RadioPowerStateListener
                >(
                    telephonyManager
                )

            // GIVEN satellite is currently provisioned
            callback.onSatelliteProvisionStateChanged(true)

            assertThat(provisioned).isTrue()

            // WHEN a crash event happens (detected by radio state change)
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_ON)
            runCurrent()
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_OFF)
            runCurrent()

            // THEN listeners are re-registered
            verify(satelliteManager, times(2)).registerForProvisionStateChanged(any(), any())
            // AND the state is queried again
            verify(satelliteManager, times(2)).requestIsProvisioned(any(), any())
        }

    @Test
    fun satelliteNotSupported_listenersAreNotRegistered() =
        testScope.runTest {
            // GIVEN satellite is not supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = false,
            )

            // WHEN data is requested from the repo
            val connectionState by collectLastValue(underTest.connectionState)
            val signalStrength by collectLastValue(underTest.signalStrength)

            // THEN the manager is not asked for the information, and default values are returned
            verify(satelliteManager, never()).registerForModemStateChanged(any(), any())
            verify(satelliteManager, never()).registerForNtnSignalStrengthChanged(any(), any())
        }

    @Test
    fun satelliteSupported_registersCallbackForStateChanges() =
        testScope.runTest {
            // GIVEN a supported satellite manager.
            setupDefaultRepo()
            runCurrent()

            // THEN the repo registers for state changes of satellite support
            verify(satelliteManager, times(1)).registerForSupportedStateChanged(any(), any())
        }

    @Test
    fun satelliteNotSupported_registersCallbackForStateChanges() =
        testScope.runTest {
            // GIVEN satellite is not supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = false,
            )

            runCurrent()
            // THEN the repo registers for state changes of satellite support
            verify(satelliteManager, times(1)).registerForSupportedStateChanged(any(), any())
        }

    @Test
    fun satelliteSupportedStateChangedCallbackThrows_doesNotCrash() =
        testScope.runTest {
            // GIVEN, satellite manager throws when registering for supported state changes
            whenever(satelliteManager.registerForSupportedStateChanged(any(), any()))
                .thenThrow(IllegalStateException())

            // GIVEN a supported satellite manager.
            setupDefaultRepo()
            runCurrent()

            // THEN a listener for satellite supported changed can attempt to register,
            // with no crash
            verify(satelliteManager).registerForSupportedStateChanged(any(), any())
        }

    @Test
    fun satelliteSupported_supportIsLost_unregistersListeners() =
        testScope.runTest {
            // GIVEN a supported satellite manager.
            setupDefaultRepo()
            runCurrent()

            val callback =
                withArgCaptor<SatelliteSupportedStateCallback> {
                    verify(satelliteManager).registerForSupportedStateChanged(any(), capture())
                }

            // WHEN data is requested from the repo
            val connectionState by collectLastValue(underTest.connectionState)
            val signalStrength by collectLastValue(underTest.signalStrength)

            // THEN the listeners are registered
            verify(satelliteManager, times(1)).registerForModemStateChanged(any(), any())
            verify(satelliteManager, times(1)).registerForNtnSignalStrengthChanged(any(), any())

            // WHEN satellite support turns off
            callback.onSatelliteSupportedStateChanged(false)
            runCurrent()

            // THEN listeners are unregistered
            verify(satelliteManager, times(1)).unregisterForModemStateChanged(any())
            verify(satelliteManager, times(1)).unregisterForNtnSignalStrengthChanged(any())
        }

    @Test
    fun satelliteNotSupported_supportShowsUp_registersListeners() =
        testScope.runTest {
            // GIVEN satellite is not supported
            setUpRepo(
                uptime = MIN_UPTIME,
                satMan = satelliteManager,
                satelliteSupported = false,
            )
            runCurrent()

            val callback =
                withArgCaptor<SatelliteSupportedStateCallback> {
                    verify(satelliteManager).registerForSupportedStateChanged(any(), capture())
                }

            // WHEN data is requested from the repo
            val connectionState by collectLastValue(underTest.connectionState)
            val signalStrength by collectLastValue(underTest.signalStrength)

            // THEN the listeners are not yet registered
            verify(satelliteManager, times(0)).registerForModemStateChanged(any(), any())
            verify(satelliteManager, times(0)).registerForNtnSignalStrengthChanged(any(), any())

            // WHEN satellite support turns on
            callback.onSatelliteSupportedStateChanged(true)
            runCurrent()

            // THEN listeners are registered
            verify(satelliteManager, times(1)).registerForModemStateChanged(any(), any())
            verify(satelliteManager, times(1)).registerForNtnSignalStrengthChanged(any(), any())
        }

    @Test
    fun repoDoesNotCheckForSupportUntilMinUptime() =
        testScope.runTest {
            // GIVEN we init 100ms after sysui starts up
            setUpRepo(
                uptime = 100,
                satMan = satelliteManager,
                satelliteSupported = true,
            )

            // WHEN data is requested
            val connectionState by collectLastValue(underTest.connectionState)
            val signalStrength by collectLastValue(underTest.signalStrength)

            // THEN we have not yet talked to satellite manager, since we are well before MIN_UPTIME
            Mockito.verifyZeroInteractions(satelliteManager)

            // WHEN enough time has passed
            systemClock.advanceTime(MIN_UPTIME)
            runCurrent()

            // THEN we finally register with the satellite manager
            verify(satelliteManager).registerForModemStateChanged(any(), any())
        }

    @Test
    fun telephonyCrash_repoReregistersConnectionStateListener() =
        testScope.runTest {
            setupDefaultRepo()

            // GIVEN connection state is requested
            val connectionState by collectLastValue(underTest.connectionState)

            runCurrent()

            val telephonyCallback =
                MobileTelephonyHelpers.getTelephonyCallbackForType<
                    TelephonyCallback.RadioPowerStateListener
                >(
                    telephonyManager
                )

            // THEN listener is registered once
            verify(satelliteManager, times(1)).registerForModemStateChanged(any(), any())

            // WHEN a crash event happens (detected by radio state change)
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_ON)
            runCurrent()
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_OFF)
            runCurrent()

            // THEN listeners are unregistered and re-registered
            verify(satelliteManager, times(1)).unregisterForModemStateChanged(any())
            verify(satelliteManager, times(2)).registerForModemStateChanged(any(), any())
        }

    @Test
    fun telephonyCrash_repoReregistersSignalStrengthListener() =
        testScope.runTest {
            setupDefaultRepo()

            // GIVEN signal strength is requested
            val signalStrength by collectLastValue(underTest.signalStrength)

            runCurrent()

            val telephonyCallback =
                MobileTelephonyHelpers.getTelephonyCallbackForType<
                    TelephonyCallback.RadioPowerStateListener
                >(
                    telephonyManager
                )

            // THEN listeners are registered the first time
            verify(satelliteManager, times(1)).registerForNtnSignalStrengthChanged(any(), any())

            // WHEN a crash event happens (detected by radio state change)
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_ON)
            runCurrent()
            telephonyCallback.onRadioPowerStateChanged(TelephonyManager.RADIO_POWER_OFF)
            runCurrent()

            // THEN listeners are unregistered and re-registered
            verify(satelliteManager, times(1)).unregisterForNtnSignalStrengthChanged(any())
            verify(satelliteManager, times(2)).registerForNtnSignalStrengthChanged(any(), any())
        }

    private fun setUpRepo(
        uptime: Long = MIN_UPTIME,
        satMan: SatelliteManager? = satelliteManager,
        satelliteSupported: Boolean = true,
        initialSatelliteIsProvisioned: Boolean = true,
    ) {
        doAnswer {
                val callback: OutcomeReceiver<Boolean, SatelliteException> =
                    it.getArgument(1) as OutcomeReceiver<Boolean, SatelliteException>
                callback.onResult(satelliteSupported)
            }
            .whenever(satelliteManager)
            .requestIsSupported(any(), any())

        doAnswer {
                val callback: OutcomeReceiver<Boolean, SatelliteException> =
                    it.getArgument(1) as OutcomeReceiver<Boolean, SatelliteException>
                callback.onResult(initialSatelliteIsProvisioned)
            }
            .whenever(satelliteManager)
            .requestIsProvisioned(any(), any())

        systemClock.setUptimeMillis(Process.getStartUptimeMillis() + uptime)

        underTest =
            DeviceBasedSatelliteRepositoryImpl(
                if (satMan != null) Optional.of(satMan) else Optional.empty(),
                telephonyManager,
                dispatcher,
                testScope.backgroundScope,
                logBuffer = FakeLogBuffer.Factory.create(),
                verboseLogBuffer = FakeLogBuffer.Factory.create(),
                systemClock,
            )
    }

    // Set system time to MIN_UPTIME and create a repo with satellite supported
    private fun setupDefaultRepo() {
        setUpRepo(uptime = MIN_UPTIME, satMan = satelliteManager, satelliteSupported = true)
    }
}
