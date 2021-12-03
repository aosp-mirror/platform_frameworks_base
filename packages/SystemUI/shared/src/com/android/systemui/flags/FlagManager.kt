/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONException
import org.json.JSONObject
import java.util.function.Consumer

class FlagManager constructor(
    private val context: Context,
    private val settings: FlagSettingsHelper,
    private val handler: Handler
) : FlagListenable {
    companion object {
        const val RECEIVING_PACKAGE = "com.android.systemui"
        const val ACTION_SET_FLAG = "com.android.systemui.action.SET_FLAG"
        const val ACTION_GET_FLAGS = "com.android.systemui.action.GET_FLAGS"
        const val FLAGS_PERMISSION = "com.android.systemui.permission.FLAGS"
        const val FIELD_ID = "id"
        const val FIELD_VALUE = "value"
        const val FIELD_TYPE = "type"
        const val FIELD_FLAGS = "flags"
        const val TYPE_BOOLEAN = "boolean"
        private const val SETTINGS_PREFIX = "systemui/flags"
    }

    constructor(context: Context, handler: Handler) : this(
        context,
        FlagSettingsHelper(context.contentResolver),
        handler
    )

    /**
     * An action called on restart which takes as an argument whether the listeners requested
     * that the restart be suppressed
     */
    var restartAction: Consumer<Boolean>? = null
    var clearCacheAction: Consumer<Int>? = null
    private val listeners: MutableSet<PerFlagListener> = mutableSetOf()
    private val settingsObserver: ContentObserver = SettingsObserver()

    fun getFlagsFuture(): ListenableFuture<Collection<Flag<*>>> {
        val intent = Intent(ACTION_GET_FLAGS)
        intent.setPackage(RECEIVING_PACKAGE)

        return CallbackToFutureAdapter.getFuture {
            completer: CallbackToFutureAdapter.Completer<Any?> ->
                context.sendOrderedBroadcast(intent, null,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val extras: Bundle? = getResultExtras(false)
                            val listOfFlags: java.util.ArrayList<ParcelableFlag<*>>? =
                                extras?.getParcelableArrayList(FIELD_FLAGS)
                            if (listOfFlags != null) {
                                completer.set(listOfFlags)
                            } else {
                                completer.setException(NoFlagResultsException())
                            }
                        }
                    }, null, Activity.RESULT_OK, "extra data", null)
            "QueryingFlags"
        } as ListenableFuture<Collection<Flag<*>>>
    }

    fun setFlagValue(id: Int, enabled: Boolean) {
        val intent = createIntent(id)
        intent.putExtra(FIELD_VALUE, enabled)

        context.sendBroadcast(intent)
    }

    fun eraseFlag(id: Int) {
        val intent = createIntent(id)

        context.sendBroadcast(intent)
    }

    /** Returns the stored value or null if not set.  */
    fun isEnabled(id: Int): Boolean? {
        val data = settings.getString(keyToSettingsPrefix(id))
        if (data == null || data?.isEmpty()) {
            return null
        }
        val json: JSONObject
        try {
            json = JSONObject(data)
            return if (!assertType(json, TYPE_BOOLEAN)) {
                null
            } else json.getBoolean(FIELD_VALUE)
        } catch (e: JSONException) {
            throw InvalidFlagStorageException()
        }
    }

    override fun addListener(flag: Flag<*>, listener: FlagListenable.Listener) {
        synchronized(listeners) {
            val registerNeeded = listeners.isEmpty()
            listeners.add(PerFlagListener(flag.id, listener))
            if (registerNeeded) {
                settings.registerContentObserver(SETTINGS_PREFIX, true, settingsObserver)
            }
        }
    }

    override fun removeListener(listener: FlagListenable.Listener) {
        synchronized(listeners) {
            if (listeners.isEmpty()) {
                return
            }
            listeners.removeIf { it.listener == listener }
            if (listeners.isEmpty()) {
                settings.unregisterContentObserver(settingsObserver)
            }
        }
    }

    private fun createIntent(id: Int): Intent {
        val intent = Intent(ACTION_SET_FLAG)
        intent.setPackage(RECEIVING_PACKAGE)
        intent.putExtra(FIELD_ID, id)

        return intent
    }

    fun keyToSettingsPrefix(key: Int): String {
        return "$SETTINGS_PREFIX/$key"
    }

    private fun assertType(json: JSONObject, type: String): Boolean {
        return try {
            json.getString(FIELD_TYPE) == TYPE_BOOLEAN
        } catch (e: JSONException) {
            false
        }
    }

    inner class SettingsObserver : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri == null) {
                return
            }
            val parts = uri.pathSegments
            val idStr = parts[parts.size - 1]
            val id = try { idStr.toInt() } catch (e: NumberFormatException) { return }
            clearCacheAction?.accept(id)
            dispatchListenersAndMaybeRestart(id)
        }
    }

    fun dispatchListenersAndMaybeRestart(id: Int) {
        val filteredListeners: List<FlagListenable.Listener> = synchronized(listeners) {
            listeners.mapNotNull { if (it.id == id) it.listener else null }
        }
        // If there are no listeners, there's nothing to dispatch to, and nothing to suppress it.
        if (filteredListeners.isEmpty()) {
            restartAction?.accept(false)
            return
        }
        // Dispatch to every listener and save whether each one called requestNoRestart.
        val suppressRestartList: List<Boolean> = filteredListeners.map { listener ->
            var didRequestNoRestart = false
            val event = object : FlagListenable.FlagEvent {
                override val flagId = id
                override fun requestNoRestart() {
                    didRequestNoRestart = true
                }
            }
            listener.onFlagChanged(event)
            didRequestNoRestart
        }
        // Suppress restart only if ALL listeners request it.
        val suppressRestart = suppressRestartList.all { it }
        restartAction?.accept(suppressRestart)
    }

    private data class PerFlagListener(val id: Int, val listener: FlagListenable.Listener)
}

class InvalidFlagStorageException : Exception("Data found but is invalid")

class NoFlagResultsException : Exception(
    "SystemUI failed to communicate its flags back successfully")