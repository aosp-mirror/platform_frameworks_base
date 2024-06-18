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

package com.android.settingslib.spaprivileged.framework.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

private const val TAG = "BroadcastReceiverFlow"

/**
 * A [BroadcastReceiver] flow for the given [intentFilter].
 */
fun Context.broadcastReceiverFlow(intentFilter: IntentFilter): Flow<Intent> = callbackFlow {
    val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive: $intent")
            trySend(intent)
        }
    }
    registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_VISIBLE_TO_INSTANT_APPS)

    awaitClose { unregisterReceiver(broadcastReceiver) }
}.catch { e ->
    Log.e(TAG, "Error while broadcastReceiverFlow", e)
}.conflate().flowOn(Dispatchers.Default)
