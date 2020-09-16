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
import javax.inject.Singleton

private const val TAG = "MediaDataFilter"
private const val DEBUG = true

/**
 * Filters data updates from [MediaDataCombineLatest] based on the current user ID, and handles user
 * switches (removing entries for the previous user, adding back entries for the current user)
 *
 * This is added downstream of [MediaDataManager] since we may still need to handle callbacks from
 * background users (e.g. timeouts) that UI classes should ignore.
 * Instead, UI classes should listen to this so they can stay in sync with the current user.
 */
@Singleton
class MediaDataFilter @Inject constructor(
    private val dataSource: MediaDataCombineLatest,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val mediaResumeListener: MediaResumeListener,
    private val mediaDataManager: MediaDataManager,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    @Main private val executor: Executor
) : MediaDataManager.Listener {
    private val userTracker: CurrentUserTracker
    private val listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()

    // The filtered mediaEntries, which will be a subset of all mediaEntries in MediaDataManager
    private val mediaEntries: LinkedHashMap<String, MediaData> = LinkedHashMap()

    init {
        userTracker = object : CurrentUserTracker(broadcastDispatcher) {
            override fun onUserSwitched(newUserId: Int) {
                // Post this so we can be sure lockscreenUserManager already got the broadcast
                executor.execute { handleUserSwitched(newUserId) }
            }
        }
        userTracker.startTracking()
        dataSource.addListener(this)
    }

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData) {
        if (!lockscreenUserManager.isCurrentProfile(data.userId)) {
            return
        }

        if (oldKey != null) {
            mediaEntries.remove(oldKey)
        }
        mediaEntries.put(key, data)

        // Notify listeners
        val listenersCopy = listeners.toSet()
        listenersCopy.forEach {
            it.onMediaDataLoaded(key, oldKey, data)
        }
    }

    override fun onMediaDataRemoved(key: String) {
        mediaEntries.remove(key)?.let {
            // Only notify listeners if something actually changed
            val listenersCopy = listeners.toSet()
            listenersCopy.forEach {
                it.onMediaDataRemoved(key)
            }
        }
    }

    @VisibleForTesting
    internal fun handleUserSwitched(id: Int) {
        // If the user changes, remove all current MediaData objects and inform listeners
        val listenersCopy = listeners.toSet()
        val keyCopy = mediaEntries.keys.toMutableList()
        // Clear the list first, to make sure callbacks from listeners if we have any entries
        // are up to date
        mediaEntries.clear()
        keyCopy.forEach {
            if (DEBUG) Log.d(TAG, "Removing $it after user change")
            listenersCopy.forEach { listener ->
                listener.onMediaDataRemoved(it)
            }
        }

        dataSource.getData().forEach { (key, data) ->
            if (lockscreenUserManager.isCurrentProfile(data.userId)) {
                if (DEBUG) Log.d(TAG, "Re-adding $key after user change")
                mediaEntries.put(key, data)
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
        val mediaKeys = mediaEntries.keys.toSet()
        mediaKeys.forEach {
            mediaDataManager.setTimedOut(it, timedOut = true)
        }
    }

    /**
     * Are there any media notifications active?
     */
    fun hasActiveMedia() = mediaEntries.any { it.value.active }

    /**
     * Are there any media entries we should display?
     * If resumption is enabled, this will include inactive players
     * If resumption is disabled, we only want to show active players
     */
    fun hasAnyMedia() = if (mediaResumeListener.isResumptionEnabled()) {
        mediaEntries.isNotEmpty()
    } else {
        hasActiveMedia()
    }

    /**
     * Add a listener for filtered [MediaData] changes
     */
    fun addListener(listener: MediaDataManager.Listener) = listeners.add(listener)

    /**
     * Remove a listener that was registered with addListener
     */
    fun removeListener(listener: MediaDataManager.Listener) = listeners.remove(listener)
}