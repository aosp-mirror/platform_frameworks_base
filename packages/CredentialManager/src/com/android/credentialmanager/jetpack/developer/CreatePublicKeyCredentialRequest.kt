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
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential.Companion.BUNDLE_KEY_SUBTYPE

/**
* A request to register a passkey from the user's public key credential provider.
*
* @property requestJson the privileged request in JSON format in the standard webauthn web json
* shown [here](https://w3c.github.io/webauthn/#dictdef-publickeycredentialrequestoptionsjson).
* @property preferImmediatelyAvailableCredentials true if you prefer the operation to return
* immediately when there is no available passkey registration offering instead of falling back to
* discovering remote options, and false (default) otherwise
* @throws NullPointerException If [requestJson] is null
* @throws IllegalArgumentException If [requestJson] is empty
*/
class CreatePublicKeyCredentialRequest @JvmOverloads constructor(
    val requestJson: String,
    @get:JvmName("preferImmediatelyAvailableCredentials")
    val preferImmediatelyAvailableCredentials: Boolean = false
) : CreateCredentialRequest(
    type = PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL,
    credentialData = toCredentialDataBundle(requestJson, preferImmediatelyAvailableCredentials),
    // The whole request data should be passed during the query phase.
    candidateQueryData = toCredentialDataBundle(requestJson, preferImmediatelyAvailableCredentials),
    requireSystemProvider = false,
) {

    init {
        require(requestJson.isNotEmpty()) { "requestJson must not be empty" }
    }

    /** @hide */
    companion object {
        const val BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS =
            "androidx.credentials.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS"
        internal const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
        internal const val BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST =
            "androidx.credentials.BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST"

        @JvmStatic
        internal fun toCredentialDataBundle(
            requestJson: String,
            preferImmediatelyAvailableCredentials: Boolean
        ): Bundle {
            val bundle = Bundle()
            bundle.putString(BUNDLE_KEY_SUBTYPE,
                BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST)
            bundle.putString(BUNDLE_KEY_REQUEST_JSON, requestJson)
            bundle.putBoolean(BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS,
                preferImmediatelyAvailableCredentials)
            return bundle
        }

        @JvmStatic
        internal fun toCandidateDataBundle(
            requestJson: String,
            preferImmediatelyAvailableCredentials: Boolean
        ): Bundle {
            val bundle = Bundle()
            bundle.putString(BUNDLE_KEY_SUBTYPE,
                BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST)
            bundle.putString(BUNDLE_KEY_REQUEST_JSON, requestJson)
            bundle.putBoolean(BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS,
                preferImmediatelyAvailableCredentials)
            return bundle
        }

        @Suppress("deprecation") // bundle.get() used for boolean value to prevent default
        // boolean value from being returned.
        @JvmStatic
        internal fun createFrom(data: Bundle): CreatePublicKeyCredentialRequest {
            try {
                val requestJson = data.getString(BUNDLE_KEY_REQUEST_JSON)
                val preferImmediatelyAvailableCredentials =
                    data.get(BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS)
                return CreatePublicKeyCredentialRequest(requestJson!!,
                    (preferImmediatelyAvailableCredentials!!) as Boolean)
            } catch (e: Exception) {
                throw FrameworkClassParsingException()
            }
        }
    }
}