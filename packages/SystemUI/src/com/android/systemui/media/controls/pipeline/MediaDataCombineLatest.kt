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

package com.android.systemui.media.controls.pipeline

import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.models.player.MediaDeviceData
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData
import javax.inject.Inject

/** Combines [MediaDataManager.Listener] events with [MediaDeviceManager.Listener] events. */
class MediaDataCombineLatest @Inject constructor() :
    MediaDataManager.Listener, MediaDeviceManager.Listener {

    private val listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    private val entries: MutableMap<String, Pair<MediaData?, MediaDeviceData?>> = mutableMapOf()

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        if (oldKey != null && oldKey != key && entries.contains(oldKey)) {
            entries[key] = data to entries.remove(oldKey)?.second
            update(key, oldKey)
        } else {
            entries[key] = data to entries[key]?.second
            update(key, key)
        }
    }

    override fun onSmartspaceMediaDataLoaded(
        key: String,
        data: SmartspaceMediaData,
        shouldPrioritize: Boolean
    ) {
        listeners.toSet().forEach { it.onSmartspaceMediaDataLoaded(key, data) }
    }

    override fun onMediaDataRemoved(key: String) {
        remove(key)
    }

    override fun onSmartspaceMediaDataRemoved(key: String, immediately: Boolean) {
        listeners.toSet().forEach { it.onSmartspaceMediaDataRemoved(key, immediately) }
    }

    override fun onMediaDeviceChanged(key: String, oldKey: String?, data: MediaDeviceData?) {
        if (oldKey != null && oldKey != key && entries.contains(oldKey)) {
            entries[key] = entries.remove(oldKey)?.first to data
            update(key, oldKey)
        } else {
            entries[key] = entries[key]?.first to data
            update(key, key)
        }
    }

    override fun onKeyRemoved(key: String) {
        remove(key)
    }

    /**
     * Add a listener for [MediaData] changes that has been combined with latest [MediaDeviceData].
     */
    fun addListener(listener: MediaDataManager.Listener) = listeners.add(listener)

    /** Remove a listener registered with addListener. */
    fun removeListener(listener: MediaDataManager.Listener) = listeners.remove(listener)

    private fun update(key: String, oldKey: String?) {
        val (entry, device) = entries[key] ?: null to null
        if (entry != null && device != null) {
            val data = entry.copy(device = device)
            val listenersCopy = listeners.toSet()
            listenersCopy.forEach { it.onMediaDataLoaded(key, oldKey, data) }
        }
    }

    private fun remove(key: String) {
        entries.remove(key)?.let {
            val listenersCopy = listeners.toSet()
            listenersCopy.forEach { it.onMediaDataRemoved(key) }
        }
    }
}
