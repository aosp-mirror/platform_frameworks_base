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
import androidx.lifecycle.Observer
import androidx.slice.Slice
import androidx.slice.SliceProvider
import com.android.settingslib.spa.framework.common.EntrySliceData
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TAG = "SpaSliceProvider"

class SpaSliceProvider : SliceProvider(), Observer<Slice?> {
    private fun getOrPutSliceData(sliceUri: Uri): EntrySliceData? {
        if (!SpaEnvironmentFactory.isReady()) return null
        val sliceRepository by SpaEnvironmentFactory.instance.sliceDataRepository
        return sliceRepository.getOrBuildSliceData(sliceUri)
    }

    override fun onBindSlice(sliceUri: Uri): Slice? {
        if (context == null) return null
        Log.d(TAG, "onBindSlice: $sliceUri")
        return getOrPutSliceData(sliceUri)?.value
    }

    override fun onSlicePinned(sliceUri: Uri) {
        Log.d(TAG, "onSlicePinned: $sliceUri")
        super.onSlicePinned(sliceUri)
        val sliceLiveData = getOrPutSliceData(sliceUri) ?: return
        runBlocking {
            withContext(Dispatchers.Main) {
                sliceLiveData.observeForever(this@SpaSliceProvider)
            }
        }
    }

    override fun onSliceUnpinned(sliceUri: Uri) {
        Log.d(TAG, "onSliceUnpinned: $sliceUri")
        super.onSliceUnpinned(sliceUri)
        val sliceLiveData = getOrPutSliceData(sliceUri) ?: return
        runBlocking {
            withContext(Dispatchers.Main) {
                sliceLiveData.removeObserver(this@SpaSliceProvider)
            }
        }
    }

    override fun onChanged(value: Slice?) {
        val uri = value?.uri ?: return
        Log.d(TAG, "onChanged: $uri")
        context?.contentResolver?.notifyChange(uri, null)
    }

    override fun onCreateSliceProvider(): Boolean {
        Log.d(TAG, "onCreateSliceProvider")
        return true
    }
}
