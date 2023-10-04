/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.mapper

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.android.credentialmanager.TAG
import com.android.credentialmanager.ktx.appLabel
import com.android.credentialmanager.ktx.cancelUiRequest
import com.android.credentialmanager.model.Request

fun Intent.toRequestCancel(packageManager: PackageManager): Request.Cancel? =
    this.cancelUiRequest?.let { cancelUiRequest ->
        val appLabel = packageManager.appLabel(cancelUiRequest.appPackageName)
        if (appLabel == null) {
            Log.d(TAG, "Received UI cancel request with an invalid package name.")
            null
        } else {
            Request.Cancel(appName = appLabel)
        }
    }
