/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.mapper

import android.content.Intent
import com.android.credentialmanager.ktx.cancelUiRequest
import com.android.credentialmanager.ktx.requestInfo
import com.android.credentialmanager.model.Request

fun Intent.toRequestClose(
    previousIntent: Intent? = null,
): Request.Close? {
    // Close request comes as "Cancel" request from Credential Manager API
    this.cancelUiRequest?.let { cancelUiRequest ->

        if (cancelUiRequest.shouldShowCancellationUi()) {
            // Current request is to Cancel and not to Close
            return null
        }

        previousIntent?.let {
            val previousToken = previousIntent.requestInfo?.token
            val currentToken = this.requestInfo?.token

            if (previousToken != currentToken) {
                // Current cancellation is for a different request, don't close the current flow.
                return null
            }
        }

        return Request.Close
    }

    return null
}