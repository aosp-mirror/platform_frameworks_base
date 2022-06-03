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

import android.content.pm.PackageManager
import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.util.Log
import androidx.annotation.WorkerThread
import com.android.internal.annotations.GuardedBy
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.util.asIndenting
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Monitors the active media projection to update privacy items.
 */
@SysUISingleton
class MediaProjectionPrivacyItemMonitor @Inject constructor(
    private val mediaProjectionManager: MediaProjectionManager,
    private val packageManager: PackageManager,
    private val privacyConfig: PrivacyConfig,
    @Background private val bgHandler: Handler,
    private val systemClock: SystemClock,
    private val logger: PrivacyLogger
) : PrivacyItemMonitor {

    companion object {
        const val TAG = "MediaProjectionPrivacyItemMonitor"
        const val DEBUG = false
    }

    private val lock = Any()

    @GuardedBy("lock")
    private var callback: PrivacyItemMonitor.Callback? = null

    @GuardedBy("lock")
    private var mediaProjectionAvailable = privacyConfig.mediaProjectionAvailable
    @GuardedBy("lock")
    private var listening = false

    @GuardedBy("lock")
    private val privacyItems = mutableListOf<PrivacyItem>()

    private val optionsCallback = object : PrivacyConfig.Callback {
        override fun onFlagMediaProjectionChanged(flag: Boolean) {
            synchronized(lock) {
                mediaProjectionAvailable = privacyConfig.mediaProjectionAvailable
                setListeningStateLocked()
            }
            dispatchOnPrivacyItemsChanged()
        }
    }

    private val mediaProjectionCallback = object : MediaProjectionManager.Callback() {
        @WorkerThread
        override fun onStart(info: MediaProjectionInfo) {
            synchronized(lock) { onMediaProjectionStartedLocked(info) }
            dispatchOnPrivacyItemsChanged()
        }

        @WorkerThread
        override fun onStop(info: MediaProjectionInfo) {
            synchronized(lock) { onMediaProjectionStoppedLocked(info) }
            dispatchOnPrivacyItemsChanged()
        }
    }

    init {
        privacyConfig.addCallback(optionsCallback)
        setListeningStateLocked()
    }

    override fun startListening(callback: PrivacyItemMonitor.Callback) {
        synchronized(lock) {
            this.callback = callback
        }
    }

    override fun stopListening() {
        synchronized(lock) {
            this.callback = null
        }
    }

    @GuardedBy("lock")
    @WorkerThread
    private fun onMediaProjectionStartedLocked(info: MediaProjectionInfo) {
        if (DEBUG) Log.d(TAG, "onMediaProjectionStartedLocked: info=$info")
        val item = makePrivacyItem(info)
        privacyItems.add(item)
        logItemActive(item, true)
    }

    @GuardedBy("lock")
    @WorkerThread
    private fun onMediaProjectionStoppedLocked(info: MediaProjectionInfo) {
        if (DEBUG) Log.d(TAG, "onMediaProjectionStoppedLocked: info=$info")
        val item = makePrivacyItem(info)
        privacyItems.removeAt(privacyItems.indexOfFirst { it.application == item.application })
        logItemActive(item, false)
    }

    @WorkerThread
    private fun makePrivacyItem(info: MediaProjectionInfo): PrivacyItem {
        val userId = info.userHandle.identifier
        val uid = packageManager.getPackageUidAsUser(info.packageName, userId)
        val app = PrivacyApplication(info.packageName, uid)
        val now = systemClock.elapsedRealtime()
        return PrivacyItem(PrivacyType.TYPE_MEDIA_PROJECTION, app, now)
    }

    private fun logItemActive(item: PrivacyItem, active: Boolean) {
        logger.logUpdatedItemFromMediaProjection(
                item.application.uid, item.application.packageName, active)
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicator is enabled.
     */
    @GuardedBy("lock")
    private fun setListeningStateLocked() {
        val shouldListen = mediaProjectionAvailable
        if (DEBUG) {
            Log.d(TAG, "shouldListen=$shouldListen, " +
                    "mediaProjectionAvailable=$mediaProjectionAvailable")
        }
        if (listening == shouldListen) {
            return
        }

        listening = shouldListen
        if (shouldListen) {
            if (DEBUG) Log.d(TAG, "Registering MediaProjectionManager callback")
            mediaProjectionManager.addCallback(mediaProjectionCallback, bgHandler)

            val activeProjection = mediaProjectionManager.activeProjectionInfo
            if (activeProjection != null) {
                onMediaProjectionStartedLocked(activeProjection)
                dispatchOnPrivacyItemsChanged()
            }
        } else {
            if (DEBUG) Log.d(TAG, "Unregistering MediaProjectionManager callback")
            mediaProjectionManager.removeCallback(mediaProjectionCallback)
            privacyItems.forEach { logItemActive(it, false) }
            privacyItems.clear()
            dispatchOnPrivacyItemsChanged()
        }
    }

    override fun getActivePrivacyItems(): List<PrivacyItem> {
        synchronized(lock) {
            if (DEBUG) Log.d(TAG, "getActivePrivacyItems: privacyItems=$privacyItems")
            return privacyItems.toList()
        }
    }

    private fun dispatchOnPrivacyItemsChanged() {
        if (DEBUG) Log.d(TAG, "dispatchOnPrivacyItemsChanged")
        val cb = synchronized(lock) { callback }
        if (cb != null) {
            bgHandler.post {
                cb.onPrivacyItemsChanged()
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("MediaProjectionPrivacyItemMonitor:")
        ipw.withIncreasedIndent {
            synchronized(lock) {
                ipw.println("Listening: $listening")
                ipw.println("mediaProjectionAvailable: $mediaProjectionAvailable")
                ipw.println("Callback: $callback")
                ipw.println("Privacy Items: $privacyItems")
            }
        }
        ipw.flush()
    }
}
