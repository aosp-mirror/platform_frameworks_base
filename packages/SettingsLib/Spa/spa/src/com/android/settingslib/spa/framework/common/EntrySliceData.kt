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

package com.android.settingslib.spa.framework.common

import androidx.lifecycle.LiveData
import androidx.slice.Slice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

open class EntrySliceData : LiveData<Slice?>() {
    private val asyncRunnerScope = CoroutineScope(Dispatchers.IO)
    private var asyncRunnerJob: Job? = null
    private var asyncActionJob: Job? = null
    private var isActive = false

    open suspend fun asyncRunner() {}

    open suspend fun asyncAction() {}

    override fun onActive() {
        asyncRunnerJob?.cancel()
        asyncRunnerJob = asyncRunnerScope.launch { asyncRunner() }
        isActive = true
    }

    override fun onInactive() {
        asyncRunnerJob?.cancel()
        asyncRunnerJob = null
        asyncActionJob?.cancel()
        asyncActionJob = null
        isActive = false
    }

    fun isActive(): Boolean {
        return isActive
    }

    fun doAction() {
        asyncActionJob?.cancel()
        asyncActionJob = asyncRunnerScope.launch { asyncAction() }
    }
}
