/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.credentialmanager.model

/**
 * This allows reading the data from the request, and holding that state around the framework.
 * The [opId] bit is required for some authentication flows where CryptoObjects are used.
 * The [allowedAuthenticators] is needed for all flows, and our flow ensures this value is never
 * null.
 */
data class BiometricRequestInfo(
    val opId: Long? = null,
    val allowedAuthenticators: Int
)