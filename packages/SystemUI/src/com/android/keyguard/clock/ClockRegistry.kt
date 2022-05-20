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
package com.android.keyguard.clock

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.shared.plugins.PluginManager
import javax.inject.Inject

private val TAG = ClockRegistry::class.simpleName
private const val DEFAULT_CLOCK_ID = "DEFAULT"

typealias ClockChangeListener = () -> Unit

/** ClockRegistry aggregates providers and plugins */
// TODO: Is this the right place for this?
@SysUISingleton
class ClockRegistry @Inject constructor(
    val context: Context,
    val pluginManager: PluginManager
) {
    val pluginListener = object : PluginListener<ClockProviderPlugin> {
        override fun onPluginConnected(plugin: ClockProviderPlugin, context: Context) {
            for (clock in plugin.getClocks()) {
                val id = clock.clockId
                val current = availableClocks[id]
                if (current != null) {
                    Log.e(TAG, "Clock Id conflict: $id is registered by both " +
                            "${plugin::class.simpleName} and ${current.provider::class.simpleName}")
                    return
                }

                availableClocks[id] = ClockInfo(clock, plugin)
            }
        }

        override fun onPluginDisconnected(plugin: ClockProviderPlugin) {
            for (clock in plugin.getClocks()) {
                availableClocks.remove(clock.clockId)
            }
        }
    }

    private val availableClocks = mutableMapOf<ClockId, ClockInfo>()

    init {
        pluginManager.addPluginListener(pluginListener, ClockProviderPlugin::class.java)
        // TODO: Register Settings ContentObserver
    }

    fun getClocks(): List<ClockMetadata> = availableClocks.map { (_, clock) -> clock.metadata }

    fun getClockThumbnail(clockId: ClockId): Drawable? =
        availableClocks[clockId]?.provider?.getClockThumbnail(clockId)

    fun createExampleClock(clockId: ClockId): Clock? = createClock(clockId)

    fun getCurrentClock(): Clock {
        val clockId = "" // TODO: Load setting
        if (!clockId.isNullOrEmpty()) {
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

    fun setCurrentClock(clockId: ClockId) {
        // TODO: Write Setting
    }

    private data class ClockInfo(
        val metadata: ClockMetadata,
        val provider: ClockProvider
    )
}