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

package com.android.settingslib.volume.data.repository

import android.media.AudioManager
import com.android.internal.util.ConcurrentUtils
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Provides audio managing functionality and data. */
interface AudioRepository {

    /** Current [AudioManager.getMode]. */
    val mode: StateFlow<Int>
}

class AudioRepositoryImpl(
    private val audioManager: AudioManager,
    backgroundCoroutineContext: CoroutineContext,
    coroutineScope: CoroutineScope,
) : AudioRepository {

    override val mode: StateFlow<Int> =
        callbackFlow {
                val listener =
                    AudioManager.OnModeChangedListener { newMode -> launch { send(newMode) } }
                audioManager.addOnModeChangedListener(ConcurrentUtils.DIRECT_EXECUTOR, listener)
                awaitClose { audioManager.removeOnModeChangedListener(listener) }
            }
            .flowOn(backgroundCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), audioManager.mode)
}
