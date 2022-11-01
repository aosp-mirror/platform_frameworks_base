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

package com.android.credentialmanager.jetpack.developer

import android.os.Bundle

/**
 * Base request class for registering a public key credential.
 *
 * @property requestJson The request in JSON format
 * @throws NullPointerException If [requestJson] is null. This is handled by the Kotlin runtime
 * @throws IllegalArgumentException If [requestJson] is empty
 *
 * @hide
 */
abstract class CreatePublicKeyCredentialBaseRequest constructor(
        val requestJson: String,
        type: String,
        data: Bundle,
        requireSystemProvider: Boolean,
) : CreateCredentialRequest(type, data, requireSystemProvider) {

    init {
        require(requestJson.isNotEmpty()) { "request json must not be empty" }
    }

    companion object {
        const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
        const val BUNDLE_KEY_SUBTYPE = "androidx.credentials.BUNDLE_KEY_SUBTYPE"

        @JvmStatic
        fun createFrom(data: Bundle): CreatePublicKeyCredentialBaseRequest {
            return when (data.getString(BUNDLE_KEY_SUBTYPE)) {
                CreatePublicKeyCredentialRequest
                        .BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST ->
                    CreatePublicKeyCredentialRequestPrivileged.createFrom(data)
                CreatePublicKeyCredentialRequestPrivileged
                        .BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST_PRIVILEGED ->
                    CreatePublicKeyCredentialRequestPrivileged.createFrom(data)
                else -> throw FrameworkClassParsingException()
            }
        }
    }
}
