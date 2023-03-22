/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.RequestInfo
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.StartBalIntentSenderForResultContract
import com.android.credentialmanager.createflow.CreateCredentialScreen
import com.android.credentialmanager.createflow.hasContentToDisplay
import com.android.credentialmanager.getflow.GetCredentialScreen
import com.android.credentialmanager.getflow.GetGenericCredentialScreen
import com.android.credentialmanager.getflow.hasContentToDisplay
import com.android.credentialmanager.getflow.isFallbackScreen
import com.android.credentialmanager.ui.theme.PlatformTheme

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(Constants.LOG_TAG, "Creating new CredentialSelectorActivity")
        try {
            if (CredentialManagerRepo.getCancelUiRequestToken(intent) != null) {
                Log.d(
                    Constants.LOG_TAG, "Received UI cancellation intent; cancelling the activity.")
                this.finish()
                return
            }
            val userConfigRepo = UserConfigRepo(this)
            val credManRepo = CredentialManagerRepo(this, intent, userConfigRepo)
            setContent {
                PlatformTheme {
                    CredentialManagerBottomSheet(
                        credManRepo,
                        userConfigRepo
                    )
                }
            }
        } catch (e: Exception) {
            onInitializationError(e, intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(Constants.LOG_TAG, "Existing activity received new intent")
        try {
            val cancelUiRequestToken = CredentialManagerRepo.getCancelUiRequestToken(intent)
            val viewModel: CredentialSelectorViewModel by viewModels()
            if (cancelUiRequestToken != null &&
                viewModel.shouldCancelCurrentUi(cancelUiRequestToken)) {
                Log.d(
                    Constants.LOG_TAG, "Received UI cancellation intent; cancelling the activity.")
                this.finish()
                return
            } else {
                val userConfigRepo = UserConfigRepo(this)
                val credManRepo = CredentialManagerRepo(this, intent, userConfigRepo)
                viewModel.onNewCredentialManagerRepo(credManRepo)
            }
        } catch (e: Exception) {
            onInitializationError(e, intent)
        }
    }

    @ExperimentalMaterialApi
    @Composable
    fun CredentialManagerBottomSheet(
        credManRepo: CredentialManagerRepo,
        userConfigRepo: UserConfigRepo
    ) {
        val viewModel: CredentialSelectorViewModel = viewModel {
            CredentialSelectorViewModel(credManRepo, userConfigRepo)
        }
        val launcher = rememberLauncherForActivityResult(
            StartBalIntentSenderForResultContract()
        ) {
            viewModel.onProviderActivityResult(ProviderActivityResult(it.resultCode, it.data))
        }
        LaunchedEffect(viewModel.uiState.dialogState) {
            handleDialogState(viewModel.uiState.dialogState)
        }

        val createCredentialUiState = viewModel.uiState.createCredentialUiState
        val getCredentialUiState = viewModel.uiState.getCredentialUiState
        if (createCredentialUiState != null && hasContentToDisplay(createCredentialUiState)) {
            CreateCredentialScreen(
                viewModel = viewModel,
                createCredentialUiState = createCredentialUiState,
                providerActivityLauncher = launcher
            )
        } else if (getCredentialUiState != null && hasContentToDisplay(getCredentialUiState)) {
            if (isFallbackScreen(getCredentialUiState)) {
                GetGenericCredentialScreen(
                        viewModel = viewModel,
                        getCredentialUiState = getCredentialUiState,
                        providerActivityLauncher = launcher
                )
            } else {
                GetCredentialScreen(
                        viewModel = viewModel,
                        getCredentialUiState = getCredentialUiState,
                        providerActivityLauncher = launcher
                )
            }
        } else {
            Log.d(Constants.LOG_TAG, "UI wasn't able to render neither get nor create flow")
            reportInstantiationErrorAndFinishActivity(credManRepo)
        }
    }

    private fun reportInstantiationErrorAndFinishActivity(credManRepo: CredentialManagerRepo) {
        Log.w(Constants.LOG_TAG, "Finishing the activity due to instantiation failure.")
        credManRepo.onParsingFailureCancel()
        this@CredentialSelectorActivity.finish()
    }

    private fun handleDialogState(dialogState: DialogState) {
        if (dialogState == DialogState.COMPLETE) {
            Log.d(Constants.LOG_TAG, "Received signal to finish the activity.")
            this@CredentialSelectorActivity.finish()
        } else if (dialogState == DialogState.CANCELED_FOR_SETTINGS) {
            Log.d(Constants.LOG_TAG, "Received signal to finish the activity and launch settings.")
            this@CredentialSelectorActivity.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
            this@CredentialSelectorActivity.finish()
        }
    }

    private fun onInitializationError(e: Exception, intent: Intent) {
        Log.e(Constants.LOG_TAG, "Failed to show the credential selector; closing the activity", e)
        val resultReceiver = intent.getParcelableExtra(
            android.credentials.ui.Constants.EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java
        )
        val requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        )
        CredentialManagerRepo.sendCancellationCode(
            BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE,
            requestInfo?.token, resultReceiver
        )
        this.finish()
    }
}
