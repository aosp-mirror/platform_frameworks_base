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

package com.android.systemui.lifecycle

import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeSysUiViewModel(
    private val onActivation: () -> Unit = {},
    private val onDeactivation: () -> Unit = {},
    upstreamFlow: Flow<Boolean> = flowOf(true),
    upstreamStateFlow: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow(),
) : ExclusiveActivatable() {

    var activationCount = 0
    var cancellationCount = 0

    private val hydrator = Hydrator("test")
    val stateBackedByFlow: Boolean by
        hydrator.hydratedStateOf(
            traceName = "test",
            initialValue = true,
            source = upstreamFlow,
        )
    val stateBackedByStateFlow: Boolean by
        hydrator.hydratedStateOf(
            traceName = "test",
            source = upstreamStateFlow,
        )

    override suspend fun onActivated(): Nothing {
        activationCount++
        onActivation()
        try {
            hydrator.activate()
        } finally {
            cancellationCount++
            onDeactivation()
        }
    }
}
