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
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spaprivileged.framework.common.broadcastReceiverAsUserFlow

/**
 * A [BroadcastReceiver] which registered when on start and unregistered when on stop.
 */
@Composable
fun DisposableBroadcastReceiverAsUser(
    intentFilter: IntentFilter,
    userHandle: UserHandle,
    onReceive: (Intent) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        context.broadcastReceiverAsUserFlow(intentFilter, userHandle).collect(onReceive)
    }
}
