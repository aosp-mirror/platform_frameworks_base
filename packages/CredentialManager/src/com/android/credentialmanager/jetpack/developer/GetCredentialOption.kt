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
    val type: String,
    val data: Bundle,
    val requireSystemProvider: Boolean,
) {
    companion object {
        @JvmStatic
        fun createFrom(from: android.credentials.GetCredentialOption): GetCredentialOption {
            return try {
                when (from.type) {
                    Credential.TYPE_PASSWORD_CREDENTIAL ->
                        GetPasswordOption.createFrom(from.credentialRetrievalData)
                    PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL ->
                        GetPublicKeyCredentialBaseOption.createFrom(from.credentialRetrievalData)
                    else ->
                        GetCredentialOption(
                            from.type, from.credentialRetrievalData, from.requireSystemProvider()
                        )
                }
            } catch (e: FrameworkClassParsingException) {
                GetCredentialOption(
                    from.type,
                    from.credentialRetrievalData,
                    from.requireSystemProvider()
                )
            }
        }
    }
}
