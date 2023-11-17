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

package com.android.credentialmanager.client

import android.content.Intent
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.UserSelectionDialogResult
import com.android.credentialmanager.model.Request
import kotlinx.coroutines.flow.StateFlow

interface CredentialManagerClient {
    /** The UI should monitor the request update. */
    val requests: StateFlow<Request?>

    /** The UI got a new intent; update the request state. */
    fun updateRequest(intent: Intent)

    /** Sends an error encountered during the UI. */
    fun sendError(
        @BaseDialogResult.ResultCode resultCode: Int,
        errorMessage: String? = null,
    )

    /**
     * Sends a response to the system service. The response
     * contains information about the user's choice from the selector
     * UI and the result of the provider operation launched with
     * that selection.
     *
     * If the user choice was a normal entry, then the UI can finish
     * the activity immediately. Otherwise if it was an authentication
     * (locked) entry, then the UI will need to stay up and wait for
     * a new intent from the system containing the new data for
     * display.
     *
     * Note that if the provider operation returns RESULT_CANCELED,
     * then the selector should not send that result back, and instead
     * re-display the options to allow a user to have another choice.
     *
     * @throws [IllegalStateException] if [requests] is not [Request.Get].
     */
    fun sendResult(result: UserSelectionDialogResult)
}