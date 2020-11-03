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

import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import java.util.concurrent.Executor
import javax.inject.Inject

private const val TAG = "MediaDataFilter"
private const val DEBUG = true

/**
 * Filters data updates from [MediaDataCombineLatest] based on the current user ID, and handles user
 * switches (removing entries for the previous user, adding back entries for the current user)
 *
 * This is added at the end of the pipeline since we may still need to handle callbacks from
 * background users (e.g. timeouts).
 */
class MediaDataFilter @Inject constructor(
    private val broadcastDispatcher: BroadcastDispatcher,
    private val mediaResumeListener: MediaResumeListener,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    @Main private val executor: Executor
) : MediaDataManager.Listener {
    private val userTracker: CurrentUserTracker
    private val _listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    internal val listeners: Set<MediaDataManager.Listener>
        get() = _listeners.toSet()
    internal lateinit var mediaDataManager: MediaDataManager

    private val allEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()
    // The filtered userEntries, which will be a subset of all userEntries in MediaDataManager
    private val userEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()

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

    override fun onMediaDataRemoved(key: String) {
        allEntries.remove(key)
        userEntries.remove(key)?.let {
            // Only notify listeners if something actually changed
            listeners.forEach {
                it.onMediaDataRemoved(key)
            }
        }
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
            mediaDataManager.setTimedOut(it, timedOut = true)
        }
    }

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = userEntries.any { it.value.active }

    /**
     * Are there any media entries we should display?
     */
    fun hasAnyMedia() = userEntries.isNotEmpty()

    /**
     * Add a listener for filtered [MediaData] changes
     */
    fun addListener(listener: MediaDataManager.Listener) = _listeners.add(listener)

    /**
     * Remove a listener that was registered with addListener
     */
    fun removeListener(listener: MediaDataManager.Listener) = _listeners.remove(listener)
}
