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

package com.android.credentialmanager.client.impl

import android.content.Intent
import android.content.pm.PackageManager
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.UserSelectionDialogResult
import android.os.Bundle
import android.util.Log
import com.android.credentialmanager.TAG
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.parse
import com.android.credentialmanager.client.CredentialManagerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class CredentialManagerClientImpl @Inject constructor(
        private val packageManager: PackageManager,
) : CredentialManagerClient {

    private val _requests = MutableStateFlow<Request?>(null)
    override val requests: StateFlow<Request?> = _requests


    override fun updateRequest(intent: Intent) {
        val request = intent.parse(
            packageManager = packageManager,
        )
        Log.d(TAG, "Request parsed: $request, client instance: $this")
        if (request is Request.Cancel || request is Request.Close) {
            if (request.token != null && request.token != _requests.value?.token) {
                Log.w(TAG, "drop terminate request for previous session.")
                return
            }
        }
        _requests.value = request
    }

    override fun sendError(resultCode: Int, errorMessage: String?) {
        TODO("b/300422310 - [Wear] Implement UI for cancellation request with message")
    }

    override fun sendResult(result: UserSelectionDialogResult) {
        val currentRequest = requests.value
        check(currentRequest is Request.Get) { "current request is not get." }
        currentRequest.resultReceiver?.let { receiver ->
            val resultDataBundle = Bundle()
            UserSelectionDialogResult.addToBundle(result, resultDataBundle)
            receiver.send(
                BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
                resultDataBundle
            )
        }
    }
}
