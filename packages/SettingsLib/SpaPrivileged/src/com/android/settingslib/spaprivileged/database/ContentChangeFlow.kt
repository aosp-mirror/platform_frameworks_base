/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.database

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/** Content change flow for the given [uri]. */
fun Context.contentChangeFlow(
    uri: Uri,
    sendInitial: Boolean = true,
): Flow<Unit> = callbackFlow {
    val contentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    contentResolver.registerContentObserver(uri, false, contentObserver)
    if (sendInitial) {
        trySend(Unit)
    }

    awaitClose { contentResolver.unregisterContentObserver(contentObserver) }
}.conflate().flowOn(Dispatchers.Default)
