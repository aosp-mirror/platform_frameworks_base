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
 * A privileged request to register a passkey from the user’s public key credential provider, where
 * the caller can modify the rp. Only callers with privileged permission, e.g. user’s default
 * brower, caBLE, can use this. These permissions will be introduced in an upcoming release.
 * TODO("Add specific permission info/annotation")
 *
 * @property requestJson the privileged request in JSON format in the standard webauthn web json
 * shown [here](https://w3c.github.io/webauthn/#dictdef-publickeycredentialrequestoptionsjson).
 * @property preferImmediatelyAvailableCredentials true if you prefer the operation to return
 * immediately when there is no available passkey registration offering instead of falling back to
 * discovering remote options, and false (default) otherwise
 * @property relyingParty the expected true RP ID which will override the one in the [requestJson],
 * where rp is defined [here](https://w3c.github.io/webauthn/#rp-id)
 * @property clientDataHash a hash that is used to verify the [relyingParty] Identity
 * @throws NullPointerException If any of [requestJson], [relyingParty], or [clientDataHash] is
 * null
 * @throws IllegalArgumentException If any of [requestJson], [relyingParty], or [clientDataHash] is
 * empty
 */
class CreatePublicKeyCredentialRequestPrivileged @JvmOverloads constructor(
    val requestJson: String,
    val relyingParty: String,
    val clientDataHash: String,
    @get:JvmName("preferImmediatelyAvailableCredentials")
    val preferImmediatelyAvailableCredentials: Boolean = false
) : CreateCredentialRequest(
    type = PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL,
    credentialData = toCredentialDataBundle(
        requestJson,
        relyingParty,
        clientDataHash,
        preferImmediatelyAvailableCredentials
    ),
    // The whole request data should be passed during the query phase.
    candidateQueryData = toCredentialDataBundle(
        requestJson, relyingParty, clientDataHash, preferImmediatelyAvailableCredentials
    ),
    requireSystemProvider = false,
) {

    init {
        require(requestJson.isNotEmpty()) { "requestJson must not be empty" }
        require(relyingParty.isNotEmpty()) { "rp must not be empty" }
        require(clientDataHash.isNotEmpty()) { "clientDataHash must not be empty" }
    }

    /** A builder for [CreatePublicKeyCredentialRequestPrivileged]. */
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
         * passkey registration offering instead of falling back to discovering remote options, and
         * false otherwise.
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

        /** Builds a [CreatePublicKeyCredentialRequestPrivileged]. */
        fun build(): CreatePublicKeyCredentialRequestPrivileged {
            return CreatePublicKeyCredentialRequestPrivileged(
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

        internal const val BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST_PRIV =
            "androidx.credentials.BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST_" +
                    "PRIVILEGED"

        @JvmStatic
        internal fun toCredentialDataBundle(
            requestJson: String,
            relyingParty: String,
            clientDataHash: String,
            preferImmediatelyAvailableCredentials: Boolean
        ): Bundle {
            val bundle = Bundle()
            bundle.putString(
                PublicKeyCredential.BUNDLE_KEY_SUBTYPE,
                BUNDLE_VALUE_SUBTYPE_CREATE_PUBLIC_KEY_CREDENTIAL_REQUEST_PRIV
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
        internal fun createFrom(data: Bundle): CreatePublicKeyCredentialRequestPrivileged {
            try {
                val requestJson = data.getString(BUNDLE_KEY_REQUEST_JSON)
                val rp = data.getString(BUNDLE_KEY_RELYING_PARTY)
                val clientDataHash = data.getString(BUNDLE_KEY_CLIENT_DATA_HASH)
                val preferImmediatelyAvailableCredentials =
                    data.get(BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS)
                return CreatePublicKeyCredentialRequestPrivileged(
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
