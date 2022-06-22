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
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.shared.plugins.PluginManager
import com.google.gson.Gson
import javax.inject.Inject

private val TAG = ClockRegistry::class.simpleName
private val DEBUG = true

/** ClockRegistry aggregates providers and plugins */
open class ClockRegistry(
    val context: Context,
    val pluginManager: PluginManager,
    val handler: Handler,
    defaultClockProvider: ClockProvider
) {
    @Inject constructor(
        context: Context,
        pluginManager: PluginManager,
        @Main handler: Handler,
        defaultClockProvider: DefaultClockProvider
    ) : this(context, pluginManager, handler, defaultClockProvider as ClockProvider) { }

    // Usually this would be a typealias, but a SAM provides better java interop
    fun interface ClockChangeListener {
        fun onClockChanged()
    }

    var isEnabled: Boolean = false

    private val gson = Gson()
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
            val json = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE
            )
            return gson.fromJson(json, ClockSetting::class.java)?.clockId ?: DEFAULT_CLOCK_ID
        }
        set(value) {
            val json = gson.toJson(ClockSetting(value, System.currentTimeMillis()))
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, json
            )
        }

    init {
        connectClocks(defaultClockProvider)
        if (!availableClocks.containsKey(DEFAULT_CLOCK_ID)) {
            throw IllegalArgumentException(
                "$defaultClockProvider did not register clock at $DEFAULT_CLOCK_ID"
            )
        }

        pluginManager.addPluginListener(pluginListener, ClockProviderPlugin::class.java)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
            false,
            settingObserver,
            UserHandle.USER_ALL
        )
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

            if (currentId == clock.clockId) {
                Log.w(TAG, "Current clock ($currentId) was disconnected")
                clockChangeListeners.forEach { it.onClockChanged() }
            }
        }
    }

    fun getClocks(): List<ClockMetadata> {
        if (!isEnabled) {
            return listOf(availableClocks[DEFAULT_CLOCK_ID]!!.metadata)
        }
        return availableClocks.map { (_, clock) -> clock.metadata }
    }

    fun getClockThumbnail(clockId: ClockId): Drawable? =
        availableClocks[clockId]?.provider?.getClockThumbnail(clockId)

    fun createExampleClock(clockId: ClockId): Clock? = createClock(clockId)

    fun registerClockChangeListener(listener: ClockChangeListener) =
        clockChangeListeners.add(listener)

    fun unregisterClockChangeListener(listener: ClockChangeListener) =
        clockChangeListeners.remove(listener)

    fun createCurrentClock(): Clock {
        val clockId = currentClockId
        if (isEnabled && clockId.isNotEmpty()) {
            val clock = createClock(clockId)
            if (clock != null) {
                return clock
            } else {
                Log.e(TAG, "Clock $clockId not found; using default")
            }
        }

        return createClock(DEFAULT_CLOCK_ID)!!
    }

    private fun createClock(clockId: ClockId): Clock? =
        availableClocks[clockId]?.provider?.createClock(clockId)

    private data class ClockInfo(
        val metadata: ClockMetadata,
        val provider: ClockProvider
    )

    private data class ClockSetting(
        val clockId: ClockId,
        val _applied_timestamp: Long
    )
}
