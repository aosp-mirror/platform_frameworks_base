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

package com.android.settingslib.spa.slice

import android.net.Uri
import android.util.Log
import com.android.settingslib.spa.framework.common.EntrySliceData
import com.android.settingslib.spa.framework.common.SettingsEntryRepository
import com.android.settingslib.spa.framework.util.getEntryId

private const val TAG = "SliceDataRepository"

class SettingsSliceDataRepository(private val entryRepository: SettingsEntryRepository) {
    // The map of slice uri to its EntrySliceData, a.k.a. LiveData<Slice?>
    private val sliceDataMap: MutableMap<String, EntrySliceData> = mutableMapOf()

    // Note: mark this function synchronized, so that we can get the same livedata during the
    // whole lifecycle of a Slice.
    @Synchronized
    fun getOrBuildSliceData(sliceUri: Uri): EntrySliceData? {
        val sliceString = sliceUri.getSliceId() ?: return null
        return sliceDataMap[sliceString] ?: buildLiveDataImpl(sliceUri)?.let {
            sliceDataMap[sliceString] = it
            it
        }
    }

    fun getActiveSliceData(sliceUri: Uri): EntrySliceData? {
        val sliceString = sliceUri.getSliceId() ?: return null
        val sliceData = sliceDataMap[sliceString] ?: return null
        return if (sliceData.isActive()) sliceData else null
    }

    private fun buildLiveDataImpl(sliceUri: Uri): EntrySliceData? {
        Log.d(TAG, "buildLiveData: $sliceUri")

        val entryId = sliceUri.getEntryId() ?: return null
        val entry = entryRepository.getEntry(entryId) ?: return null
        if (!entry.hasSliceSupport) return null
        val arguments = sliceUri.getRuntimeArguments()
        return entry.getSliceData(runtimeArguments = arguments, sliceUri = sliceUri)
    }
}
