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

package com.android.settingslib.spaprivileged.framework.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.framework.compose.LifecycleEffect

/**
 * A [BroadcastReceiver] which registered when on start and unregistered when on stop.
 */
@Composable
fun DisposableBroadcastReceiverAsUser(
    intentFilter: IntentFilter,
    userHandle: UserHandle,
    onStart: () -> Unit = {},
    onReceive: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onReceive(intent)
            }
        }
    }
    LifecycleEffect(
        onStart = {
            context.registerReceiverAsUser(
                broadcastReceiver, userHandle, intentFilter, null, null
            )
            onStart()
        },
        onStop = {
            context.unregisterReceiver(broadcastReceiver)
        },
    )
}
