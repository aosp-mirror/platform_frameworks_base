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

package com.android.credentialmanager

import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.Composable
import com.android.credentialmanager.model.EntryInfo
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import kotlinx.coroutines.flow.StateFlow

/** Engine of the credential selecting flow. */
interface FlowEngine {
    /** UI state of the selector app */
    val uiState: StateFlow<CredentialSelectorUiState>
    /** Back from previous stage. */
    fun back()
    /** Cancels the selection flow. */
    fun cancel()
    /** Opens secondary screen. */
    fun openSecondaryScreen()
    /**
     * Sends [entryInfo] as long as result after launching [EntryInfo.pendingIntent] with
     * [EntryInfo.fillInIntent].
     *
     * @param entryInfo: selected entry.
     * @param resultCode: result code received after launch.
     * @param resultData: data received after launch
     * @param isAutoSelected: whether the entry is auto selected or by user.
     */
    fun sendSelectionResult(
        entryInfo: EntryInfo,
        resultCode: Int? = null,
        resultData: Intent? = null,
        isAutoSelected: Boolean = false,
    )

    /**
     * Helper function to get an entry selector.
     *
     * @return selector fun consumes selected [EntryInfo]. Once invoked, [IntentSenderRequest] would
     * be launched and invocation of [sendSelectionResult] would happen right after launching result
     * coming back.
     */
    @Composable
    fun getEntrySelector(): (entry: EntryInfo, isAutoSelected: Boolean) -> Unit
}

/** UI state of the selector app */
sealed class CredentialSelectorUiState {
    /** Idle UI state, no request is going on. */
    data object Idle : CredentialSelectorUiState()
    /** Getting credential UI state. */
    sealed class Get : CredentialSelectorUiState() {
        /** Getting credential UI state when there is only one credential available. */
        data class SingleEntry(val entry: CredentialEntryInfo) : Get()
        /**
         * Getting credential UI state on primary screen when there is are multiple accounts.
         */
        data class MultipleEntryPrimaryScreen(
            val icon: Drawable?,
            val sortedEntries: List<CredentialEntryInfo>,
            val authenticationEntryList: List<AuthenticationEntryInfo>,
            ) : Get()
        /** Getting credential UI state on secondary screen when there are multiple accounts available. */
        data class MultipleEntry(
            val accounts: List<PerNameEntries>,
            val actionEntryList: List<ActionEntryInfo>,
            val authenticationEntryList: List<AuthenticationEntryInfo>,
        ) : Get() {
            data class PerNameEntries(
                val name: String,
                val sortedCredentialEntryList: List<CredentialEntryInfo>,
            )
        }
    }
    /** Creating credential UI state. */
    data object Create : CredentialSelectorUiState()
    /** Request is cancelling by [appName]. */
    data class Cancel(val appName: String) : CredentialSelectorUiState()
    /** Request is closed peacefully. */
    data object Close : CredentialSelectorUiState()
}