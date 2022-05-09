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

package com.android.systemui.qs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.util.ArraySet
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.UserAwareController
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "AutoAddTracker"

/**
 * Class to track tiles that have been auto-added
 *
 * The list is backed by [Settings.Secure.QS_AUTO_ADDED_TILES].
 *
 * It also handles restore gracefully.
 */
class AutoAddTracker @VisibleForTesting constructor(
    private val secureSettings: SecureSettings,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val qsHost: QSHost,
    private val dumpManager: DumpManager,
    private val mainHandler: Handler?,
    private val backgroundExecutor: Executor,
    private var userId: Int
) : UserAwareController, Dumpable {

    companion object {
        private val FILTER = IntentFilter(Intent.ACTION_SETTING_RESTORED)
    }

    @GuardedBy("autoAdded")
    private val autoAdded = ArraySet<String>()
    private var restoredTiles: Set<String>? = null

    override val currentUserId: Int
        get() = userId

    private val contentObserver = object : ContentObserver(mainHandler) {
        override fun onChange(
            selfChange: Boolean,
            uris: Collection<Uri>,
            flags: Int,
            _userId: Int
        ) {
            if (_userId != userId) {
                // Ignore changes outside of our user. We'll load the correct value on user change
                return
            }
            loadTiles()
        }
    }

    private val restoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SETTING_RESTORED) return
            processRestoreIntent(intent)
        }
    }

    private fun processRestoreIntent(intent: Intent) {
        when (intent.getStringExtra(Intent.EXTRA_SETTING_NAME)) {
            Settings.Secure.QS_TILES -> {
                restoredTiles = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE)
                        ?.split(",")
                        ?.toSet()
                        ?: run {
                            Log.w(TAG, "Null restored tiles for user $userId")
                            emptySet()
                        }
            }
            Settings.Secure.QS_AUTO_ADDED_TILES -> {
                restoredTiles?.let { tiles ->
                    val restoredAutoAdded = intent
                            .getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE)
                            ?.split(",")
                            ?: emptyList()
                    val autoAddedBeforeRestore = intent
                            .getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE)
                            ?.split(",")
                            ?: emptyList()

                    val tilesToRemove = restoredAutoAdded.filter { it !in tiles }
                    if (tilesToRemove.isNotEmpty()) {
                        qsHost.removeTiles(tilesToRemove)
                    }
                    val tiles = synchronized(autoAdded) {
                        autoAdded.clear()
                        autoAdded.addAll(restoredAutoAdded + autoAddedBeforeRestore)
                        getTilesFromListLocked()
                    }
                    saveTiles(tiles)
                } ?: run {
                    Log.w(TAG, "${Settings.Secure.QS_AUTO_ADDED_TILES} restored before " +
                            "${Settings.Secure.QS_TILES} for user $userId")
                }
            }
            else -> {} // Do nothing for other Settings
        }
    }

    /**
     * Init method must be called after construction to start listening
     */
    fun initialize() {
        dumpManager.registerDumpable(TAG, this)
        loadTiles()
        secureSettings.registerContentObserverForUser(
                secureSettings.getUriFor(Settings.Secure.QS_AUTO_ADDED_TILES),
                contentObserver,
                UserHandle.USER_ALL
        )
        registerBroadcastReceiver()
    }

    /**
     * Unregister listeners, receivers and observers
     */
    fun destroy() {
        dumpManager.unregisterDumpable(TAG)
        secureSettings.unregisterContentObserver(contentObserver)
        unregisterBroadcastReceiver()
    }

    private fun registerBroadcastReceiver() {
        broadcastDispatcher.registerReceiver(
                restoreReceiver,
                FILTER,
                backgroundExecutor,
                UserHandle.of(userId)
        )
    }

    private fun unregisterBroadcastReceiver() {
        broadcastDispatcher.unregisterReceiver(restoreReceiver)
    }

    override fun changeUser(newUser: UserHandle) {
        if (newUser.identifier == userId) return
        unregisterBroadcastReceiver()
        userId = newUser.identifier
        restoredTiles = null
        loadTiles()
        registerBroadcastReceiver()
    }

    /**
     * Returns `true` if the tile has been auto-added before
     */
    fun isAdded(tile: String): Boolean {
        return synchronized(autoAdded) {
            tile in autoAdded
        }
    }

    /**
     * Sets a tile as auto-added.
     *
     * From here on, [isAdded] will return true for that tile.
     */
    fun setTileAdded(tile: String) {
        val tiles = synchronized(autoAdded) {
                if (autoAdded.add(tile)) {
                    getTilesFromListLocked()
                } else {
                    null
                }
            }
        tiles?.let { saveTiles(it) }
    }

    /**
     * Removes a tile from the list of auto-added.
     *
     * This allows for this tile to be auto-added again in the future.
     */
    fun setTileRemoved(tile: String) {
        val tiles = synchronized(autoAdded) {
            if (autoAdded.remove(tile)) {
                getTilesFromListLocked()
            } else {
                null
            }
        }
        tiles?.let { saveTiles(it) }
    }

    private fun getTilesFromListLocked(): String {
        return TextUtils.join(",", autoAdded)
    }

    private fun saveTiles(tiles: String) {
        secureSettings.putStringForUser(
                Settings.Secure.QS_AUTO_ADDED_TILES,
                tiles,
                /* tag */ null,
                /* makeDefault */ false,
                userId,
                /* overrideableByRestore */ true
        )
    }

    private fun loadTiles() {
        synchronized(autoAdded) {
            autoAdded.clear()
            autoAdded.addAll(getAdded())
        }
    }

    private fun getAdded(): Collection<String> {
        val current = secureSettings.getStringForUser(Settings.Secure.QS_AUTO_ADDED_TILES, userId)
        return current?.split(",") ?: emptySet()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Current user: $userId")
        pw.println("Added tiles: $autoAdded")
    }

    @SysUISingleton
    class Builder @Inject constructor(
        private val secureSettings: SecureSettings,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val qsHost: QSHost,
        private val dumpManager: DumpManager,
        @Main private val handler: Handler,
        @Background private val executor: Executor
    ) {
        private var userId: Int = 0

        fun setUserId(_userId: Int): Builder {
            userId = _userId
            return this
        }

        fun build(): AutoAddTracker {
            return AutoAddTracker(
                    secureSettings,
                    broadcastDispatcher,
                    qsHost,
                    dumpManager,
                    handler,
                    executor,
                    userId
            )
        }
    }
}