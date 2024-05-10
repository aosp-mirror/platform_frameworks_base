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

package com.android.credentialmanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.credentials.ui.RequestInfo
import android.util.Log
import com.android.credentialmanager.ktx.appLabel
import com.android.credentialmanager.ktx.cancelUiRequest
import com.android.credentialmanager.ktx.requestInfo
import com.android.credentialmanager.mapper.toGet
import com.android.credentialmanager.model.Request

fun Intent.parse(
    context: Context,
): Request {
    return parseCancelUiRequest(context.packageManager)
        ?: parseRequestInfo(context)
}

fun Intent.parseCancelUiRequest(packageManager: PackageManager): Request? =
    this.cancelUiRequest?.let { cancelUiRequest ->
        val showCancel = cancelUiRequest.shouldShowCancellationUi().apply {
            Log.d(TAG, "Received UI cancel request, shouldShowCancellationUi: $this")
        }
        if (showCancel) {
            val appLabel = packageManager.appLabel(cancelUiRequest.appPackageName)
            if (appLabel == null) {
                Log.d(TAG, "Received UI cancel request with an invalid package name.")
                null
            } else {
                Request.Cancel(appName = appLabel, token = cancelUiRequest.token)
            }
        } else {
            Request.Close(cancelUiRequest.token)
        }
    }

fun Intent.parseRequestInfo(context: Context): Request =
    requestInfo.let{ info ->
        when (info?.type) {
            RequestInfo.TYPE_CREATE -> Request.Create(info.token)
            RequestInfo.TYPE_GET -> toGet(context)
            else -> {
                throw IllegalStateException("Unrecognized request type: ${info?.type}")
            }
        }
    }

