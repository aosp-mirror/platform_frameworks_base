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

package com.android.systemui.slice

import android.net.Uri
import androidx.slice.Slice
import androidx.slice.SliceViewManager
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Returns updating [Slice] for a [sliceUri]. It's null when there is no slice available for the
 * provided Uri. This can change overtime because of external changes (like device being
 * connected/disconnected).
 *
 * The flow should be [kotlinx.coroutines.flow.flowOn] the main thread because [SliceViewManager]
 * isn't thread-safe. An exception will be thrown otherwise.
 */
fun SliceViewManager.sliceForUri(sliceUri: Uri): Flow<Slice?> =
    ConflatedCallbackFlow.conflatedCallbackFlow {
        val callback = SliceViewManager.SliceCallback { launch { send(it) } }

        val slice = bindSlice(sliceUri)
        send(slice)
        registerSliceCallback(sliceUri, callback)
        awaitClose { unregisterSliceCallback(sliceUri, callback) }
    }
