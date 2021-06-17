/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.statementservice.domain

import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationManager

/**
 * Wraps known [DomainVerificationManager] status codes so that they can be used in a when
 * statement. Unknown codes are coerced to [VerifyStatus.UNKNOWN] and should be treated as
 * unverified.
 *
 * Also includes error codes specific to this implementation of the domain verification agent.
 * These must be stable across all versions, as codes are persisted to disk. They do not
 * technically have to be stable across different device factory resets, since they will be reset
 * once the apps are re-initialized, but easier to keep them unique forever.
 */
enum class VerifyStatus(val value: Int) {
    NO_RESPONSE(DomainVerificationInfo.STATE_NO_RESPONSE),
    SUCCESS(DomainVerificationInfo.STATE_SUCCESS),

    UNKNOWN(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED),
    FAILURE_LEGACY_UNSUPPORTED_WILDCARD(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 1),
    FAILURE_REJECTED_BY_SERVER(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 2),
    FAILURE_TIMEOUT(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 3),
    FAILURE_UNKNOWN(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 4),
    FAILURE_REDIRECT(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 5),

    // Failed to retrieve signature information from PackageManager
    FAILURE_PACKAGE_MANAGER(DomainVerificationInfo.STATE_FIRST_VERIFIER_DEFINED + 6);

    companion object {
        fun shouldRetry(state: Int): Boolean {
            if (state == DomainVerificationInfo.STATE_UNMODIFIABLE) {
                return false
            }

            val status = values().find { it.value == state } ?: return true
            return when (status) {
                SUCCESS,
                FAILURE_LEGACY_UNSUPPORTED_WILDCARD,
                FAILURE_REJECTED_BY_SERVER,
                FAILURE_PACKAGE_MANAGER,
                UNKNOWN -> false
                NO_RESPONSE,
                FAILURE_TIMEOUT,
                FAILURE_UNKNOWN,
                FAILURE_REDIRECT -> true
            }
        }
    }
}
