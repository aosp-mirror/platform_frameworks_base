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

package com.android.systemui.keyguard.shared.model

import com.android.keyguard.TrustGrantFlags

sealed class TrustMessage

/** Represents the trust state */
data class TrustModel(
    /** If true, the system believes the environment to be trusted. */
    val isTrusted: Boolean,
    /** The user, for which the trust changed. */
    val userId: Int,
    val flags: TrustGrantFlags,
) : TrustMessage()

/** Represents where trust agents are enabled for a particular user. */
data class TrustManagedModel(
    val userId: Int,
    val isTrustManaged: Boolean,
) : TrustMessage()
