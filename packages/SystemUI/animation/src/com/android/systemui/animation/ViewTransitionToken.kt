/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.animation

/**
 * A token uniquely mapped to a View in [ViewTransitionRegistry]. This token is guaranteed to be
 * unique as timestamp is appended to the token string
 *
 * @constructor creates an instance of [ViewTransitionToken] with token as "timestamp" or
 * "ClassName_timestamp"
 *
 * @property token String value of a unique token
 */
@JvmInline
value class ViewTransitionToken private constructor(val token: String) {
    constructor() : this(token = System.currentTimeMillis().toString())
    constructor(clazz: Class<*>) : this(token = clazz.simpleName + "_${System.currentTimeMillis()}")
}
