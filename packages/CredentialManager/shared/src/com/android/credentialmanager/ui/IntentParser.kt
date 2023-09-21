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

package com.android.credentialmanager.ui

import android.content.Intent
import android.credentials.ui.GetCredentialProviderData
import android.credentials.ui.RequestInfo
import com.android.credentialmanager.ui.ktx.cancelUiRequest
import com.android.credentialmanager.ui.ktx.getCredentialProviderDataList
import com.android.credentialmanager.ui.ktx.requestInfo
import com.android.credentialmanager.ui.mapper.toCancel
import com.android.credentialmanager.ui.model.Request
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap

fun Intent.parse(): Request {
    cancelUiRequest?.let {
        return it.toCancel()
    }

    return when (requestInfo?.type) {
        RequestInfo.TYPE_CREATE -> {
            Request.Create
        }

        RequestInfo.TYPE_GET -> {
            Request.Get(
                providers = ImmutableMap.copyOf(
                    getCredentialProviderDataList.associateBy { it.providerFlattenedComponentName }
                ),
                entries = ImmutableList.copyOf(
                    getCredentialProviderDataList.map { providerData ->
                        check(providerData is GetCredentialProviderData) {
                            "Invalid provider data type for GetCredentialRequest"
                        }
                        providerData
                    }.flatMap { it.credentialEntries }
                )
            )
        }

        else -> {
            throw IllegalStateException("Unrecognized request type: ${requestInfo?.type}")
        }
    }
}
