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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

fun AppOpsManager.getOpMode(op: Int, app: ApplicationInfo) =
    checkOpNoThrow(op, app.uid, app.packageName)

fun AppOpsManager.opModeFlow(op: Int, app: ApplicationInfo) =
    opChangedFlow(op, app).map { getOpMode(op, app) }.flowOn(Dispatchers.Default)

private fun AppOpsManager.opChangedFlow(op: Int, app: ApplicationInfo) = callbackFlow {
    val listener = object : AppOpsManager.OnOpChangedListener {
        override fun onOpChanged(op: String, packageName: String) {}

        override fun onOpChanged(op: String, packageName: String, userId: Int) {
            if (userId == app.userId) trySend(Unit)
        }
    }
    startWatchingMode(op, app.packageName, listener)
    trySend(Unit)

    awaitClose { stopWatchingMode(listener) }
}.conflate().flowOn(Dispatchers.Default)
