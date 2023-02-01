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

import android.credentials.Credential
import android.os.Bundle
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.BUNDLE_KEY_SUBTYPE

/**
 * Base request class for registering a credential.
 *
 * @property type the credential type
 * @property data the request data in the [Bundle] format
 * @property requireSystemProvider true if must only be fulfilled by a system provider and false
 *                              otherwise
 */
open class CreateCredentialRequest(
    open val type: String,
    open val credentialData: Bundle,
    open val candidateQueryData: Bundle,
    open val requireSystemProvider: Boolean
) {
    companion object {
        @JvmStatic
        fun createFrom(
            type: String,
            credentialData: Bundle,
            candidateQueryData: Bundle,
            requireSystemProvider: Boolean
        ): CreateCredentialRequest {
            return try {
                when (type) {
                    Credential.TYPE_PASSWORD_CREDENTIAL ->
                        CreatePasswordRequest.createFrom(credentialData)
                    PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL ->
                        when (credentialData.getString(BUNDLE_KEY_SUBTYPE)) {
                            CreatePublicKeyCredentialRequest
                                .BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST ->
                                CreatePublicKeyCredentialRequest.createFrom(credentialData)
                            CreatePublicKeyCredentialRequestPrivileged
                                .BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST_PRIV ->
                                CreatePublicKeyCredentialRequestPrivileged
                                    .createFrom(credentialData)
                            else -> throw FrameworkClassParsingException()
                        }
                    else -> throw FrameworkClassParsingException()
                }
            } catch (e: FrameworkClassParsingException) {
                // Parsing failed but don't crash the process. Instead just output a request with
                // the raw framework values.
                CreateCustomCredentialRequest(
                    type,
                    credentialData,
                    candidateQueryData,
                    requireSystemProvider
                )
            }
        }
    }
}
