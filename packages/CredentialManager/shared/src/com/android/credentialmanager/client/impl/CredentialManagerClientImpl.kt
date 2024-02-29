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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.credentials.selection.BaseDialogResult
import android.credentials.selection.BaseDialogResult.RESULT_CODE_DIALOG_USER_CANCELED
import android.credentials.selection.Constants
import android.credentials.selection.ProviderPendingIntentResponse
import android.credentials.selection.UserSelectionDialogResult
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import com.android.credentialmanager.TAG
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.parse
import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.model.EntryInfo

import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class CredentialManagerClientImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialManagerClient {

    private val _requests = MutableStateFlow<Request?>(null)
    override val requests: StateFlow<Request?> = _requests


    override fun updateRequest(intent: Intent) {
        val request: Request
        try {
            request = intent.parse(context)
        } catch (e: Exception) {
            sendError(BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE)
            return
        }
        Log.d(TAG, "Request parsed: $request, client instance: $this")
        if (request is Request.Cancel || request is Request.Close) {
            if (request.token != null && request.token != _requests.value?.token) {
                Log.w(TAG, "drop terminate request for previous session.")
                return
            }
        }
        _requests.value = request
    }

    override fun sendError(resultCode: Int) {
        Log.w(TAG, "Error occurred, resultCode: $resultCode, current request: ${ requests.value }")
        requests.value?.sendCancellationCode(resultCode)
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

    override fun sendEntrySelectionResult(
        entryInfo: EntryInfo,
        resultCode: Int?,
        resultData: Intent?,
        isAutoSelected: Boolean,
    ): Boolean {
        Log.d(TAG, "sendEntrySelectionResult, resultCode: $resultCode, resultData: $resultData," +
                " entryInfo: $entryInfo")
        val currentRequest = requests.value
        check(currentRequest is Request.Get) { "current request is not get." }
        if (resultCode == Activity.RESULT_CANCELED) {
            if (isAutoSelected) {
                currentRequest.sendCancellationCode(RESULT_CODE_DIALOG_USER_CANCELED)
            }
            return isAutoSelected
        }
        val userSelectionDialogResult = UserSelectionDialogResult(
            currentRequest.token,
            entryInfo.providerId,
            entryInfo.entryKey,
            entryInfo.entrySubkey,
            if (resultCode != null) ProviderPendingIntentResponse(
                resultCode,
                resultData
            ) else null
        )
        sendResult(userSelectionDialogResult)
        return entryInfo.shouldTerminateUiUponSuccessfulProviderResult
    }

    private fun Request.sendCancellationCode(cancelCode: Int) {
        sendCancellationCode(
            cancelCode = cancelCode,
            requestToken = token,
            resultReceiver = resultReceiver,
            finalResponseReceiver = finalResponseReceiver
        )
    }

    private fun sendCancellationCode(
        cancelCode: Int,
        requestToken: IBinder?,
        resultReceiver: ResultReceiver?,
        finalResponseReceiver: ResultReceiver?
    ) {
        if (requestToken != null && resultReceiver != null) {
            val resultData = Bundle().apply {
                putParcelable(Constants.EXTRA_FINAL_RESPONSE_RECEIVER, finalResponseReceiver)
            }
            BaseDialogResult.addToBundle(BaseDialogResult(requestToken), resultData)
            resultReceiver.send(cancelCode, resultData)
        }
    }
}
