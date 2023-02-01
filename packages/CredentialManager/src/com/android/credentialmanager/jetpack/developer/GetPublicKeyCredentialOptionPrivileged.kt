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
 * A privileged request to get passkeys from the user's public key credential provider. The caller
 * can modify the RP. Only callers with privileged permission (e.g. user's public browser or caBLE)
 * can use this. These permissions will be introduced in an upcoming release.
 * TODO("Add specific permission info/annotation")
 *
 * @property requestJson the privileged request in JSON format in the standard webauthn web json
 * shown [here](https://w3c.github.io/webauthn/#dictdef-publickeycredentialrequestoptionsjson).
 * @property preferImmediatelyAvailableCredentials true if you prefer the operation to return
 * immediately when there is no available credential instead of falling back to discovering remote
 * credentials, and false (default) otherwise
 * @property relyingParty the expected true RP ID which will override the one in the [requestJson],
 * where relyingParty is defined [here](https://w3c.github.io/webauthn/#rp-id) in more detail
 * @property clientDataHash a hash that is used to verify the [relyingParty] Identity
 * @throws NullPointerException If any of [requestJson], [relyingParty], or [clientDataHash]
 * is null
 * @throws IllegalArgumentException If any of [requestJson], [relyingParty], or [clientDataHash] is
 * empty
 */
class GetPublicKeyCredentialOptionPrivileged @JvmOverloads constructor(
    val requestJson: String,
    val relyingParty: String,
    val clientDataHash: String,
    @get:JvmName("preferImmediatelyAvailableCredentials")
    val preferImmediatelyAvailableCredentials: Boolean = false
) : GetCredentialOption(
    type = PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL,
    requestData = toBundle(
        requestJson,
        relyingParty,
        clientDataHash,
        preferImmediatelyAvailableCredentials
    ),
    candidateQueryData = toBundle(
        requestJson,
        relyingParty,
        clientDataHash,
        preferImmediatelyAvailableCredentials
    ),
    requireSystemProvider = false,
) {

    init {
        require(requestJson.isNotEmpty()) { "requestJson must not be empty" }
        require(relyingParty.isNotEmpty()) { "rp must not be empty" }
        require(clientDataHash.isNotEmpty()) { "clientDataHash must not be empty" }
    }

    /** A builder for [GetPublicKeyCredentialOptionPrivileged]. */
    class Builder(
        private var requestJson: String,
        private var relyingParty: String,
        private var clientDataHash: String
    ) {

        private var preferImmediatelyAvailableCredentials: Boolean = false

        /**
         * Sets the privileged request in JSON format.
         */
        fun setRequestJson(requestJson: String): Builder {
            this.requestJson = requestJson
            return this
        }

        /**
         * Sets to true if you prefer the operation to return immediately when there is no available
         * credential instead of falling back to discovering remote credentials, and false
         * otherwise.
         *
         * The default value is false.
         */
        @Suppress("MissingGetterMatchingBuilder")
        fun setPreferImmediatelyAvailableCredentials(
            preferImmediatelyAvailableCredentials: Boolean
        ): Builder {
            this.preferImmediatelyAvailableCredentials = preferImmediatelyAvailableCredentials
            return this
        }

        /**
         * Sets the expected true RP ID which will override the one in the [requestJson].
         */
        fun setRelyingParty(relyingParty: String): Builder {
            this.relyingParty = relyingParty
            return this
        }

        /**
         * Sets a hash that is used to verify the [relyingParty] Identity.
         */
        fun setClientDataHash(clientDataHash: String): Builder {
            this.clientDataHash = clientDataHash
            return this
        }

        /** Builds a [GetPublicKeyCredentialOptionPrivileged]. */
        fun build(): GetPublicKeyCredentialOptionPrivileged {
            return GetPublicKeyCredentialOptionPrivileged(
                this.requestJson,
                this.relyingParty, this.clientDataHash, this.preferImmediatelyAvailableCredentials
            )
        }
    }

    /** @hide */
    companion object {
        internal const val BUNDLE_KEY_RELYING_PARTY =
            "androidx.credentials.BUNDLE_KEY_RELYING_PARTY"
        internal const val BUNDLE_KEY_CLIENT_DATA_HASH =
            "androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH"
        internal const val BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS =
            "androidx.credentials.BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS"
        internal const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
        internal const val BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION_PRIVILEGED =
            "androidx.credentials.BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION" +
                    "_PRIVILEGED"

        @JvmStatic
        internal fun toBundle(
            requestJson: String,
            relyingParty: String,
            clientDataHash: String,
            preferImmediatelyAvailableCredentials: Boolean
        ): Bundle {
            val bundle = Bundle()
            bundle.putString(
                PublicKeyCredential.BUNDLE_KEY_SUBTYPE,
                BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION_PRIVILEGED
            )
            bundle.putString(BUNDLE_KEY_REQUEST_JSON, requestJson)
            bundle.putString(BUNDLE_KEY_RELYING_PARTY, relyingParty)
            bundle.putString(BUNDLE_KEY_CLIENT_DATA_HASH, clientDataHash)
            bundle.putBoolean(
                BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS,
                preferImmediatelyAvailableCredentials
            )
            return bundle
        }

        @Suppress("deprecation") // bundle.get() used for boolean value to prevent default
        // boolean value from being returned.
        @JvmStatic
        internal fun createFrom(data: Bundle): GetPublicKeyCredentialOptionPrivileged {
            try {
                val requestJson = data.getString(BUNDLE_KEY_REQUEST_JSON)
                val rp = data.getString(BUNDLE_KEY_RELYING_PARTY)
                val clientDataHash = data.getString(BUNDLE_KEY_CLIENT_DATA_HASH)
                val preferImmediatelyAvailableCredentials =
                    data.get(BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS)
                return GetPublicKeyCredentialOptionPrivileged(
                    requestJson!!,
                    rp!!,
                    clientDataHash!!,
                    (preferImmediatelyAvailableCredentials!!) as Boolean,
                )
            } catch (e: Exception) {
                throw FrameworkClassParsingException()
            }
        }
    }
}
