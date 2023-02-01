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

/**
 * Base request class for getting a registered credential.
 *
 * @property type the credential type
 * @property data the request data in the [Bundle] format
 * @property requireSystemProvider true if must only be fulfilled by a system provider and false
 *                              otherwise
 */
open class GetCredentialOption(
    open val type: String,
    open val requestData: Bundle,
    open val candidateQueryData: Bundle,
    open val requireSystemProvider: Boolean,
) {
    companion object {
        @JvmStatic
        fun createFrom(
            type: String,
            requestData: Bundle,
            candidateQueryData: Bundle,
            requireSystemProvider: Boolean
        ): GetCredentialOption {
            return try {
                when (type) {
                    Credential.TYPE_PASSWORD_CREDENTIAL ->
                        GetPasswordOption.createFrom(requestData)
                    PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL ->
                        when (requestData.getString(PublicKeyCredential.BUNDLE_KEY_SUBTYPE)) {
                            GetPublicKeyCredentialOption
                                .BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION ->
                                GetPublicKeyCredentialOption.createFrom(requestData)
                            GetPublicKeyCredentialOptionPrivileged
                                .BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION_PRIVILEGED ->
                                GetPublicKeyCredentialOptionPrivileged.createFrom(requestData)
                            else -> throw FrameworkClassParsingException()
                        }
                    else -> throw FrameworkClassParsingException()
                }
            } catch (e: FrameworkClassParsingException) {
                // Parsing failed but don't crash the process. Instead just output a request with
                // the raw framework values.
                GetCustomCredentialOption(
                    type, requestData, candidateQueryData, requireSystemProvider)
            }
        }
    }
}
