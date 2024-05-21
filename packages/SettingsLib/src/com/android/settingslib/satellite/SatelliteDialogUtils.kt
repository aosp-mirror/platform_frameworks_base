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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

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
                    isSatelliteModeOn = requestIsEnabled(context)
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
