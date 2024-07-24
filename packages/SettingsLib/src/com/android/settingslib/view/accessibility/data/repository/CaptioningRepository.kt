/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.view.accessibility.data.repository

import android.view.accessibility.CaptioningManager
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface CaptioningRepository {

    /** The system audio caption enabled state. */
    val isSystemAudioCaptioningEnabled: StateFlow<Boolean>

    /** The system audio caption UI enabled state. */
    val isSystemAudioCaptioningUiEnabled: StateFlow<Boolean>

    /** Sets [isSystemAudioCaptioningEnabled]. */
    suspend fun setIsSystemAudioCaptioningEnabled(isEnabled: Boolean)
}

class CaptioningRepositoryImpl(
    private val captioningManager: CaptioningManager,
    private val backgroundCoroutineContext: CoroutineContext,
    coroutineScope: CoroutineScope,
) : CaptioningRepository {

    private val captioningChanges: SharedFlow<CaptioningChange> =
        callbackFlow {
                val listener = CaptioningChangeProducingListener(this)
                captioningManager.addCaptioningChangeListener(listener)
                awaitClose { captioningManager.removeCaptioningChangeListener(listener) }
            }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), replay = 0)

    override val isSystemAudioCaptioningEnabled: StateFlow<Boolean> =
        captioningChanges
            .filterIsInstance(CaptioningChange.IsSystemAudioCaptioningEnabled::class)
            .map { it.isEnabled }
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                captioningManager.isSystemAudioCaptioningEnabled
            )

    override val isSystemAudioCaptioningUiEnabled: StateFlow<Boolean> =
        captioningChanges
            .filterIsInstance(CaptioningChange.IsSystemUICaptioningEnabled::class)
            .map { it.isEnabled }
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                captioningManager.isSystemAudioCaptioningUiEnabled,
            )

    override suspend fun setIsSystemAudioCaptioningEnabled(isEnabled: Boolean) {
        withContext(backgroundCoroutineContext) {
            captioningManager.isSystemAudioCaptioningEnabled = isEnabled
        }
    }

    private sealed interface CaptioningChange {

        data class IsSystemAudioCaptioningEnabled(val isEnabled: Boolean) : CaptioningChange

        data class IsSystemUICaptioningEnabled(val isEnabled: Boolean) : CaptioningChange
    }

    private class CaptioningChangeProducingListener(
        private val scope: ProducerScope<CaptioningChange>
    ) : CaptioningManager.CaptioningChangeListener() {

        override fun onSystemAudioCaptioningChanged(enabled: Boolean) {
            emitChange(CaptioningChange.IsSystemAudioCaptioningEnabled(enabled))
        }

        override fun onSystemAudioCaptioningUiChanged(enabled: Boolean) {
            emitChange(CaptioningChange.IsSystemUICaptioningEnabled(enabled))
        }

        private fun emitChange(change: CaptioningChange) {
            scope.launch { scope.send(change) }
        }
    }
}
