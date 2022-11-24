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

package com.android.server.permission.access.external

object SigningDetails {
    fun hasCommonSignerWithCapability(otherDetails: SigningDetails, flags: Int): Boolean {
        throw NotImplementedError()
    }

    fun hasAncestorOrSelf(oldDetails: SigningDetails): Boolean {
        throw NotImplementedError()
    }

    fun checkCapability(oldDetails: SigningDetails, flags: Int): Boolean {
        throw NotImplementedError()
    }

    fun hasAncestorOrSelfWithDigest(certDigests: Set<String>): Boolean {
        throw NotImplementedError()
    }

    class CertCapabilities {
        companion object {
            /** grant SIGNATURE permissions to pkgs with this cert  */
            var PERMISSION = 4
        }
    }
}
