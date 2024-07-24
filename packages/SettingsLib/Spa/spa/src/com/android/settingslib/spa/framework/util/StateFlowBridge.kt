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

package com.android.settingslib.spa.framework.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/** A StateFlow holder which value could be set or sync from callback. */
class StateFlowBridge<T> {
    private val stateFlow = MutableStateFlow<T?>(null)
    val flow = stateFlow.filterNotNull()

    fun setIfAbsent(value: T) {
        if (stateFlow.value == null) {
            stateFlow.value = value
        }
    }

    @Composable
    fun Sync(callback: () -> T) {
        val value = callback()
        LaunchedEffect(value) {
            stateFlow.value = value
        }
    }
}
