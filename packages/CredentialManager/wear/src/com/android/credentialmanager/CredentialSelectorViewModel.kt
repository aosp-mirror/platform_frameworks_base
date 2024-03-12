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

package com.android.credentialmanager

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.ui.mappers.toGet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CredentialSelectorViewModel @Inject constructor(
    private val credentialManagerClient: CredentialManagerClient,
) : ViewModel() {

    val uiState: StateFlow<CredentialSelectorUiState> = credentialManagerClient.requests
        .map { request ->
            when (request) {
                null -> CredentialSelectorUiState.Idle
                is Request.Cancel -> CredentialSelectorUiState.Cancel(request.appName)
                is Request.Close -> CredentialSelectorUiState.Close
                is Request.Create -> CredentialSelectorUiState.Create
                is Request.Get -> request.toGet()
            }
        }
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CredentialSelectorUiState.Idle,
        )

    fun updateRequest(intent: Intent) {
            credentialManagerClient.updateRequest(intent = intent)
    }
}

sealed class CredentialSelectorUiState {
    data object Idle : CredentialSelectorUiState()
    sealed class Get : CredentialSelectorUiState() {
        data object SingleProviderSinglePasskey : Get()
        data object SingleProviderSinglePassword : Get()

        // TODO: b/301206470 add the remaining states
    }

    data object Create : CredentialSelectorUiState()
    data class Cancel(val appName: String) : CredentialSelectorUiState()
    data object Close : CredentialSelectorUiState()
}
