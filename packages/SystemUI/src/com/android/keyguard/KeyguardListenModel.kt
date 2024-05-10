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

package com.android.keyguard

import java.text.SimpleDateFormat
import java.util.Locale

/** Verbose logging for various keyguard listening states. */
sealed class KeyguardListenModel {
    /** Timestamp of the state change. */
    abstract var timeMillis: Long
    /** Current user. */
    abstract var userId: Int
    /** If keyguard is listening for the modality represented by this model. */
    abstract var listening: Boolean
}

val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
