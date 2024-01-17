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
import android.telephony.satellite.NtnSignalStrengthCallback
import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteModemStateCallback
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Companion.whenSupported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.NotSupported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Supported
import com.android.systemui.statusbar.pipeline.satellite.data.prod.SatelliteSupport.Unknown
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.util.kotlin.getOrNull
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
        /** Convenience function to switch to the supported flow */
        fun <T> Flow<SatelliteSupport>.whenSupported(
            supported: (SatelliteManager) -> Flow<T>,
            orElse: Flow<T>,
        ): Flow<T> = flatMapLatest {
            when (it) {
                is Supported -> supported(it.satelliteManager)
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
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val systemClock: SystemClock,
) : DeviceBasedSatelliteRepository {

    private val satelliteManager: SatelliteManager?

    override val isSatelliteAllowedForCurrentLocation: MutableStateFlow<Boolean>

    // Some calls into satellite manager will throw exceptions if it is not supported.
    // This is never expected to change after boot, but may need to be retried in some cases
    @get:VisibleForTesting
    val satelliteSupport: MutableStateFlow<SatelliteSupport> = MutableStateFlow(Unknown)

    init {
        satelliteManager = satelliteManagerOpt.getOrNull()

        isSatelliteAllowedForCurrentLocation = MutableStateFlow(false)

        if (satelliteManager != null) {
            // First, check that satellite is supported on this device
            scope.launch {
                ensureMinUptime(systemClock, MIN_UPTIME)
                satelliteSupport.value = satelliteManager.checkSatelliteSupported()

                // We only need to check location availability if this mode is supported
                if (satelliteSupport.value is Supported) {
                    isSatelliteAllowedForCurrentLocation.subscriptionCount
                        .map { it > 0 }
                        .distinctUntilChanged()
                        .collectLatest { hasSubscribers ->
                            if (hasSubscribers) {
                                /*
                                 * As there is no listener available for checking satellite allowed,
                                 * we must poll. Defaulting to polling at most once every hour while
                                 * active. Subsequent OOS events will restart the job, so a flaky
                                 * connection might cause more frequent checks.
                                 */
                                while (true) {
                                    checkIsSatelliteAllowed()
                                    delay(POLLING_INTERVAL_MS)
                                }
                            }
                        }
                }
            }
        } else {
            satelliteSupport.value = NotSupported
        }
    }

    override val connectionState =
        satelliteSupport.whenSupported(
            supported = ::connectionStateFlow,
            orElse = flowOf(SatelliteConnectionState.Off)
        )

    // By using the SupportedSatelliteManager here, we expect registration never to fail
    private fun connectionStateFlow(sm: SupportedSatelliteManager): Flow<SatelliteConnectionState> =
        conflatedCallbackFlow {
                val cb = SatelliteModemStateCallback { state ->
                    trySend(SatelliteConnectionState.fromModemState(state))
                }

                sm.registerForSatelliteModemStateChanged(bgDispatcher.asExecutor(), cb)

                awaitClose { sm.unregisterForSatelliteModemStateChanged(cb) }
            }
            .flowOn(bgDispatcher)

    override val signalStrength =
        satelliteSupport.whenSupported(supported = ::signalStrengthFlow, orElse = flowOf(0))

    // By using the SupportedSatelliteManager here, we expect registration never to fail
    private fun signalStrengthFlow(sm: SupportedSatelliteManager) =
        conflatedCallbackFlow {
                val cb = NtnSignalStrengthCallback { signalStrength ->
                    trySend(signalStrength.level)
                }

                sm.registerForNtnSignalStrengthChanged(bgDispatcher.asExecutor(), cb)

                awaitClose { sm.unregisterForNtnSignalStrengthChanged(cb) }
            }
            .flowOn(bgDispatcher)

    /** Fire off a request to check for satellite availability. Always runs on the bg context */
    private suspend fun checkIsSatelliteAllowed() =
        withContext(bgDispatcher) {
            satelliteManager?.requestIsSatelliteCommunicationAllowedForCurrentLocation(
                bgDispatcher.asExecutor(),
                object : OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> {
                    override fun onError(e: SatelliteManager.SatelliteException) {
                        android.util.Log.e(TAG, "Found exception when checking for satellite: ", e)
                        isSatelliteAllowedForCurrentLocation.value = false
                    }

                    override fun onResult(allowed: Boolean) {
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
                        // Assume that an error means it's not supported
                        continuation.resume(NotSupported)
                    }
                }

            try {
                requestIsSatelliteSupported(bgDispatcher.asExecutor(), cb)
            } catch (e: Exception) {
                continuation.resume(NotSupported)
            }
        }

    companion object {
        // TTL for satellite polling is one hour
        const val POLLING_INTERVAL_MS: Long = 1000 * 60 * 60

        // Let the system boot up and stabilize before we check for system support
        const val MIN_UPTIME: Long = 1000 * 60

        private const val TAG = "DeviceBasedSatelliteRepo"

        /** If our process hasn't been up for at least MIN_UPTIME, delay until we reach that time */
        private suspend fun ensureMinUptime(clock: SystemClock, uptime: Long) {
            val timeTilMinUptime =
                uptime - (clock.uptimeMillis() - android.os.Process.getStartUptimeMillis())
            if (timeTilMinUptime > 0) {
                delay(timeTilMinUptime)
            }
        }
    }
}
