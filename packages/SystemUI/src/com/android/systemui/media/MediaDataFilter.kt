/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media

import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceTarget
import android.os.SystemProperties
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "MediaDataFilter"
private const val DEBUG = true

/**
 * Maximum age of a media control to re-activate on smartspace signal. If there is no media control
 * available within this time window, smartspace recommendations will be shown instead.
 */
@VisibleForTesting
internal val SMARTSPACE_MAX_AGE = SystemProperties
        .getLong("debug.sysui.smartspace_max_age", TimeUnit.HOURS.toMillis(3))

/**
 * Filters data updates from [MediaDataCombineLatest] based on the current user ID, and handles user
 * switches (removing entries for the previous user, adding back entries for the current user). Also
 * filters out smartspace updates in favor of local recent media, when avaialble.
 *
 * This is added at the end of the pipeline since we may still need to handle callbacks from
 * background users (e.g. timeouts).
 */
class MediaDataFilter @Inject constructor(
    private val broadcastDispatcher: BroadcastDispatcher,
    private val mediaResumeListener: MediaResumeListener,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    @Main private val executor: Executor,
    private val systemClock: SystemClock
) : MediaDataManager.Listener {
    private val userTracker: CurrentUserTracker
    private val _listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    internal val listeners: Set<MediaDataManager.Listener>
        get() = _listeners.toSet()
    internal lateinit var mediaDataManager: MediaDataManager

    private val allEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    // The filtered userEntries, which will be a subset of all userEntries in MediaDataManager
    private val userEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    private var hasSmartspace: Boolean = false
    private var reactivatedKey: String? = null

    init {
        userTracker = object : CurrentUserTracker(broadcastDispatcher) {
            override fun onUserSwitched(newUserId: Int) {
                // Post this so we can be sure lockscreenUserManager already got the broadcast
                executor.execute { handleUserSwitched(newUserId) }
            }
        }
        userTracker.startTracking()
    }

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        if (oldKey != null && oldKey != key) {
            allEntries.remove(oldKey)
        }
        allEntries.put(key, data)

        if (!lockscreenUserManager.isCurrentProfile(data.userId)) {
            return
        }

        if (oldKey != null && oldKey != key) {
            userEntries.remove(oldKey)
        }
        userEntries.put(key, data)

        // Notify listeners
        listeners.forEach {
            it.onMediaDataLoaded(key, oldKey, data)
        }
    }

    override fun onSmartspaceMediaDataLoaded(key: String, data: SmartspaceTarget) {
        hasSmartspace = true

        // Before forwarding the smartspace target, first check if we have recently inactive media
        val now = systemClock.elapsedRealtime()
        val sorted = userEntries.toSortedMap(compareBy {
            userEntries.get(it)?.lastActive ?: -1
        })
        if (sorted.size > 0) {
            val lastActiveKey = sorted.lastKey() // most recently active
            val timeSinceActive = sorted.get(lastActiveKey)?.let {
                now - it.lastActive
            } ?: Long.MAX_VALUE
            if (timeSinceActive < SMARTSPACE_MAX_AGE) {
                // Notify listeners to consider this media active
                Log.d(TAG, "reactivating $lastActiveKey instead of smartspace")
                reactivatedKey = lastActiveKey
                val mediaData = sorted.get(lastActiveKey)!!.copy(active = true)
                listeners.forEach {
                    it.onMediaDataLoaded(lastActiveKey, lastActiveKey, mediaData)
                }
                return
            }
        }

        // If no recent media, continue with smartspace update
        if (isMediaRecommendationEmpty(data)) {
            Log.d(TAG, "Empty media recommendations. Skip showing the card")
            return
        }

        // Proceed only if the Smartspace recommendation is not empty.
        listeners.forEach { it.onSmartspaceMediaDataLoaded(key, data) }
    }

    override fun onMediaDataRemoved(key: String) {
        allEntries.remove(key)
        userEntries.remove(key)?.let {
            // Only notify listeners if something actually changed
            listeners.forEach {
                it.onMediaDataRemoved(key)
            }
        }
    }

    override fun onSmartspaceMediaDataRemoved(key: String) {
        hasSmartspace = false

        // First check if we had reactivated media instead of forwarding smartspace
        reactivatedKey?.let {
            val lastActiveKey = it
            reactivatedKey = null
            Log.d(TAG, "expiring reactivated key $lastActiveKey")
            // Notify listeners to update with actual active value
            userEntries.get(lastActiveKey)?.let { mediaData ->
                listeners.forEach {
                    it.onMediaDataLoaded(lastActiveKey, lastActiveKey, mediaData)
                }
            }
            return
        }

        listeners.forEach { it.onSmartspaceMediaDataRemoved(key) }
    }

    @VisibleForTesting
    internal fun handleUserSwitched(id: Int) {
        // If the user changes, remove all current MediaData objects and inform listeners
        val listenersCopy = listeners
        val keyCopy = userEntries.keys.toMutableList()
        // Clear the list first, to make sure callbacks from listeners if we have any entries
        // are up to date
        userEntries.clear()
        keyCopy.forEach {
            if (DEBUG) Log.d(TAG, "Removing $it after user change")
            listenersCopy.forEach { listener ->
                listener.onMediaDataRemoved(it)
            }
        }

        allEntries.forEach { (key, data) ->
            if (lockscreenUserManager.isCurrentProfile(data.userId)) {
                if (DEBUG) Log.d(TAG, "Re-adding $key after user change")
                userEntries.put(key, data)
                listenersCopy.forEach { listener ->
                    listener.onMediaDataLoaded(key, null, data)
                }
            }
        }
    }

    /**
     * Invoked when the user has dismissed the media carousel
     */
    fun onSwipeToDismiss() {
        if (DEBUG) Log.d(TAG, "Media carousel swiped away")
        val mediaKeys = userEntries.keys.toSet()
        mediaKeys.forEach {
            // Force updates to listeners, needed for re-activated card
            mediaDataManager.setTimedOut(it, timedOut = true, forceUpdate = true)
        }
        if (hasSmartspace) {
            mediaDataManager.dismissSmartspaceRecommendation(0L /* delay */)
        }
    }

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = userEntries.any { it.value.active } || hasSmartspace

    /**
     * Are there any media entries we should display?
     */
    fun hasAnyMedia() = userEntries.isNotEmpty() || hasSmartspace

    /**
     * Add a listener for filtered [MediaData] changes
     */
    fun addListener(listener: MediaDataManager.Listener) = _listeners.add(listener)

    /**
     * Remove a listener that was registered with addListener
     */
    fun removeListener(listener: MediaDataManager.Listener) = _listeners.remove(listener)

    /** Check if the Smartspace sends an empty update. */
    private fun isMediaRecommendationEmpty(data: SmartspaceTarget): Boolean {
        val mediaRecommendationList: List<SmartspaceAction> = data.getIconGrid()
        return mediaRecommendationList == null || mediaRecommendationList.isEmpty()
    }
}
