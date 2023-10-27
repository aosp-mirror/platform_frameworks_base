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
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.credentialmanager.TAG
import com.android.credentialmanager.ktx.getIntentSenderRequest
import com.android.credentialmanager.model.Password
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.repository.PasswordRepository
import com.android.credentialmanager.repository.RequestRepository
import com.android.credentialmanager.ui.model.PasswordUiModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SinglePasswordScreenViewModel @Inject constructor(
    private val requestRepository: RequestRepository,
    private val passwordRepository: PasswordRepository,
) : ViewModel() {

    private var initializeCalled = false

    private lateinit var requestGet: Request.Get
    private lateinit var password: Password

    private val _uiState =
        MutableStateFlow<SinglePasswordScreenUiState>(SinglePasswordScreenUiState.Idle)
    val uiState: StateFlow<SinglePasswordScreenUiState> = _uiState

    @MainThread
    fun initialize() {
        if (initializeCalled) return
        initializeCalled = true

        viewModelScope.launch {
            val request = requestRepository.requests.first()
            Log.d(TAG, "request: $request")

            if (request !is Request.Get) {
                _uiState.value = SinglePasswordScreenUiState.Error
            } else {
                requestGet = request
                if (requestGet.passwordEntries.isEmpty()) {
                    Log.d(TAG, "Empty passwordEntries")
                    _uiState.value = SinglePasswordScreenUiState.Error
                } else {
                    password = requestGet.passwordEntries.first()
                    _uiState.value = SinglePasswordScreenUiState.Loaded(
                        PasswordUiModel(
                            email = password.passwordCredentialEntry.username.toString(),
                        )
                    )
                }
            }
        }
    }

    fun onCancelClick() {
        _uiState.value = SinglePasswordScreenUiState.Cancel
    }

    fun onOKClick() {
        _uiState.value = SinglePasswordScreenUiState.PasswordSelected(
            intentSenderRequest = password.getIntentSenderRequest()
        )
    }

    fun onPasswordInfoRetrieved(
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        viewModelScope.launch {
            passwordRepository.selectPassword(
                password = password,
                request = requestGet,
                resultCode = resultCode,
                resultData = resultData
            )

            _uiState.value = SinglePasswordScreenUiState.Completed
        }
    }
}

sealed class SinglePasswordScreenUiState {
    data object Idle : SinglePasswordScreenUiState()
    data class Loaded(val passwordUiModel: PasswordUiModel) : SinglePasswordScreenUiState()
    data class PasswordSelected(
        val intentSenderRequest: IntentSenderRequest
    ) : SinglePasswordScreenUiState()

    data object Cancel : SinglePasswordScreenUiState()
    data object Error : SinglePasswordScreenUiState()
    data object Completed : SinglePasswordScreenUiState()
}
