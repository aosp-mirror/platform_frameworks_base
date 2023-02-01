/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * Allows extending custom versions of GetCredentialOptions for unique use cases.
 *
 * @property type the credential type determined by the credential-type-specific subclass
 * generated for custom use cases
 * @property requestData the request data in the [Bundle] format, generated for custom use cases
 * @property candidateQueryData the partial request data in the [Bundle] format that will be sent to
 * the provider during the initial candidate query stage, which should not contain sensitive user
 * information
 * @property requireSystemProvider true if must only be fulfilled by a system provider and false
 * otherwise
 * @throws IllegalArgumentException If [type] is empty
 * @throws NullPointerException If [requestData] or [type] is null
 */
open class GetCustomCredentialOption(
    final override val type: String,
    final override val requestData: Bundle,
    final override val candidateQueryData: Bundle,
    @get:JvmName("requireSystemProvider")
    final override val requireSystemProvider: Boolean
) : GetCredentialOption(
    type,
    requestData,
    candidateQueryData,
    requireSystemProvider
) {
    init {
        require(type.isNotEmpty()) { "type should not be empty" }
    }
}
