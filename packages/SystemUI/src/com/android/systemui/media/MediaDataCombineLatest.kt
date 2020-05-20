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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines updates from [MediaDataManager] with [MediaDeviceManager].
 */
@Singleton
class MediaDataCombineLatest @Inject constructor(
    private val dataSource: MediaDataManager,
    private val deviceSource: MediaDeviceManager
) {
    private val listeners: MutableSet<MediaDataManager.Listener> = mutableSetOf()
    private val entries: MutableMap<String, Pair<MediaData?, MediaDeviceData?>> = mutableMapOf()

    init {
        dataSource.addListener(object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(key: String, data: MediaData) {
                entries[key] = data to entries[key]?.second
                update(key)
            }
            override fun onMediaDataRemoved(key: String) {
                remove(key)
            }
        })
        deviceSource.addListener(object : MediaDeviceManager.Listener {
            override fun onMediaDeviceChanged(key: String, data: MediaDeviceData?) {
                entries[key] = entries[key]?.first to data
                update(key)
            }
            override fun onKeyRemoved(key: String) {
                remove(key)
            }
        })
    }

    /**
     * Add a listener for [MediaData] changes that has been combined with latest [MediaDeviceData].
     */
    fun addListener(listener: MediaDataManager.Listener) = listeners.add(listener)

    /**
     * Remove a listener registered with addListener.
     */
    fun removeListener(listener: MediaDataManager.Listener) = listeners.remove(listener)

    private fun update(key: String) {
        val (entry, device) = entries[key] ?: null to null
        if (entry != null && device != null) {
            val data = entry.copy(device = device)
            listeners.forEach {
                it.onMediaDataLoaded(key, data)
            }
        }
    }

    private fun remove(key: String) {
        entries.remove(key)?.let {
            listeners.forEach {
                it.onMediaDataRemoved(key)
            }
        }
    }
}
