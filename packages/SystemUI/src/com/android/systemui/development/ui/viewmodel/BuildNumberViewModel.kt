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

package com.android.systemui.development.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.development.domain.interactor.BuildNumberInteractor
import com.android.systemui.development.shared.model.BuildNumber
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** View model for UI that (optionally) shows the build number and copies it on long press. */
class BuildNumberViewModel
@AssistedInject
constructor(private val buildNumberInteractor: BuildNumberInteractor) : ExclusiveActivatable() {

    private val hydrator = Hydrator("BuildNumberViewModel")

    private val copyRequests = Channel<Unit>()

    val buildNumber: BuildNumber? by
        hydrator.hydratedStateOf(
            traceName = "buildNumber",
            initialValue = null,
            source = buildNumberInteractor.buildNumber,
        )

    fun onBuildNumberLongPress() {
        copyRequests.trySend(Unit)
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch { hydrator.activate() }
            launch {
                copyRequests.receiveAsFlow().collect { buildNumberInteractor.copyBuildNumber() }
            }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): BuildNumberViewModel
    }
}
