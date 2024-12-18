/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.media.controls.data.repository

import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import com.android.systemui.media.controls.util.MediaFlags
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MediaDataRepository"
private const val DEBUG = true

/** A repository that holds the state of all media controls in carousel. */
@SysUISingleton
class MediaDataRepository
@Inject
constructor(
    private val mediaFlags: MediaFlags,
    dumpManager: DumpManager,
) : Dumpable {

    private val _mediaEntries: MutableStateFlow<Map<String, MediaData>> =
        MutableStateFlow(LinkedHashMap())
    val mediaEntries: StateFlow<Map<String, MediaData>> = _mediaEntries.asStateFlow()

    private val _smartspaceMediaData: MutableStateFlow<SmartspaceMediaData> =
        MutableStateFlow(SmartspaceMediaData())
    val smartspaceMediaData: StateFlow<SmartspaceMediaData> = _smartspaceMediaData.asStateFlow()

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    /** Updates the recommendation data with a new smartspace media data. */
    fun setRecommendation(recommendation: SmartspaceMediaData) {
        _smartspaceMediaData.value = recommendation
    }

    /**
     * Marks the recommendation data as inactive.
     *
     * @return true if the recommendation was actually marked as inactive, false otherwise.
     */
    fun setRecommendationInactive(key: String): Boolean {
        if (!mediaFlags.isPersistentSsCardEnabled()) {
            Log.e(TAG, "Only persistent recommendation can be inactive!")
            return false
        }
        if (DEBUG) Log.d(TAG, "Setting smartspace recommendation inactive")

        if (smartspaceMediaData.value.targetId != key || !smartspaceMediaData.value.isValid()) {
            // If this doesn't match, or we've already invalidated the data, no action needed
            return false
        }

        setRecommendation(smartspaceMediaData.value.copy(isActive = false))
        return true
    }

    /**
     * Marks the recommendation data as dismissed.
     *
     * @return true if the recommendation was dismissed or already inactive, false otherwise.
     */
    fun dismissSmartspaceRecommendation(key: String): Boolean {
        val data = smartspaceMediaData.value
        if (data.targetId != key || !data.isValid()) {
            // If this doesn't match, or we've already invalidated the data, no action needed
            return false
        }

        if (DEBUG) Log.d(TAG, "Dismissing Smartspace media target")
        if (data.isActive) {
            setRecommendation(
                SmartspaceMediaData(
                    targetId = smartspaceMediaData.value.targetId,
                    instanceId = smartspaceMediaData.value.instanceId
                )
            )
        }
        return true
    }

    fun removeMediaEntry(key: String): MediaData? {
        val entries = LinkedHashMap<String, MediaData>(_mediaEntries.value)
        val mediaData = entries.remove(key)
        _mediaEntries.value = entries
        return mediaData
    }

    fun addMediaEntry(key: String, data: MediaData): MediaData? {
        val entries = LinkedHashMap<String, MediaData>(_mediaEntries.value)
        val mediaData = entries.put(key, data)
        _mediaEntries.value = entries
        return mediaData
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply { println("mediaEntries: ${mediaEntries.value}") }
    }
}
