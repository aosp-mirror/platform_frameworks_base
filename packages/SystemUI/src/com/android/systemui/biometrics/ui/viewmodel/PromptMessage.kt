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

package com.android.systemui.biometrics.ui.viewmodel

/**
 * A help, hint, or error message to show.
 *
 * These typically correspond to the same category of help/error callbacks from the underlying HAL
 * that runs the biometric operation, but may be customized by the framework.
 */
sealed interface PromptMessage {

    /** The message to show the user or the empty string. */
    val message: String
        get() =
            when (this) {
                is Error -> errorMessage
                is Help -> helpMessage
                else -> ""
            }

    /** If this is an [Error]. */
    val isError: Boolean
        get() = this is Error

    /** An error message. */
    data class Error(val errorMessage: String) : PromptMessage

    /** A help message. */
    data class Help(val helpMessage: String) : PromptMessage

    /** No message. */
    object Empty : PromptMessage
}
