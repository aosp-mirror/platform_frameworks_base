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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.android.settingslib.spaprivileged.framework.common.asUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * Creates an instance of a cold Flow for permissions changed callback of given [app].
 *
 * An initial element will be always sent.
 */
fun Context.permissionsChangedFlow(app: ApplicationInfo) = callbackFlow {
    val userPackageManager = asUser(app.userHandle).packageManager

    val onPermissionsChangedListener = PackageManager.OnPermissionsChangedListener { uid ->
        if (uid == app.uid) trySend(Unit)
    }
    userPackageManager.addOnPermissionsChangeListener(onPermissionsChangedListener)
    trySend(Unit)

    awaitClose {
        userPackageManager.removeOnPermissionsChangeListener(onPermissionsChangedListener)
    }
}.conflate().flowOn(Dispatchers.Default)
