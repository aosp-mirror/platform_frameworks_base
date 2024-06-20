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

@file:OptIn(ExperimentalComposeUiApi::class)

package com.android.systemui.common.ui.compose

import android.annotation.UserIdInt
import android.os.UserHandle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Wrapper for input connection composables that need to be aware of the selected user to connect to
 * the correct instance of on-device services like autofill, autocorrect, etc.
 *
 * Usage:
 * ```
 * @Composable
 * fun YourFunction(viewModel: YourViewModel) {
 *     val selectedUserId by viewModel.selectedUserId.collectAsStateWithLifecycle()
 *
 *     SelectedUserAwareInputConnection(selectedUserId) {
 *         TextField(...)
 *     }
 * }
 * ```
 */
@Composable
fun SelectedUserAwareInputConnection(
    @UserIdInt selectedUserId: Int,
    content: @Composable () -> Unit,
) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            // Create a new request to wrap the incoming one with some custom logic.
            val modifiedRequest =
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val inputConnection = request.createInputConnection(outAttributes)
                        // After the original request finishes initializing the EditorInfo we can
                        // customize it. If we needed to we could also wrap the InputConnection
                        // before
                        // returning it.
                        updateEditorInfo(outAttributes)
                        return inputConnection
                    }

                    fun updateEditorInfo(outAttributes: EditorInfo) {
                        outAttributes.targetInputMethodUser = UserHandle.of(selectedUserId)
                    }
                }

            // Send our wrapping request to the next handler, which could be the system or another
            // interceptor up the tree.
            nextHandler.startInputMethod(modifiedRequest)
        }
    ) {
        content()
    }
}
