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
import android.telephony.satellite.SatelliteManager.SatelliteException
import android.telephony.satellite.SatelliteModemStateCallback
import android.telephony.satellite.SatelliteSupportedStateCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl.Companion.MIN_UPTIME
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl.Companion.POLLING_INTERVAL_MS
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
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
                    FakeLogBuffer.Factory.create(),
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
    fun isSatelliteAllowed_readsSatelliteManagerState_enabled() =
        testScope.runTest {
            setupDefaultRepo()
            // GIVEN satellite is allowed in this location
            val allowed = true

            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onResult(allowed)
                    null
                }
                .`when`(satelliteManager)
                .requestIsCommunicationAllowedForCurrentLocation(
                    any(),
                    any<OutcomeReceiver<Boolean, SatelliteException>>()
                )

            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            assertThat(latest).isTrue()
        }

    @Test
    fun isSatelliteAllowed_readsSatelliteManagerState_disabled() =
        testScope.runTest {
            setupDefaultRepo()
            // GIVEN satellite is not allowed in this location
            val allowed = false

            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onResult(allowed)
                    null
                }
                .`when`(satelliteManager)
                .requestIsCommunicationAllowedForCurrentLocation(
                    any(),
                    any<OutcomeReceiver<Boolean, SatelliteException>>()
                )

            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            assertThat(latest).isFalse()
        }

    @Test
    fun isSatelliteAllowed_pollsOnTimeout() =
        testScope.runTest {
            setupDefaultRepo()
            // GIVEN satellite is not allowed in this location
            var allowed = false

            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onResult(allowed)
                    null
                }
                .`when`(satelliteManager)
                .requestIsCommunicationAllowedForCurrentLocation(
                    any(),
                    any<OutcomeReceiver<Boolean, SatelliteException>>()
                )

            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            assertThat(latest).isFalse()

            // WHEN satellite becomes enabled
            allowed = true

            // WHEN the timeout has not yet been reached
            advanceTimeBy(POLLING_INTERVAL_MS / 2)

            // THEN the value is still false
            assertThat(latest).isFalse()

            // WHEN time advances beyond the polling interval
            advanceTimeBy(POLLING_INTERVAL_MS / 2 + 1)

            // THEN then new value is emitted
            assertThat(latest).isTrue()
        }

    @Test
    fun isSatelliteAllowed_pollingRestartsWhenCollectionRestarts() =
        testScope.runTest {
            setupDefaultRepo()
            // Use the old school launch/cancel so we can simulate subscribers arriving and leaving

            var latest: Boolean? = false
            var job =
                underTest.isSatelliteAllowedForCurrentLocation.onEach { latest = it }.launchIn(this)

            // GIVEN satellite is not allowed in this location
            var allowed = false

            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onResult(allowed)
                    null
                }
                .`when`(satelliteManager)
                .requestIsCommunicationAllowedForCurrentLocation(
                    any(),
                    any<OutcomeReceiver<Boolean, SatelliteException>>()
                )

            assertThat(latest).isFalse()

            // WHEN satellite becomes enabled
            allowed = true

            // WHEN the job is restarted
            advanceTimeBy(POLLING_INTERVAL_MS / 2)

            job.cancel()
            job =
                underTest.isSatelliteAllowedForCurrentLocation.onEach { latest = it }.launchIn(this)

            // THEN the value is re-fetched
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isSatelliteAllowed_falseWhenErrorOccurs() =
        testScope.runTest {
            setupDefaultRepo()
            doAnswer {
                    val receiver = it.arguments[1] as OutcomeReceiver<Boolean, SatelliteException>
                    receiver.onError(SatelliteException(1 /* unused */))
                    null
                }
                .`when`(satelliteManager)
                .requestIsCommunicationAllowedForCurrentLocation(
                    any(),
                    any<OutcomeReceiver<Boolean, SatelliteException>>()
                )

            val latest by collectLastValue(underTest.isSatelliteAllowedForCurrentLocation)

            assertThat(latest).isFalse()
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
    ) {
        doAnswer {
                val callback: OutcomeReceiver<Boolean, SatelliteException> =
                    it.getArgument(1) as OutcomeReceiver<Boolean, SatelliteException>
                callback.onResult(satelliteSupported)
            }
            .whenever(satelliteManager)
            .requestIsSupported(any(), any())

        systemClock.setUptimeMillis(Process.getStartUptimeMillis() + uptime)

        underTest =
            DeviceBasedSatelliteRepositoryImpl(
                if (satMan != null) Optional.of(satMan) else Optional.empty(),
                telephonyManager,
                dispatcher,
                testScope.backgroundScope,
                FakeLogBuffer.Factory.create(),
                systemClock,
            )
    }

    // Set system time to MIN_UPTIME and create a repo with satellite supported
    private fun setupDefaultRepo() {
        setUpRepo(uptime = MIN_UPTIME, satMan = satelliteManager, satelliteSupported = true)
    }
}
