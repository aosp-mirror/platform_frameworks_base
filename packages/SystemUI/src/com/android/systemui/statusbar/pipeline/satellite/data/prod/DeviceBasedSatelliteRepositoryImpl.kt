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
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.satellite.NtnSignalStrengthCallback
import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteManager.SATELLITE_RESULT_SUCCESS
import android.telephony.satellite.SatelliteModemStateCallback
import android.telephony.satellite.SatelliteProvisionStateCallback
import android.telephony.satellite.SatelliteSupportedStateCallback
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.MessageInitializer
import com.android.systemui.log.core.MessagePrinter
import com.android.systemui.statusbar.pipeline.dagger.DeviceBasedSatelliteInputLog
import com.android.systemui.statusbar.pipeline.dagger.VerboseDeviceBasedSatelliteInputLog
import com.android.systemui.statusbar.pipeline.satellite.data.RealDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl.Companion.POLLING_INTERVAL_MS
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Companion.whenSupported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.NotSupported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Supported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Unknown
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A SatelliteManager that has responded that it has satellite support. Use [SatelliteSupport] to
 * get one
 */
private typealias SupportedSatelliteManager = SatelliteManager

/**
 * "Supported" here means supported by the device. The value of this should be stable during the
 * process lifetime.
 *
 * @VisibleForTesting
 */
sealed interface SatelliteSupport {
    /** Not yet fetched */
    data object Unknown : SatelliteSupport

    /**
     * SatelliteManager says that this mode is supported. Note that satellite manager can never be
     * null now
     */
    data class Supported(val satelliteManager: SupportedSatelliteManager) : SatelliteSupport

    /**
     * Either we were told that there is no support for this feature, or the manager is null, or
     * some other exception occurred while querying for support.
     */
    data object NotSupported : SatelliteSupport

    @OptIn(ExperimentalCoroutinesApi::class)
    companion object {
        /**
         * Convenience function to switch to the supported flow. [retrySignal] is a flow that emits
         * [Unit] whenever the [supported] flow needs to be restarted
         */
        fun <T> Flow<SatelliteSupport>.whenSupported(
            supported: (SatelliteManager) -> Flow<T>,
            orElse: Flow<T>,
            retrySignal: Flow<Unit>,
        ): Flow<T> = flatMapLatest { satelliteSupport ->
            when (satelliteSupport) {
                is Supported -> {
                    retrySignal.flatMapLatest { supported(satelliteSupport.satelliteManager) }
                }
                else -> orElse
            }
        }
    }
}

/**
 * Basically your everyday run-of-the-mill system service listener, with three notable exceptions.
 *
 * First, there is an availability bit that we are tracking via [SatelliteManager]. See
 * [isSatelliteAllowedForCurrentLocation] for the implementation details. The thing to note about
 * this bit is that there is no callback that exists. Therefore we implement a simple polling
 * mechanism here. Since the underlying bit is location-dependent, we simply poll every hour (see
 * [POLLING_INTERVAL_MS]) and see what the current state is.
 *
 * Secondly, there are cases when simply requesting information from SatelliteManager can fail. See
 * [SatelliteSupport] for details on how we track the state. What's worth noting here is that
 * SUPPORTED is a stronger guarantee than [satelliteManager] being null. Therefore, the fundamental
 * data flows here ([connectionState], [signalStrength],...) are wrapped in the convenience method
 * [SatelliteSupport.whenSupported]. By defining flows as simple functions based on a
 * [SupportedSatelliteManager], we can guarantee that the manager is non-null AND that it has told
 * us that satellite is supported. Therefore, we don't expect exceptions to be thrown.
 *
 * Lastly, this class is designed to wait a full minute of process uptime before making any requests
 * to the satellite manager. The hope is that by waiting we don't have to retry due to a modem that
 * is still booting up or anything like that. We can tune or remove this behavior in the future if
 * necessary.
 */
@SysUISingleton
class DeviceBasedSatelliteRepositoryImpl
@Inject
constructor(
    satelliteManagerOpt: Optional<SatelliteManager>,
    telephonyManager: TelephonyManager,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    @DeviceBasedSatelliteInputLog private val logBuffer: LogBuffer,
    @VerboseDeviceBasedSatelliteInputLog private val verboseLogBuffer: LogBuffer,
    private val systemClock: SystemClock,
) : RealDeviceBasedSatelliteRepository {

    private val satelliteManager: SatelliteManager?

    override val isSatelliteAllowedForCurrentLocation: MutableStateFlow<Boolean>

    // Some calls into satellite manager will throw exceptions if it is not supported.
    // This is never expected to change after boot, but may need to be retried in some cases
    @get:VisibleForTesting
    val satelliteSupport: MutableStateFlow<SatelliteSupport> = MutableStateFlow(Unknown)

    /**
     * Note that we are given an "unbound" [TelephonyManager] (meaning it was not created with a
     * specific `subscriptionId`). Therefore this is the radio power state of the
     * DEFAULT_SUBSCRIPTION_ID subscription. This subscription, I am led to believe, is the one that
     * would be used for the SatelliteManager subscription.
     *
     * By watching power state changes, we can detect if the telephony process crashes.
     *
     * See b/337258696 for details
     */
    private val radioPowerState: StateFlow<Int> =
        conflatedCallbackFlow {
                val cb =
                    object : TelephonyCallback(), TelephonyCallback.RadioPowerStateListener {
                        override fun onRadioPowerStateChanged(powerState: Int) {
                            trySend(powerState)
                        }
                    }

                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), cb)

                awaitClose { telephonyManager.unregisterTelephonyCallback(cb) }
            }
            .flowOn(bgDispatcher)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                TelephonyManager.RADIO_POWER_UNAVAILABLE
            )

    /**
     * In the event that a telephony phone process has crashed, we expect to see a radio power state
     * change from ON to something else. This trigger can be used to re-start a flow via
     * [whenSupported]
     *
     * This flow emits [Unit] when started so that newly-started collectors always run, and only
     * restart when the state goes from ON -> !ON
     */
    private val telephonyProcessCrashedEvent: Flow<Unit> =
        radioPowerState
            .pairwise()
            .mapNotNull { (prev: Int, new: Int) ->
                if (
                    prev == TelephonyManager.RADIO_POWER_ON &&
                        new != TelephonyManager.RADIO_POWER_ON
                ) {
                    Unit
                } else {
                    null
                }
            }
            .onStart { emit(Unit) }

    init {
        satelliteManager = satelliteManagerOpt.getOrNull()

        isSatelliteAllowedForCurrentLocation = MutableStateFlow(false)

        if (satelliteManager != null) {
            // Outer scope launch allows us to delay until MIN_UPTIME
            scope.launch {
                // First, check that satellite is supported on this device
                satelliteSupport.value = checkSatelliteSupportAfterMinUptime(satelliteManager)
                logBuffer.i(
                    { str1 = satelliteSupport.value.toString() },
                    { "Checked for system support. support=$str1" },
                )

                // Second, launch a job to poll for service availability based on location
                scope.launch { pollForAvailabilityBasedOnLocation() }

                // Third, register a listener to let us know if there are changes to support
                scope.launch { listenForChangesToSatelliteSupport(satelliteManager) }
            }
        } else {
            logBuffer.i { "Satellite manager is null" }
            satelliteSupport.value = NotSupported
        }
    }

    private suspend fun checkSatelliteSupportAfterMinUptime(
        sm: SatelliteManager
    ): SatelliteSupport {
        val waitTime = ensureMinUptime(systemClock, MIN_UPTIME)
        if (waitTime > 0) {
            logBuffer.i({ long1 = waitTime }) {
                "Waiting $long1 ms before checking for satellite support"
            }
            delay(waitTime)
        }

        return sm.checkSatelliteSupported()
    }

    /*
     * As there is no listener available for checking satellite allowed, we must poll the service.
     * Defaulting to polling at most once every 20m while active. Subsequent OOS events will restart
     * the job, so a flaky connection might cause more frequent checks.
     */
    private suspend fun pollForAvailabilityBasedOnLocation() {
        satelliteSupport
            .whenSupported(
                supported = ::isSatelliteAllowedHasListener,
                orElse = flowOf(false),
                retrySignal = telephonyProcessCrashedEvent,
            )
            .collectLatest { hasSubscribers ->
                if (hasSubscribers) {
                    while (true) {
                        logBuffer.i { "requestIsCommunicationAllowedForCurrentLocation" }
                        checkIsSatelliteAllowed()
                        delay(POLLING_INTERVAL_MS)
                    }
                }
            }
    }

    /**
     * Register a callback with [SatelliteManager] to let us know if there is a change in satellite
     * support. This job restarts if there is a crash event detected.
     *
     * Note that the structure of this method looks similar to [whenSupported], but since we want
     * this callback registered even when it is [NotSupported], we just mimic the structure here.
     */
    private suspend fun listenForChangesToSatelliteSupport(sm: SatelliteManager) {
        telephonyProcessCrashedEvent.collectLatest {
            satelliteIsSupportedCallback.collect { supported ->
                if (supported) {
                    satelliteSupport.value = Supported(sm)
                } else {
                    satelliteSupport.value = NotSupported
                }
            }
        }
    }

    /**
     * Callback version of [checkSatelliteSupported]. This flow should be retried on the same
     * [telephonyProcessCrashedEvent] signal, but does not require a [SupportedSatelliteManager],
     * since it specifically watches for satellite support.
     */
    private val satelliteIsSupportedCallback: Flow<Boolean> =
        if (satelliteManager == null) {
            flowOf(false)
        } else {
            conflatedCallbackFlow {
                val callback = SatelliteSupportedStateCallback { supported ->
                    logBuffer.i {
                        "onSatelliteSupportedStateChanged: " +
                            "${if (supported) "supported" else "not supported"}"
                    }
                    trySend(supported)
                }

                var registered = false
                try {
                    satelliteManager.registerForSupportedStateChanged(
                        bgDispatcher.asExecutor(),
                        callback
                    )
                    registered = true
                } catch (e: Exception) {
                    logBuffer.e("error registering for supported state change", e)
                }

                awaitClose {
                    if (registered) {
                        satelliteManager.unregisterForSupportedStateChanged(callback)
                    }
                }
            }
        }

    override val isSatelliteProvisioned: StateFlow<Boolean> =
        satelliteSupport
            .whenSupported(
                supported = ::satelliteProvisioned,
                orElse = flowOf(false),
                retrySignal = telephonyProcessCrashedEvent,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private fun satelliteProvisioned(sm: SupportedSatelliteManager): Flow<Boolean> =
        conflatedCallbackFlow {
            val callback = SatelliteProvisionStateCallback { provisioned ->
                logBuffer.i {
                    "onSatelliteProvisionStateChanged: " +
                        if (provisioned) "provisioned" else "not provisioned"
                }
                trySend(provisioned)
            }

            var registered = false
            try {
                sm.registerForProvisionStateChanged(
                    bgDispatcher.asExecutor(),
                    callback,
                )
                registered = true
            } catch (e: Exception) {
                logBuffer.e("error registering for provisioning state callback", e)
            }

            awaitClose {
                if (registered) {
                    sm.unregisterForProvisionStateChanged(callback)
                }
            }
        }

    /**
     * Signal that we should start polling [checkIsSatelliteAllowed]. We only need to poll if there
     * are active listeners to [isSatelliteAllowedForCurrentLocation]
     */
    @SuppressWarnings("unused")
    private fun isSatelliteAllowedHasListener(sm: SupportedSatelliteManager): Flow<Boolean> =
        isSatelliteAllowedForCurrentLocation.subscriptionCount.map { it > 0 }.distinctUntilChanged()

    override val connectionState =
        satelliteSupport
            .whenSupported(
                supported = ::connectionStateFlow,
                orElse = flowOf(SatelliteConnectionState.Off),
                retrySignal = telephonyProcessCrashedEvent,
            )
            .stateIn(scope, SharingStarted.Eagerly, SatelliteConnectionState.Off)

    // By using the SupportedSatelliteManager here, we expect registration never to fail
    private fun connectionStateFlow(sm: SupportedSatelliteManager): Flow<SatelliteConnectionState> =
        conflatedCallbackFlow {
                val cb = SatelliteModemStateCallback { state ->
                    logBuffer.i({ int1 = state }) { "onSatelliteModemStateChanged: state=$int1" }
                    trySend(SatelliteConnectionState.fromModemState(state))
                }

                var registered = false

                try {
                    val res = sm.registerForModemStateChanged(bgDispatcher.asExecutor(), cb)
                    registered = res == SATELLITE_RESULT_SUCCESS
                } catch (e: Exception) {
                    logBuffer.e("error registering for modem state", e)
                }

                awaitClose { if (registered) sm.unregisterForModemStateChanged(cb) }
            }
            .flowOn(bgDispatcher)

    override val signalStrength =
        satelliteSupport
            .whenSupported(
                supported = ::signalStrengthFlow,
                orElse = flowOf(0),
                retrySignal = telephonyProcessCrashedEvent,
            )
            .stateIn(scope, SharingStarted.Eagerly, 0)

    // By using the SupportedSatelliteManager here, we expect registration never to fail
    private fun signalStrengthFlow(sm: SupportedSatelliteManager) =
        conflatedCallbackFlow {
                val cb = NtnSignalStrengthCallback { signalStrength ->
                    verboseLogBuffer.i({ int1 = signalStrength.level }) {
                        "onNtnSignalStrengthChanged: level=$int1"
                    }
                    trySend(signalStrength.level)
                }

                var registered = false
                try {
                    sm.registerForNtnSignalStrengthChanged(bgDispatcher.asExecutor(), cb)
                    registered = true
                    logBuffer.i { "Registered for signal strength successfully" }
                } catch (e: Exception) {
                    logBuffer.e("error registering for signal strength", e)
                }

                awaitClose {
                    if (registered) {
                        sm.unregisterForNtnSignalStrengthChanged(cb)
                        logBuffer.i { "Unregistered for signal strength successfully" }
                    }
                }
            }
            .flowOn(bgDispatcher)

    /** Fire off a request to check for satellite availability. Always runs on the bg context */
    private suspend fun checkIsSatelliteAllowed() =
        withContext(bgDispatcher) {
            satelliteManager?.requestIsCommunicationAllowedForCurrentLocation(
                bgDispatcher.asExecutor(),
                object : OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> {
                    override fun onError(e: SatelliteManager.SatelliteException) {
                        logBuffer.e(
                            "Found exception when checking availability",
                            e,
                        )
                        isSatelliteAllowedForCurrentLocation.value = false
                    }

                    override fun onResult(allowed: Boolean) {
                        logBuffer.i { "isSatelliteAllowedForCurrentLocation: $allowed" }
                        isSatelliteAllowedForCurrentLocation.value = allowed
                    }
                }
            )
        }

    private suspend fun SatelliteManager.checkSatelliteSupported(): SatelliteSupport =
        suspendCancellableCoroutine { continuation ->
            val cb =
                object : OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> {
                    override fun onResult(supported: Boolean) {
                        continuation.resume(
                            if (supported) {
                                Supported(satelliteManager = this@checkSatelliteSupported)
                            } else {
                                NotSupported
                            }
                        )
                    }

                    override fun onError(error: SatelliteManager.SatelliteException) {
                        logBuffer.e(
                            "Exception when checking for satellite support. " +
                                "Assuming it is not supported for this device.",
                            error,
                        )

                        // Assume that an error means it's not supported
                        continuation.resume(NotSupported)
                    }
                }

            try {
                requestIsSupported(bgDispatcher.asExecutor(), cb)
            } catch (error: Exception) {
                logBuffer.e(
                    "Exception when checking for satellite support. " +
                        "Assuming it is not supported for this device.",
                    error,
                )
                continuation.resume(NotSupported)
            }
        }

    companion object {
        // TTL for satellite polling is twenty minutes
        const val POLLING_INTERVAL_MS: Long = 1000 * 60 * 20

        // Let the system boot up and stabilize before we check for system support
        const val MIN_UPTIME: Long = 1000 * 60

        private const val TAG = "DeviceBasedSatelliteRepo"

        /** Calculates how long we have to wait to reach MIN_UPTIME */
        private fun ensureMinUptime(clock: SystemClock, uptime: Long): Long =
            uptime - (clock.uptimeMillis() - android.os.Process.getStartUptimeMillis())

        /** A couple of convenience logging methods rather than a whole class */
        private fun LogBuffer.i(
            initializer: MessageInitializer = {},
            printer: MessagePrinter,
        ) = this.log(TAG, LogLevel.INFO, initializer, printer)

        private fun LogBuffer.e(message: String, exception: Throwable? = null) =
            this.log(
                tag = TAG,
                level = LogLevel.ERROR,
                message = message,
                exception = exception,
            )
    }
}
