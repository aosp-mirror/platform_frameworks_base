/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.authentication.shared.model

/** Models a state for temporarily locking out the next authentication attempt. */
data class AuthenticationLockoutModel(

    /** Number of failed authentication attempts so far. If not locked out this will be `0`. */
    val failedAttemptCount: Int = 0,

    /**
     * Remaining amount of time, in seconds, before another authentication attempt can be done. If
     * not locked out this will be `0`.
     *
     * This number is changed throughout the lockout.
     *
     * Note: this isn't precise (in milliseconds), but rounded up to ensure "at most" this amount of
     * seconds remains.
     */
    val remainingSeconds: Int = 0,
)
