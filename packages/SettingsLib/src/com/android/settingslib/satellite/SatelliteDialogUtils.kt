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

package com.android.settingslib.satellite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.OutcomeReceiver
import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteModemStateCallback
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.android.settingslib.wifi.WifiUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/** A util for Satellite dialog */
object SatelliteDialogUtils {

    /**
     * Uses to start Satellite dialog to prevent users from using the BT, Airplane Mode, and
     * Wifi during the satellite mode is on.
     */
    @JvmStatic
    fun mayStartSatelliteWarningDialog(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            type: Int,
            allowClick: (isAllowed: Boolean) -> Unit
    ): Job {
        return mayStartSatelliteWarningDialog(
                context, lifecycleOwner.lifecycleScope, type, allowClick)
    }

    /**
     * Uses to start Satellite dialog to prevent users from using the BT, Airplane Mode, and
     * Wifi during the satellite mode is on.
     */
    @JvmStatic
    fun mayStartSatelliteWarningDialog(
            context: Context,
            coroutineScope: CoroutineScope,
            type: Int,
            allowClick: (isAllowed: Boolean) -> Unit
    ): Job =
            coroutineScope.launch {
                var isSatelliteModeOn = false
                try {
                    isSatelliteModeOn = requestIsSessionStarted(context)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Error to get satellite status : $e")
                } catch (e: ExecutionException) {
                    Log.w(TAG, "Error to get satellite status : $e")
                } catch (e: TimeoutException) {
                    Log.w(TAG, "Error to get satellite status : $e")
                }

                if (isSatelliteModeOn) {
                    startSatelliteWarningDialog(context, type)
                }
                withContext(Dispatchers.Main) {
                    allowClick(!isSatelliteModeOn)
                }
            }

    private fun startSatelliteWarningDialog(context: Context, type: Int) {
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.network.SatelliteWarningDialogActivity"
            )
            putExtra(WifiUtils.DIALOG_WINDOW_TYPE,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
            putExtra(EXTRA_TYPE_OF_SATELLITE_WARNING_DIALOG, type)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    /**
     * Checks if the satellite modem is enabled.
     *
     * @param executor The executor to run the asynchronous operation on
     * @return A ListenableFuture that will resolve to `true` if the satellite modem enabled,
     *         `false` otherwise.
     */
    private suspend fun requestIsEnabled(
            context: Context,
    ): Boolean = withContext(Default) {
        val satelliteManager: SatelliteManager? =
                context.getSystemService(SatelliteManager::class.java)
        if (satelliteManager == null) {
            Log.w(TAG, "SatelliteManager is null")
            return@withContext false
        }

        suspendCancellableCoroutine {continuation ->
            try {
                satelliteManager?.requestIsEnabled(Default.asExecutor(),
                        object : OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> {
                            override fun onResult(result: Boolean) {
                                Log.i(TAG, "Satellite modem enabled status: $result")
                                continuation.resume(result)
                            }

                            override fun onError(error: SatelliteManager.SatelliteException) {
                                super.onError(error)
                                Log.w(TAG, "Can't get satellite modem enabled status", error)
                                continuation.resume(false)
                            }
                        })
            } catch (e: IllegalStateException) {
                Log.w(TAG, "IllegalStateException: $e")
                continuation.resume(false)
            }
        }
    }

    private suspend fun requestIsSessionStarted(
            context: Context
    ): Boolean = withContext(Default) {
        val satelliteManager: SatelliteManager? =
                context.getSystemService(SatelliteManager::class.java)
        if (satelliteManager == null) {
            Log.w(TAG, "SatelliteManager is null")
            return@withContext false
        }

        getIsSessionStartedFlow(context).conflate().first()
    }

    /**
     * Provides a Flow that emits the session state of the satellite modem. Updates are triggered
     * when the modem state changes.
     *
     * @param defaultDispatcher The CoroutineDispatcher to use (Defaults to `Dispatchers.Default`).
     * @return A Flow emitting `true` when the session is started and `false` otherwise.
     */
    private fun getIsSessionStartedFlow(
            context: Context
    ): Flow<Boolean> {
        val satelliteManager: SatelliteManager? =
                context.getSystemService(SatelliteManager::class.java)
        if (satelliteManager == null) {
            Log.w(TAG, "SatelliteManager is null")
            return flowOf(false)
        }

        return callbackFlow {
            val callback = SatelliteModemStateCallback { state ->
                val isSessionStarted = isSatelliteSessionStarted(state)
                Log.i(TAG, "Satellite modem state changed: state=$state"
                        + ", isSessionStarted=$isSessionStarted")
                trySend(isSessionStarted)
            }

            var registerResult = SatelliteManager.SATELLITE_MODEM_STATE_UNKNOWN
            try {
                registerResult = satelliteManager.registerForModemStateChanged(
                        Default.asExecutor(),
                        callback
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "IllegalStateException: $e")
            }


            if (registerResult != SatelliteManager.SATELLITE_RESULT_SUCCESS) {
                // If the registration failed (e.g., device doesn't support satellite),
                // SatelliteManager will not emit the current state by callback.
                // We send `false` value by ourself to make sure the flow has initial value.
                Log.w(TAG, "Failed to register for satellite modem state change: $registerResult")
                trySend(false)
            }

            awaitClose {
                try {
                    satelliteManager.unregisterForModemStateChanged(callback)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "IllegalStateException: $e")
                }
            }
        }.flowOn(Default)
    }


    /**
     * Check if the modem is in a satellite session.
     *
     * @param state The SatelliteModemState provided by the SatelliteManager.
     * @return `true` if the modem is in a satellite session, `false` otherwise.
     */
    fun isSatelliteSessionStarted(@SatelliteManager.SatelliteModemState state: Int): Boolean {
        return when (state) {
            SatelliteManager.SATELLITE_MODEM_STATE_OFF,
            SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE,
            SatelliteManager.SATELLITE_MODEM_STATE_UNKNOWN -> false
            else -> true
        }
    }

    const val TAG = "SatelliteDialogUtils"

    const val EXTRA_TYPE_OF_SATELLITE_WARNING_DIALOG: String =
            "extra_type_of_satellite_warning_dialog"
    const val TYPE_IS_UNKNOWN = -1
    const val TYPE_IS_WIFI = 0
    const val TYPE_IS_BLUETOOTH = 1
    const val TYPE_IS_AIRPLANE_MODE = 2
}