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
 * can use this.
 *
 * @property requestJson the privileged request in JSON format
 * @property allowHybrid defines whether hybrid credentials are allowed to fulfill this request,
 * true by default
 * @property rp the expected true RP ID which will override the one in the [requestJson]
 * @property clientDataHash a hash that is used to verify the [rp] Identity
 * @throws NullPointerException If any of [allowHybrid], [requestJson], [rp], or [clientDataHash]
 * is null. This is handled by the Kotlin runtime
 * @throws IllegalArgumentException If any of [requestJson], [rp], or [clientDataHash] is empty
 *
 * @hide
 */
class GetPublicKeyCredentialOptionPrivileged @JvmOverloads constructor(
        requestJson: String,
        val rp: String,
        val clientDataHash: String,
        @get:JvmName("allowHybrid")
        val allowHybrid: Boolean = true
) : GetPublicKeyCredentialBaseOption(
        requestJson,
        PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL,
        toBundle(requestJson, rp, clientDataHash, allowHybrid),
        false,
) {

    init {
        require(rp.isNotEmpty()) { "rp must not be empty" }
        require(clientDataHash.isNotEmpty()) { "clientDataHash must not be empty" }
    }

    /** A builder for [GetPublicKeyCredentialOptionPrivileged]. */
    class Builder(var requestJson: String, var rp: String, var clientDataHash: String) {

        private var allowHybrid: Boolean = true

        /**
         * Sets the privileged request in JSON format.
         */
        fun setRequestJson(requestJson: String): Builder {
            this.requestJson = requestJson
            return this
        }

        /**
         * Sets whether hybrid credentials are allowed to fulfill this request, true by default.
         */
        fun setAllowHybrid(allowHybrid: Boolean): Builder {
            this.allowHybrid = allowHybrid
            return this
        }

        /**
         * Sets the expected true RP ID which will override the one in the [requestJson].
         */
        fun setRp(rp: String): Builder {
            this.rp = rp
            return this
        }

        /**
         * Sets a hash that is used to verify the [rp] Identity.
         */
        fun setClientDataHash(clientDataHash: String): Builder {
            this.clientDataHash = clientDataHash
            return this
        }

        /** Builds a [GetPublicKeyCredentialOptionPrivileged]. */
        fun build(): GetPublicKeyCredentialOptionPrivileged {
            return GetPublicKeyCredentialOptionPrivileged(this.requestJson,
                    this.rp, this.clientDataHash, this.allowHybrid)
        }
    }

    companion object {
        const val BUNDLE_KEY_RP = "androidx.credentials.BUNDLE_KEY_RP"
        const val BUNDLE_KEY_CLIENT_DATA_HASH =
                "androidx.credentials.BUNDLE_KEY_CLIENT_DATA_HASH"
        const val BUNDLE_KEY_ALLOW_HYBRID = "androidx.credentials.BUNDLE_KEY_ALLOW_HYBRID"
        const val BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION_PRIVILEGED =
                "androidx.credentials.BUNDLE_VALUE_SUBTYPE_GET_PUBLIC_KEY_CREDENTIAL_OPTION" +
                        "_PRIVILEGED"

        @JvmStatic
        internal fun toBundle(
                requestJson: String,
                rp: String,
                clientDataHash: String,
                allowHybrid: Boolean
        ): Bundle {
            val bundle = Bundle()
            bundle.putString(BUNDLE_KEY_REQUEST_JSON, requestJson)
            bundle.putString(BUNDLE_KEY_RP, rp)
            bundle.putString(BUNDLE_KEY_CLIENT_DATA_HASH, clientDataHash)
            bundle.putBoolean(BUNDLE_KEY_ALLOW_HYBRID, allowHybrid)
            return bundle
        }

        @JvmStatic
        fun createFrom(data: Bundle): GetPublicKeyCredentialOptionPrivileged {
            try {
                val requestJson = data.getString(BUNDLE_KEY_REQUEST_JSON)
                val rp = data.getString(BUNDLE_KEY_RP)
                val clientDataHash = data.getString(BUNDLE_KEY_CLIENT_DATA_HASH)
                val allowHybrid = data.get(BUNDLE_KEY_ALLOW_HYBRID)
                return GetPublicKeyCredentialOptionPrivileged(
                        requestJson!!,
                        rp!!,
                        clientDataHash!!,
                        (allowHybrid!!) as Boolean,
                )
            } catch (e: Exception) {
                throw FrameworkClassParsingException()
            }
        }
    }
}
