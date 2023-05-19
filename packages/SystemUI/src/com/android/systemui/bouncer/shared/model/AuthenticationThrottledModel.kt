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

package com.android.systemui.bouncer.shared.model

/**
 * Models application state for when further authentication attempts are being throttled due to too
 * many consecutive failed authentication attempts.
 */
data class AuthenticationThrottledModel(
    /** Total number of failed attempts so far. */
    val failedAttemptCount: Int,
    /** Total amount of time the user has to wait before attempting again. */
    val totalDurationSec: Int,
    /** Remaining amount of time the user has to wait before attempting again. */
    val remainingDurationSec: Int,
)
