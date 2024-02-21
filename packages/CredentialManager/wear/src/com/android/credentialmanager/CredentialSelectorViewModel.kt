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
import android.credentials.selection.BaseDialogResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.credentialmanager.CredentialSelectorUiState.Get
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.mappers.toGet
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.android.credentialmanager.CredentialSelectorUiState.Cancel
import com.android.credentialmanager.CredentialSelectorUiState.Close
import com.android.credentialmanager.CredentialSelectorUiState.Create
import com.android.credentialmanager.CredentialSelectorUiState.Idle
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.ktx.getIntentSenderRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CredentialSelectorViewModel @Inject constructor(
    private val credentialManagerClient: CredentialManagerClient,
) : FlowEngine, ViewModel() {
    private val isPrimaryScreen = MutableStateFlow(true)
    private val shouldClose = MutableStateFlow(false)
    private lateinit var selectedEntry: EntryInfo
    private var isAutoSelected: Boolean = false
    val uiState: StateFlow<CredentialSelectorUiState> =
        combine(
            credentialManagerClient.requests,
            isPrimaryScreen,
            shouldClose
        ) { request, isPrimary, shouldClose ->
            Log.d(TAG, "Request updated: " + request?.toString() +
                    " isClose: " + shouldClose.toString() +
                    " isPrimaryScreen: " + isPrimary.toString())
            if (shouldClose) {
                return@combine Close
            }

            when (request) {
                null -> Idle
                is Request.Cancel -> Cancel(request.appName)
                is Request.Close -> Close
                is Request.Create -> Create
                is Request.Get -> request.toGet(isPrimary)
            }
        }
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = Idle,
        )

    fun updateRequest(intent: Intent) {
            credentialManagerClient.updateRequest(intent = intent)
    }

    override fun back() {
        Log.d(TAG, "OnBackPressed")
        when (uiState.value) {
            is Get.MultipleEntry -> isPrimaryScreen.value = true
            is Create, Close, is Cancel, Idle -> shouldClose.value = true
            is Get.SingleEntry, is Get.SingleEntryPerAccount -> cancel()
        }
    }

    override fun cancel() {
        credentialManagerClient.sendError(BaseDialogResult.RESULT_CODE_DIALOG_USER_CANCELED)
        shouldClose.value = true
    }

    override fun openSecondaryScreen() {
        isPrimaryScreen.value = false
    }

    override fun sendSelectionResult(
        entryInfo: EntryInfo,
        resultCode: Int?,
        resultData: Intent?,
        isAutoSelected: Boolean,
    ) {
        val result = credentialManagerClient.sendEntrySelectionResult(
            entryInfo = entryInfo,
            resultCode = resultCode,
            resultData = resultData,
            isAutoSelected = isAutoSelected
        )
        shouldClose.value = result
    }

    @Composable
    override fun getEntrySelector(): (entry: EntryInfo, isAutoSelected: Boolean) -> Unit {
        val launcher = rememberLauncherForActivityResult(
            StartBalIntentSenderForResultContract()
        ) {
            sendSelectionResult(entryInfo = selectedEntry,
                resultCode = it.resultCode,
                resultData = it.data,
                isAutoSelected = isAutoSelected)
        }
        return { selected, autoSelect ->
            selectedEntry = selected
            isAutoSelected = autoSelect
            selected.getIntentSenderRequest()?.let {
                launcher.launch(it)
            } ?: Log.w(TAG, "Cannot parse IntentSenderRequest")
        }
    }
}

sealed class CredentialSelectorUiState {
    data object Idle : CredentialSelectorUiState()
    sealed class Get : CredentialSelectorUiState() {
        data class SingleEntry(val entry: CredentialEntryInfo) : Get()
        data class SingleEntryPerAccount(val sortedEntries: List<CredentialEntryInfo>) : Get()
        data class MultipleEntry(
            val accounts: List<PerUserNameEntries>,
            val actionEntryList: List<ActionEntryInfo>,
            val authenticationEntryList: List<AuthenticationEntryInfo>,
        ) : Get() {
            data class PerUserNameEntries(
                val userName: String,
                val sortedCredentialEntryList: List<CredentialEntryInfo>,
            )
        }

        // TODO: b/301206470 add the remaining states
    }

    data object Create : CredentialSelectorUiState()
    data class Cancel(val appName: String) : CredentialSelectorUiState()
    data object Close : CredentialSelectorUiState()
}
