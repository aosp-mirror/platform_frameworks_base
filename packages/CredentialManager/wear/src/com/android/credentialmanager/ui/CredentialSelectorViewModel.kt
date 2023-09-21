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

package com.android.credentialmanager.ui

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.credentialmanager.ui.ktx.appLabel
import com.android.credentialmanager.ui.ktx.requestInfo
import com.android.credentialmanager.ui.mapper.toGet
import com.android.credentialmanager.ui.model.PasskeyUiModel
import com.android.credentialmanager.ui.model.PasswordUiModel
import com.android.credentialmanager.ui.model.Request
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CredentialSelectorViewModel(
    private val application: Application
) : AndroidViewModel(application = application) {

    private val _uiState =
        MutableStateFlow<CredentialSelectorUiState>(CredentialSelectorUiState.Idle)
    val uiState: StateFlow<CredentialSelectorUiState> = _uiState

    fun onNewIntent(intent: Intent, previousIntent: Intent? = null) {
        viewModelScope.launch {
            val request = intent.parse()
            if (shouldFinishActivity(request = request, previousIntent = previousIntent)) {
                _uiState.value = CredentialSelectorUiState.Finish
            } else {
                when (request) {
                    is Request.Cancel -> {
                        request.appPackageName?.let { appPackageName ->
                            application.packageManager.appLabel(appPackageName)?.let { appLabel ->
                                _uiState.value = CredentialSelectorUiState.Cancel(appLabel)
                            } ?: run {
                                Log.d(TAG,
                                    "Received UI cancel request with an invalid package name.")
                                _uiState.value = CredentialSelectorUiState.Finish
                            }
                        } ?: run {
                            Log.d(TAG, "Received UI cancel request with an invalid package name.")
                            _uiState.value = CredentialSelectorUiState.Finish
                        }
                    }

                    Request.Create -> {
                        _uiState.value = CredentialSelectorUiState.Create
                    }

                    is Request.Get -> {
                        _uiState.value = request.toGet()
                    }
                }
            }
        }
    }

    /**
     * Check if backend requested the UI activity to be cancelled. Different from the other
     * finishing flows, this one does not report anything back to the Credential Manager service
     * backend.
     */
    private fun shouldFinishActivity(request: Request, previousIntent: Intent? = null): Boolean {
        if (request !is Request.Cancel) {
            return false
        } else {
            Log.d(
                TAG, "Received UI cancellation intent. Should show cancellation" +
                " ui = ${request.showCancellationUi}")

            previousIntent?.let {
                val previousUiRequest = previousIntent.parse()

                if (previousUiRequest is Request.Cancel) {
                    val previousToken = previousIntent.requestInfo?.token
                    val currentToken = previousIntent.requestInfo?.token

                    if (previousToken != currentToken) {
                        // Cancellation was for a different request, don't cancel the current UI.
                        return false
                    }
                }
            }

            return !request.showCancellationUi
        }
    }
}

sealed class CredentialSelectorUiState {
    data object Idle : CredentialSelectorUiState()
    sealed class Get : CredentialSelectorUiState() {
        data class SingleProviderSinglePasskey(val passkeyUiModel: PasskeyUiModel) : Get()
        data class SingleProviderSinglePassword(val passwordUiModel: PasswordUiModel) : Get()

        // TODO: b/301206470 add the remaining states
    }

    data object Create : CredentialSelectorUiState()
    data class Cancel(val appName: String) : CredentialSelectorUiState()
    data object Finish : CredentialSelectorUiState()
}
