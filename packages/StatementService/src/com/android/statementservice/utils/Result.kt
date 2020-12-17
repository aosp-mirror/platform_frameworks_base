/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.statementservice.utils

sealed class Result<T> {

    fun successValueOrNull() = (this as? Success<T>)?.value

    data class Success<T>(val value: T) : Result<T>()
    data class Failure<T>(val message: String? = null, val throwable: Throwable? = null) :
        Result<T>() {

        constructor(message: String) : this(message = message, throwable = null)
        constructor(throwable: Throwable) : this(message = null, throwable = throwable)

        @Suppress("UNCHECKED_CAST")
        fun <T> asType() = this as Result<T>
    }
}
