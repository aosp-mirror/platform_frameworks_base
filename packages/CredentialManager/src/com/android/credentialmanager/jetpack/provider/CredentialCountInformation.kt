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

package com.android.credentialmanager.jetpack.provider

import android.credentials.Credential
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential

class CredentialCountInformation constructor(
        val type: String,
        val count: Int
) {
    companion object {
        @JvmStatic
        fun createPasswordCountInformation(count: Int): CredentialCountInformation {
            return CredentialCountInformation(Credential.TYPE_PASSWORD_CREDENTIAL, count)
        }

        @JvmStatic
        fun getPasswordCount(infos: List<CredentialCountInformation>): Int? {
            return getCountForType(infos, Credential.TYPE_PASSWORD_CREDENTIAL)
        }

        @JvmStatic
        fun createPublicKeyCountInformation(count: Int): CredentialCountInformation {
            return CredentialCountInformation(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, count)
        }

        @JvmStatic
        fun getPasskeyCount(infos: List<CredentialCountInformation>): Int? {
            return getCountForType(infos, PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL)
        }

        @JvmStatic
        fun createTotalCountInformation(count: Int): CredentialCountInformation {
            return CredentialCountInformation("TOTAL_COUNT", count)
        }

        @JvmStatic
        fun getTotalCount(infos: List<CredentialCountInformation>): Int? {
            return getCountForType(infos, "TOTAL_COUNT")
        }

        private fun getCountForType(infos: List<CredentialCountInformation>, type: String): Int? {
            return infos.firstOrNull { info -> info.type == type }?.count
        }
    }
}