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

package com.android.credentialmanager.ui.screens.single.password

import android.content.Intent
import android.credentials.selection.UserSelectionDialogResult
import android.credentials.selection.ProviderPendingIntentResponse
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import com.android.credentialmanager.ktx.getIntentSenderRequest
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.screens.single.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SinglePasswordScreenViewModel @Inject constructor(
    private val credentialManagerClient: CredentialManagerClient,
) : ViewModel() {

    private lateinit var requestGet: Request.Get
    private lateinit var entryInfo: CredentialEntryInfo

    private val _uiState =
        MutableStateFlow<UiState>(UiState.CredentialScreen)
    val uiState: StateFlow<UiState> = _uiState

    @MainThread
    fun initialize(entryInfo: CredentialEntryInfo) {
        this.entryInfo = entryInfo
    }

    fun onDismissClick() {
        _uiState.value = UiState.Cancel
    }

    fun onContinueClick() {
        _uiState.value = UiState.CredentialSelected(
            intentSenderRequest = entryInfo.getIntentSenderRequest()
        )
    }

    fun onSignInOptionsClick() {
    }

    fun onPasswordInfoRetrieved(
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        val userSelectionDialogResult = UserSelectionDialogResult(
            requestGet.token,
            entryInfo.providerId,
            entryInfo.entryKey,
            entryInfo.entrySubkey,
            if (resultCode != null) ProviderPendingIntentResponse(resultCode, resultData) else null
        )
        credentialManagerClient.sendResult(userSelectionDialogResult)
    }
}
