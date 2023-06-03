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

import android.app.ActivityManager
import android.app.UserSwitchObserver
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.annotation.OpenForTesting
import com.android.systemui.plugins.ClockController
import com.android.systemui.plugins.ClockId
import com.android.systemui.plugins.ClockMetadata
import com.android.systemui.plugins.ClockProvider
import com.android.systemui.plugins.ClockProviderPlugin
import com.android.systemui.plugins.ClockSettings
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import com.android.systemui.util.Assert
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TAG = ClockRegistry::class.simpleName!!
private const val DEBUG = true
private val KEY_TIMESTAMP = "appliedTimestamp"

/** ClockRegistry aggregates providers and plugins */
open class ClockRegistry(
    val context: Context,
    val pluginManager: PluginManager,
    val scope: CoroutineScope,
    val mainDispatcher: CoroutineDispatcher,
    val bgDispatcher: CoroutineDispatcher,
    val isEnabled: Boolean,
    val handleAllUsers: Boolean,
    defaultClockProvider: ClockProvider,
    val fallbackClockId: ClockId = DEFAULT_CLOCK_ID,
) {
    interface ClockChangeListener {
        // Called when the active clock changes
        fun onCurrentClockChanged() {}

        // Called when the list of available clocks changes
        fun onAvailableClocksChanged() {}
    }

    private val availableClocks = mutableMapOf<ClockId, ClockInfo>()
    private val clockChangeListeners = mutableListOf<ClockChangeListener>()
    private val settingObserver =
        object : ContentObserver(null) {
            override fun onChange(
                selfChange: Boolean,
                uris: Collection<Uri>,
                flags: Int,
                userId: Int
            ) {
                scope.launch(bgDispatcher) { querySettings() }
            }
        }

    private val pluginListener =
        object : PluginListener<ClockProviderPlugin> {
            override fun onPluginConnected(plugin: ClockProviderPlugin, context: Context) =
                connectClocks(plugin)

            override fun onPluginDisconnected(plugin: ClockProviderPlugin) =
                disconnectClocks(plugin)
        }

    private val userSwitchObserver =
        object : UserSwitchObserver() {
            override fun onUserSwitchComplete(newUserId: Int) {
                scope.launch(bgDispatcher) { querySettings() }
            }
        }

    // TODO(b/267372164): Migrate to flows
    var settings: ClockSettings? = null
        get() = field
        protected set(value) {
            if (field != value) {
                field = value
                scope.launch(mainDispatcher) { onClockChanged { it.onCurrentClockChanged() } }
            }
        }

    var isRegistered: Boolean = false
        private set

    @OpenForTesting
    open fun querySettings() {
        assertNotMainThread()
        val result =
            try {
                val json =
                    if (handleAllUsers) {
                        Settings.Secure.getStringForUser(
                            context.contentResolver,
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                            ActivityManager.getCurrentUser()
                        )
                    } else {
                        Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE
                        )
                    }

                ClockSettings.deserialize(json)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to parse clock settings", ex)
                null
            }
        settings = result
    }

    @OpenForTesting
    open fun applySettings(value: ClockSettings?) {
        assertNotMainThread()

        try {
            value?.metadata?.put(KEY_TIMESTAMP, System.currentTimeMillis())

            val json = ClockSettings.serialize(value)
            if (handleAllUsers) {
                Settings.Secure.putStringForUser(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    json,
                    ActivityManager.getCurrentUser()
                )
            } else {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    json
                )
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to set clock settings", ex)
        }
        settings = value
    }

    @OpenForTesting
    protected open fun assertMainThread() {
        Assert.isMainThread()
    }

    @OpenForTesting
    protected open fun assertNotMainThread() {
        Assert.isNotMainThread()
    }

    private fun onClockChanged(func: (ClockChangeListener) -> Unit) {
        assertMainThread()
        clockChangeListeners.forEach(func)
    }

    public fun mutateSetting(mutator: (ClockSettings) -> ClockSettings) {
        scope.launch(bgDispatcher) { applySettings(mutator(settings ?: ClockSettings())) }
    }

    var currentClockId: ClockId
        get() = settings?.clockId ?: fallbackClockId
        set(value) {
            mutateSetting { it.copy(clockId = value) }
        }

    var seedColor: Int?
        get() = settings?.seedColor
        set(value) {
            mutateSetting { it.copy(seedColor = value) }
        }

    init {
        connectClocks(defaultClockProvider)
        if (!availableClocks.containsKey(DEFAULT_CLOCK_ID)) {
            throw IllegalArgumentException(
                "$defaultClockProvider did not register clock at $DEFAULT_CLOCK_ID"
            )
        }
    }

    fun registerListeners() {
        if (!isEnabled || isRegistered) {
            return
        }

        isRegistered = true

        pluginManager.addPluginListener(
            pluginListener,
            ClockProviderPlugin::class.java,
            /*allowMultiple=*/ true
        )

        scope.launch(bgDispatcher) { querySettings() }
        if (handleAllUsers) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                /*notifyForDescendants=*/ false,
                settingObserver,
                UserHandle.USER_ALL
            )

            ActivityManager.getService().registerUserSwitchObserver(userSwitchObserver, TAG)
        } else {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                /*notifyForDescendants=*/ false,
                settingObserver
            )
        }
    }

    fun unregisterListeners() {
        if (!isRegistered) {
            return
        }

        isRegistered = false

        pluginManager.removePluginListener(pluginListener)
        context.contentResolver.unregisterContentObserver(settingObserver)
        if (handleAllUsers) {
            ActivityManager.getService().unregisterUserSwitchObserver(userSwitchObserver)
        }
    }

    private fun connectClocks(provider: ClockProvider) {
        var isAvailableChanged = false
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
                continue
            }

            availableClocks[id] = ClockInfo(clock, provider)
            isAvailableChanged = true
            if (DEBUG) {
                Log.i(TAG, "Added ${clock.clockId}")
            }

            if (currentId == id) {
                if (DEBUG) {
                    Log.i(TAG, "Current clock ($currentId) was connected")
                }
                onClockChanged { it.onCurrentClockChanged() }
            }
        }

        if (isAvailableChanged) {
            onClockChanged { it.onAvailableClocksChanged() }
        }
    }

    private fun disconnectClocks(provider: ClockProvider) {
        var isAvailableChanged = false
        val currentId = currentClockId
        for (clock in provider.getClocks()) {
            availableClocks.remove(clock.clockId)
            isAvailableChanged = true

            if (DEBUG) {
                Log.i(TAG, "Removed ${clock.clockId}")
            }

            if (currentId == clock.clockId) {
                Log.w(TAG, "Current clock ($currentId) was disconnected")
                onClockChanged { it.onCurrentClockChanged() }
            }
        }

        if (isAvailableChanged) {
            onClockChanged { it.onAvailableClocksChanged() }
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

    private fun createClock(targetClockId: ClockId): ClockController? {
        var settings = this.settings ?: ClockSettings()
        if (targetClockId != settings.clockId) {
            settings = settings.copy(clockId = targetClockId)
        }
        return availableClocks[targetClockId]?.provider?.createClock(settings)
    }

    private data class ClockInfo(
        val metadata: ClockMetadata,
        val provider: ClockProvider,
    )
}
