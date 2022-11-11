/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.clocks

import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import androidx.annotation.OpenForTesting
import com.android.internal.annotations.Keep
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import org.json.JSONObject

private val TAG = ClockRegistry::class.simpleName
private const val DEBUG = true

/** ClockRegistry aggregates providers and plugins */
open class ClockRegistry(
    val context: Context,
    val pluginManager: PluginManager,
    val handler: Handler,
    val isEnabled: Boolean,
    userHandle: Int,
    defaultClockProvider: ClockProvider,
    val fallbackClockId: ClockId = DEFAULT_CLOCK_ID,
) {
    // Usually this would be a typealias, but a SAM provides better java interop
    fun interface ClockChangeListener {
        fun onClockChanged()
    }

    private val availableClocks = mutableMapOf<ClockId, ClockInfo>()
    private val clockChangeListeners = mutableListOf<ClockChangeListener>()
    private val settingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int, userId: Int) =
            clockChangeListeners.forEach { it.onClockChanged() }
    }

    private val pluginListener = object : PluginListener<ClockProviderPlugin> {
        override fun onPluginConnected(plugin: ClockProviderPlugin, context: Context) =
            connectClocks(plugin)

        override fun onPluginDisconnected(plugin: ClockProviderPlugin) =
            disconnectClocks(plugin)
    }

    open var currentClockId: ClockId
        get() {
            return try {
                val json = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE
                )
                if (json == null || json.isEmpty()) {
                    return fallbackClockId
                }
                ClockSetting.deserialize(json).clockId
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to parse clock setting", ex)
                fallbackClockId
            }
        }
        set(value) {
            try {
                val json = ClockSetting.serialize(ClockSetting(value, System.currentTimeMillis()))
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, json
                )
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to set clock setting", ex)
            }
        }

    init {
        connectClocks(defaultClockProvider)
        if (!availableClocks.containsKey(DEFAULT_CLOCK_ID)) {
            throw IllegalArgumentException(
                "$defaultClockProvider did not register clock at $DEFAULT_CLOCK_ID"
            )
        }

        if (isEnabled) {
            pluginManager.addPluginListener(
                pluginListener,
                ClockProviderPlugin::class.java,
                /*allowMultiple=*/ true
            )
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                /*notifyForDescendants=*/ false,
                settingObserver,
                userHandle
            )
        }
    }

    private fun connectClocks(provider: ClockProvider) {
        val currentId = currentClockId
        for (clock in provider.getClocks()) {
            val id = clock.clockId
            val current = availableClocks[id]
            if (current != null) {
                Log.e(
                    TAG,
                    "Clock Id conflict: $id is registered by both " +
                        "${provider::class.simpleName} and ${current.provider::class.simpleName}"
                )
                return
            }

            availableClocks[id] = ClockInfo(clock, provider)
            if (DEBUG) {
                Log.i(TAG, "Added ${clock.clockId}")
            }

            if (currentId == id) {
                if (DEBUG) {
                    Log.i(TAG, "Current clock ($currentId) was connected")
                }
                clockChangeListeners.forEach { it.onClockChanged() }
            }
        }
    }

    private fun disconnectClocks(provider: ClockProvider) {
        val currentId = currentClockId
        for (clock in provider.getClocks()) {
            availableClocks.remove(clock.clockId)
            if (DEBUG) {
                Log.i(TAG, "Removed ${clock.clockId}")
            }

            if (currentId == clock.clockId) {
                Log.w(TAG, "Current clock ($currentId) was disconnected")
                clockChangeListeners.forEach { it.onClockChanged() }
            }
        }
    }

    @OpenForTesting
    open fun getClocks(): List<ClockMetadata> {
        if (!isEnabled) {
            return listOf(availableClocks[DEFAULT_CLOCK_ID]!!.metadata)
        }
        return availableClocks.map { (_, clock) -> clock.metadata }
    }

    fun getClockThumbnail(clockId: ClockId): Drawable? =
        availableClocks[clockId]?.provider?.getClockThumbnail(clockId)

    fun createExampleClock(clockId: ClockId): ClockController? = createClock(clockId)

    fun registerClockChangeListener(listener: ClockChangeListener) =
        clockChangeListeners.add(listener)

    fun unregisterClockChangeListener(listener: ClockChangeListener) =
        clockChangeListeners.remove(listener)

    fun createCurrentClock(): ClockController {
        val clockId = currentClockId
        if (isEnabled && clockId.isNotEmpty()) {
            val clock = createClock(clockId)
            if (clock != null) {
                if (DEBUG) {
                    Log.i(TAG, "Rendering clock $clockId")
                }
                return clock
            } else {
                Log.e(TAG, "Clock $clockId not found; using default")
            }
        }

        return createClock(DEFAULT_CLOCK_ID)!!
    }

    private fun createClock(clockId: ClockId): ClockController? =
        availableClocks[clockId]?.provider?.createClock(clockId)

    private data class ClockInfo(
        val metadata: ClockMetadata,
        val provider: ClockProvider
    )

    @Keep
    data class ClockSetting(
        val clockId: ClockId,
        val _applied_timestamp: Long?
    ) {
        companion object {
            private val KEY_CLOCK_ID = "clockId"
            private val KEY_TIMESTAMP = "_applied_timestamp"

            fun serialize(setting: ClockSetting): String {
                return JSONObject()
                    .put(KEY_CLOCK_ID, setting.clockId)
                    .put(KEY_TIMESTAMP, setting._applied_timestamp)
                    .toString()
            }

            fun deserialize(jsonStr: String): ClockSetting {
                val json = JSONObject(jsonStr)
                return ClockSetting(
                    json.getString(KEY_CLOCK_ID),
                    if (!json.isNull(KEY_TIMESTAMP)) json.getLong(KEY_TIMESTAMP) else null)
            }
        }
    }
}
