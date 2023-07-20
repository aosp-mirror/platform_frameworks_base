/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.DeviceConfig
import android.provider.Settings
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject

@SysUISingleton
class PrivacyConfig @Inject constructor(
    @Main private val uiExecutor: DelayableExecutor,
    private val secureSettings: SecureSettings,
    @Main handler: Handler,
    dumpManager: DumpManager
) : Dumpable {

    @VisibleForTesting
    internal companion object {
        const val TAG = "PrivacyConfig"
    }

    private val callbacks = mutableListOf<WeakReference<Callback>>()

    var micCameraAvailable = isMicCameraEnabled()
        private set
    var locationAvailable = isLocationEnabled()
        private set
    var mediaProjectionAvailable = isMediaProjectionEnabled()
        private set

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            micCameraAvailable = isMicCameraEnabled()
            locationAvailable = isLocationEnabled()
            mediaProjectionAvailable = isMediaProjectionEnabled()
            callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
            callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
            callbacks.forEach { it.get()?.onFlagMediaProjectionChanged(mediaProjectionAvailable) }
        }
    }

    init {
        dumpManager.registerDumpable(TAG, this)
        secureSettings.registerContentObserverForUser(
            Settings.Secure.ENABLE_LOCATION_PRIVACY_INDICATOR,
            settingsObserver, UserHandle.USER_CURRENT
        )
        secureSettings.registerContentObserverForUser(
            Settings.Secure.ENABLE_CAMERA_PRIVACY_INDICATOR,
            settingsObserver, UserHandle.USER_CURRENT
        )
        secureSettings.registerContentObserverForUser(
            Settings.Secure.ENABLE_PROJECTION_PRIVACY_INDICATOR,
            settingsObserver, UserHandle.USER_CURRENT
        )
    }

    private fun isMicCameraEnabled(): Boolean {
        return  secureSettings.getIntForUser(
            Settings.Secure.ENABLE_CAMERA_PRIVACY_INDICATOR, 1, UserHandle.USER_CURRENT) == 1
    }

    private fun isLocationEnabled(): Boolean {
        return secureSettings.getIntForUser(
            Settings.Secure.ENABLE_LOCATION_PRIVACY_INDICATOR, 1, UserHandle.USER_CURRENT) == 1
    }

    private fun isMediaProjectionEnabled(): Boolean {
        return secureSettings.getIntForUser(
            Settings.Secure.ENABLE_PROJECTION_PRIVACY_INDICATOR, 1, UserHandle.USER_CURRENT) == 1
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            callbacks.add(callback)
        }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        uiExecutor.execute {
            // Removes also if the callback is null
            callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("PrivacyConfig state:")
        ipw.withIncreasedIndent {
            ipw.println("micCameraAvailable: $micCameraAvailable")
            ipw.println("locationAvailable: $locationAvailable")
            ipw.println("mediaProjectionAvailable: $mediaProjectionAvailable")
            ipw.println("Callbacks:")
            ipw.withIncreasedIndent {
                callbacks.forEach { callback ->
                    callback.get()?.let { ipw.println(it) }
                }
            }
        }
        ipw.flush()
    }

    interface Callback {
        @JvmDefault
        fun onFlagMicCameraChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagLocationChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagMediaProjectionChanged(flag: Boolean) {}
    }
}
