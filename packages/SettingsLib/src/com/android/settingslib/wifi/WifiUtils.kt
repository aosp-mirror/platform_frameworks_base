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
package com.android.settingslib.wifi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.icu.text.MessageFormat
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus
import android.net.wifi.WifiManager
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.android.settingslib.R
import com.android.settingslib.flags.Flags.newStatusBarIcons
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext


open class WifiUtils {
    /**
     * Wrapper the [.getInternetIconResource] for testing compatibility.
     */
    class InternetIconInjector(protected val context: Context) {
        /**
         * Returns the Internet icon for a given RSSI level.
         *
         * @param noInternet True if a connected Wi-Fi network cannot access the Internet
         * @param level The number of bars to show (0-4)
         */
        fun getIcon(noInternet: Boolean, level: Int): Drawable? {
            return context.getDrawable(getInternetIconResource(level, noInternet))
        }
    }

    companion object {
        private const val TAG = "WifiUtils"
        private const val INVALID_RSSI = -127

        /**
         * The intent action shows Wi-Fi dialog to connect Wi-Fi network.
         *
         *
         * Input: The calling package should put the chosen
         * com.android.wifitrackerlib.WifiEntry#getKey() to a string extra in the request bundle into
         * the [.EXTRA_CHOSEN_WIFI_ENTRY_KEY].
         *
         *
         * Output: Nothing.
         */
        @JvmField
        @VisibleForTesting
        val ACTION_WIFI_DIALOG = "com.android.settings.WIFI_DIALOG"

        /**
         * Specify a key that indicates the WifiEntry to be configured.
         */
        @JvmField
        @VisibleForTesting
        val EXTRA_CHOSEN_WIFI_ENTRY_KEY = "key_chosen_wifientry_key"

        /**
         * The lookup key for a boolean that indicates whether a chosen WifiEntry request to connect to.
         * `true` means a chosen WifiEntry request to connect to.
         */
        @JvmField
        @VisibleForTesting
        val EXTRA_CONNECT_FOR_CALLER = "connect_for_caller"

        /**
         * The intent action shows network details settings to allow configuration of Wi-Fi.
         *
         *
         * In some cases, a matching Activity may not exist, so ensure you
         * safeguard against this.
         *
         *
         * Input: The calling package should put the chosen
         * com.android.wifitrackerlib.WifiEntry#getKey() to a string extra in the request bundle into
         * the [.KEY_CHOSEN_WIFIENTRY_KEY].
         *
         *
         * Output: Nothing.
         */
        const val ACTION_WIFI_DETAILS_SETTINGS = "android.settings.WIFI_DETAILS_SETTINGS"
        const val KEY_CHOSEN_WIFIENTRY_KEY = "key_chosen_wifientry_key"
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        @JvmField
        val WIFI_PIE = getIconsBasedOnFlag()

        private fun getIconsBasedOnFlag(): IntArray {
            return if (newStatusBarIcons()) {
                intArrayOf(
                    R.drawable.ic_wifi_0,
                    R.drawable.ic_wifi_1,
                    R.drawable.ic_wifi_2,
                    R.drawable.ic_wifi_3,
                    R.drawable.ic_wifi_4
                )
            } else {
                intArrayOf(
                    com.android.internal.R.drawable.ic_wifi_signal_0,
                    com.android.internal.R.drawable.ic_wifi_signal_1,
                    com.android.internal.R.drawable.ic_wifi_signal_2,
                    com.android.internal.R.drawable.ic_wifi_signal_3,
                    com.android.internal.R.drawable.ic_wifi_signal_4
                )
            }
        }

        val NO_INTERNET_WIFI_PIE = getErrorIconsBasedOnFlag()

        private fun getErrorIconsBasedOnFlag(): IntArray {
            return if (newStatusBarIcons()) {
                intArrayOf(
                    R.drawable.ic_wifi_0_error,
                    R.drawable.ic_wifi_1_error,
                    R.drawable.ic_wifi_2_error,
                    R.drawable.ic_wifi_3_error,
                    R.drawable.ic_wifi_4_error
                )
            } else {
                intArrayOf(
                    R.drawable.ic_no_internet_wifi_signal_0,
                    R.drawable.ic_no_internet_wifi_signal_1,
                    R.drawable.ic_no_internet_wifi_signal_2,
                    R.drawable.ic_no_internet_wifi_signal_3,
                    R.drawable.ic_no_internet_wifi_signal_4
                )
            }
        }

        @JvmStatic
        fun buildLoggingSummary(accessPoint: AccessPoint, config: WifiConfiguration?): String {
            val summary = StringBuilder()
            val info = accessPoint.info
            // Add RSSI/band information for this config, what was seen up to 6 seconds ago
            // verbose WiFi Logging is only turned on thru developers settings
            if (accessPoint.isActive && info != null) {
                summary.append(" f=" + info.frequency.toString())
            }
            summary.append(" " + getVisibilityStatus(accessPoint))
            if (config != null && (config.networkSelectionStatus.networkSelectionStatus
                    != NetworkSelectionStatus.NETWORK_SELECTION_ENABLED)
            ) {
                summary.append(" (" + config.networkSelectionStatus.networkStatusString)
                if (config.networkSelectionStatus.disableTime > 0) {
                    val now = System.currentTimeMillis()
                    val diff = (now - config.networkSelectionStatus.disableTime) / 1000
                    val sec = diff % 60 // seconds
                    val min = diff / 60 % 60 // minutes
                    val hour = min / 60 % 60 // hours
                    summary.append(", ")
                    if (hour > 0) summary.append(hour.toString() + "h ")
                    summary.append(min.toString() + "m ")
                    summary.append(sec.toString() + "s ")
                }
                summary.append(")")
            }
            if (config != null) {
                val networkStatus = config.networkSelectionStatus
                for (reason in 0..NetworkSelectionStatus.getMaxNetworkSelectionDisableReason()) {
                    if (networkStatus.getDisableReasonCounter(reason) != 0) {
                        summary.append(" ")
                            .append(
                                NetworkSelectionStatus
                                    .getNetworkSelectionDisableReasonString(reason)
                            )
                            .append("=")
                            .append(networkStatus.getDisableReasonCounter(reason))
                    }
                }
            }
            return summary.toString()
        }

        /**
         * Returns the visibility status of the WifiConfiguration.
         *
         * @return autojoin debugging information
         * TODO: use a string formatter
         * ["rssi 5Ghz", "num results on 5GHz" / "rssi 5Ghz", "num results on 5GHz"]
         * For instance [-40,5/-30,2]
         */
        @JvmStatic
        @VisibleForTesting
        fun getVisibilityStatus(accessPoint: AccessPoint): String {
            val info = accessPoint.info
            val visibility = StringBuilder()
            val scans24GHz = StringBuilder()
            val scans5GHz = StringBuilder()
            val scans60GHz = StringBuilder()
            var bssid: String? = null
            if (accessPoint.isActive && info != null) {
                bssid = info.bssid
                if (bssid != null) {
                    visibility.append(" ").append(bssid)
                }
                visibility.append(" standard = ").append(info.wifiStandard)
                visibility.append(" rssi=").append(info.rssi)
                visibility.append(" ")
                visibility.append(" score=").append(info.getScore())
                if (accessPoint.speed != AccessPoint.Speed.NONE) {
                    visibility.append(" speed=").append(accessPoint.speedLabel)
                }
                visibility.append(String.format(" tx=%.1f,", info.successfulTxPacketsPerSecond))
                visibility.append(String.format("%.1f,", info.retriedTxPacketsPerSecond))
                visibility.append(String.format("%.1f ", info.lostTxPacketsPerSecond))
                visibility.append(String.format("rx=%.1f", info.successfulRxPacketsPerSecond))
            }
            var maxRssi5 = INVALID_RSSI
            var maxRssi24 = INVALID_RSSI
            var maxRssi60 = INVALID_RSSI
            val maxDisplayedScans = 4
            var num5 = 0 // number of scanned BSSID on 5GHz band
            var num24 = 0 // number of scanned BSSID on 2.4Ghz band
            var num60 = 0 // number of scanned BSSID on 60Ghz band
            val numBlockListed = 0

            // TODO: sort list by RSSI or age
            val nowMs = SystemClock.elapsedRealtime()
            for (result in accessPoint.getScanResults()) {
                if (result == null) {
                    continue
                }
                if (result.frequency >= AccessPoint.LOWER_FREQ_5GHZ &&
                    result.frequency <= AccessPoint.HIGHER_FREQ_5GHZ
                ) {
                    // Strictly speaking: [4915, 5825]
                    num5++
                    if (result.level > maxRssi5) {
                        maxRssi5 = result.level
                    }
                    if (num5 <= maxDisplayedScans) {
                        scans5GHz.append(
                            verboseScanResultSummary(
                                accessPoint, result, bssid,
                                nowMs
                            )
                        )
                    }
                } else if (result.frequency >= AccessPoint.LOWER_FREQ_24GHZ &&
                    result.frequency <= AccessPoint.HIGHER_FREQ_24GHZ
                ) {
                    // Strictly speaking: [2412, 2482]
                    num24++
                    if (result.level > maxRssi24) {
                        maxRssi24 = result.level
                    }
                    if (num24 <= maxDisplayedScans) {
                        scans24GHz.append(
                            verboseScanResultSummary(
                                accessPoint, result, bssid,
                                nowMs
                            )
                        )
                    }
                } else if (result.frequency >= AccessPoint.LOWER_FREQ_60GHZ &&
                    result.frequency <= AccessPoint.HIGHER_FREQ_60GHZ
                ) {
                    // Strictly speaking: [60000, 61000]
                    num60++
                    if (result.level > maxRssi60) {
                        maxRssi60 = result.level
                    }
                    if (num60 <= maxDisplayedScans) {
                        scans60GHz.append(
                            verboseScanResultSummary(
                                accessPoint, result, bssid,
                                nowMs
                            )
                        )
                    }
                }
            }
            visibility.append(" [")
            if (num24 > 0) {
                visibility.append("(").append(num24).append(")")
                if (num24 > maxDisplayedScans) {
                    visibility.append("max=").append(maxRssi24).append(",")
                }
                visibility.append(scans24GHz.toString())
            }
            visibility.append(";")
            if (num5 > 0) {
                visibility.append("(").append(num5).append(")")
                if (num5 > maxDisplayedScans) {
                    visibility.append("max=").append(maxRssi5).append(",")
                }
                visibility.append(scans5GHz.toString())
            }
            visibility.append(";")
            if (num60 > 0) {
                visibility.append("(").append(num60).append(")")
                if (num60 > maxDisplayedScans) {
                    visibility.append("max=").append(maxRssi60).append(",")
                }
                visibility.append(scans60GHz.toString())
            }
            if (numBlockListed > 0) {
                visibility.append("!").append(numBlockListed)
            }
            visibility.append("]")
            return visibility.toString()
        }

        @JvmStatic
        @VisibleForTesting /* package */ fun verboseScanResultSummary(
            accessPoint: AccessPoint,
            result: ScanResult,
            bssid: String?,
            nowMs: Long
        ): String {
            val stringBuilder = StringBuilder()
            stringBuilder.append(" \n{").append(result.BSSID)
            if (result.BSSID == bssid) {
                stringBuilder.append("*")
            }
            stringBuilder.append("=").append(result.frequency)
            stringBuilder.append(",").append(result.level)
            val speed = getSpecificApSpeed(result, accessPoint.scoredNetworkCache)
            if (speed != AccessPoint.Speed.NONE) {
                stringBuilder.append(",")
                    .append(accessPoint.getSpeedLabel(speed))
            }
            val ageSeconds = (nowMs - result.timestamp / 1000).toInt() / 1000
            stringBuilder.append(",").append(ageSeconds).append("s")
            stringBuilder.append("}")
            return stringBuilder.toString()
        }

        @AccessPoint.Speed
        private fun getSpecificApSpeed(
            result: ScanResult,
            scoredNetworkCache: Map<String, TimestampedScoredNetwork>
        ): Int {
            val timedScore = scoredNetworkCache[result.BSSID] ?: return AccessPoint.Speed.NONE
            // For debugging purposes we may want to use mRssi rather than result.level as the average
            // speed wil be determined by mRssi
            return timedScore.score.calculateBadge(result.level)
        }

        @JvmStatic
        fun getMeteredLabel(context: Context, config: WifiConfiguration): String {
            // meteredOverride is whether the user manually set the metered setting or not.
            // meteredHint is whether the network itself is telling us that it is metered
            return if (config.meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED ||
                config.meteredHint && !isMeteredOverridden(
                    config
                )
            ) {
                context.getString(R.string.wifi_metered_label)
            } else context.getString(R.string.wifi_unmetered_label)
        }

        /**
         * Returns the Internet icon resource for a given RSSI level.
         *
         * @param level The number of bars to show (0-4)
         * @param noInternet True if a connected Wi-Fi network cannot access the Internet
         */
        @JvmStatic
        fun getInternetIconResource(level: Int, noInternet: Boolean): Int {
            var wifiLevel = level
            if (wifiLevel < 0) {
                Log.e(TAG, "Wi-Fi level is out of range! level:$level")
                wifiLevel = 0
            } else if (level >= WIFI_PIE.size) {
                Log.e(TAG, "Wi-Fi level is out of range! level:$level")
                wifiLevel = WIFI_PIE.size - 1
            }
            return if (noInternet) NO_INTERNET_WIFI_PIE[wifiLevel] else WIFI_PIE[wifiLevel]
        }

        /**
         * Returns the Hotspot network icon resource.
         *
         * @param deviceType The device type of Hotspot network
         */
        @JvmStatic
        fun getHotspotIconResource(deviceType: Int): Int {
            return when (deviceType) {
                NetworkProviderInfo.DEVICE_TYPE_PHONE -> R.drawable.ic_hotspot_phone
                NetworkProviderInfo.DEVICE_TYPE_TABLET -> R.drawable.ic_hotspot_tablet
                NetworkProviderInfo.DEVICE_TYPE_LAPTOP -> R.drawable.ic_hotspot_laptop
                NetworkProviderInfo.DEVICE_TYPE_WATCH -> R.drawable.ic_hotspot_watch
                NetworkProviderInfo.DEVICE_TYPE_AUTO -> R.drawable.ic_hotspot_auto
                else -> R.drawable.ic_hotspot_phone
            }
        }

        @JvmStatic
        fun isMeteredOverridden(config: WifiConfiguration): Boolean {
            return config.meteredOverride != WifiConfiguration.METERED_OVERRIDE_NONE
        }

        /**
         * Returns the Intent for Wi-Fi dialog.
         *
         * @param key              The Wi-Fi entry key
         * @param connectForCaller True if a chosen WifiEntry request to connect to
         */
        @JvmStatic
        fun getWifiDialogIntent(key: String?, connectForCaller: Boolean): Intent {
            val intent = Intent(ACTION_WIFI_DIALOG)
            intent.putExtra(EXTRA_CHOSEN_WIFI_ENTRY_KEY, key)
            intent.putExtra(EXTRA_CONNECT_FOR_CALLER, connectForCaller)
            return intent
        }

        /**
         * Returns the Intent for Wi-Fi network details settings.
         *
         * @param key The Wi-Fi entry key
         */
        @JvmStatic
        fun getWifiDetailsSettingsIntent(key: String?): Intent {
            val intent = Intent(ACTION_WIFI_DETAILS_SETTINGS)
            val bundle = Bundle()
            bundle.putString(KEY_CHOSEN_WIFIENTRY_KEY, key)
            intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
            return intent
        }

        /**
         * Returns the string of Wi-Fi tethering summary for connected devices.
         *
         * @param context          The application context
         * @param connectedDevices The count of connected devices
         */
        @JvmStatic
        fun getWifiTetherSummaryForConnectedDevices(
            context: Context,
            connectedDevices: Int
        ): String {
            val msgFormat = MessageFormat(
                context.resources.getString(R.string.wifi_tether_connected_summary),
                Locale.getDefault()
            )
            val arguments: MutableMap<String, Any> = HashMap()
            arguments["count"] = connectedDevices
            return msgFormat.format(arguments)
        }

        @JvmStatic
        fun checkWepAllowed(
            context: Context,
            lifecycleOwner: LifecycleOwner,
            ssid: String,
            onAllowed: () -> Unit,
        ) {
            lifecycleOwner.lifecycleScope.launch {
                val wifiManager = context.getSystemService(WifiManager::class.java) ?: return@launch
                if (wifiManager.queryWepAllowed()) {
                    onAllowed()
                } else {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(
                            "com.android.settings",
                            "com.android.settings.network.WepNetworkDialogActivity"
                        )
                        putExtra(SSID, ssid)
                    }
                    context.startActivity(intent)
                }
            }
        }

        private suspend fun WifiManager.queryWepAllowed(): Boolean =
            withContext(Dispatchers.Default) {
                suspendCancellableCoroutine { continuation ->
                    queryWepAllowed(Dispatchers.Default.asExecutor()) {
                        continuation.resume(it)
                    }
                }
            }

        const val SSID = "ssid"
    }
}
