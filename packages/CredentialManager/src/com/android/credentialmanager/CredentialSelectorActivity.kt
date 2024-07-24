/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.Activity
import android.content.Intent
import android.credentials.selection.BaseDialogResult
import android.credentials.selection.RequestInfo
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.compose.theme.PlatformTheme
import com.android.credentialmanager.common.Constants
import com.android.credentialmanager.common.DialogState
import com.android.credentialmanager.common.ProviderActivityResult
import com.android.credentialmanager.common.StartBalIntentSenderForResultContract
import com.android.credentialmanager.common.ui.Snackbar
import com.android.credentialmanager.createflow.CreateCredentialScreen
import com.android.credentialmanager.createflow.hasContentToDisplay
import com.android.credentialmanager.getflow.GetCredentialScreen
import com.android.credentialmanager.getflow.hasContentToDisplay

@ExperimentalMaterialApi
class CredentialSelectorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(Constants.LOG_TAG, "Creating new CredentialSelectorActivity")
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN,
            0, 0)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE,
            0, 0)

        try {
            val (isCancellationRequest, shouldShowCancellationUi, _) =
                maybeCancelUIUponRequest(intent)
            if (isCancellationRequest && !shouldShowCancellationUi) {
                return
            }
            val credManRepo = CredentialManagerRepo(this, intent, isNewActivity = true)

            val backPressedCallback = object : OnBackPressedCallback(
                true // default to enabled
            ) {
                override fun handleOnBackPressed() {
                    credManRepo.onUserCancel()
                    Log.d(Constants.LOG_TAG, "Activity back triggered: finish the activity.")
                    this@CredentialSelectorActivity.finish()
                }
            }
            onBackPressedDispatcher.addCallback(this, backPressedCallback)

            setContent {
                PlatformTheme {
                    CredentialManagerBottomSheet(credManRepo)
                }
            }
        } catch (e: Exception) {
            onInitializationError(e, intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            val viewModel: CredentialSelectorViewModel by viewModels()
            val (isCancellationRequest, shouldShowCancellationUi, appDisplayName) =
                maybeCancelUIUponRequest(intent, viewModel)
            if (isCancellationRequest) {
                if (shouldShowCancellationUi) {
                    viewModel.onCancellationUiRequested(appDisplayName)
                } else {
                    return
                }
            } else {
                val credManRepo = CredentialManagerRepo(this, intent, isNewActivity = false)
                viewModel.onNewCredentialManagerRepo(credManRepo)
            }
        } catch (e: Exception) {
            onInitializationError(e, intent)
        }
    }

    /**
     * Cancels the UI activity if requested by the backend. Different from the other finishing
     * helpers, this does not report anything back to the Credential Manager service backend.
     *
     * Can potentially show a transient snackbar before finishing, if the request specifies so.
     *
     * Returns <isCancellationRequest, shouldShowCancellationUi, appDisplayName>.
     */
    private fun maybeCancelUIUponRequest(
        intent: Intent,
        viewModel: CredentialSelectorViewModel? = null
    ): Triple<Boolean, Boolean, String?> {
        val cancelUiRequest = CredentialManagerRepo.getCancelUiRequest(intent)
            ?: return Triple(false, false, null)
        if (viewModel != null && !viewModel.shouldCancelCurrentUi(cancelUiRequest.token)) {
            // Cancellation was for a different request, don't cancel the current UI.
            return Triple(true, false, null)
        }
        val shouldShowCancellationUi = cancelUiRequest.shouldShowCancellationExplanation()
        Log.d(
            Constants.LOG_TAG, "Received UI cancellation intent. Should show cancellation" +
            " ui = $shouldShowCancellationUi")
        val appDisplayName = getAppLabel(packageManager, cancelUiRequest.packageName)
        if (!shouldShowCancellationUi) {
            this.finish()
        }
        return Triple(true, shouldShowCancellationUi, appDisplayName)
    }


    @ExperimentalMaterialApi
    @Composable
    private fun CredentialManagerBottomSheet(
        credManRepo: CredentialManagerRepo,
    ) {
        val viewModel: CredentialSelectorViewModel = viewModel {
            CredentialSelectorViewModel(credManRepo)
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
        val cancelRequestState = viewModel.uiState.cancelRequestState
        if (cancelRequestState != null) {
            if (cancelRequestState.appDisplayName == null) {
                Log.d(Constants.LOG_TAG, "Received UI cancel request with an invalid package name.")
                this.finish()
                return
            } else {
                UiCancellationScreen(cancelRequestState.appDisplayName)
            }
        } else if (
            createCredentialUiState != null && hasContentToDisplay(createCredentialUiState)) {
            CreateCredentialScreen(
                viewModel = viewModel,
                createCredentialUiState = createCredentialUiState,
                providerActivityLauncher = launcher
            )
        } else if (getCredentialUiState != null && hasContentToDisplay(getCredentialUiState)) {
            GetCredentialScreen(
                viewModel = viewModel,
                getCredentialUiState = getCredentialUiState,
                providerActivityLauncher = launcher
            )
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
            val settingsIntent = Intent(ACTION_CREDENTIAL_PROVIDER)
            settingsIntent.data = Uri.parse("package:" + this.getPackageName())
            this@CredentialSelectorActivity.startActivity(settingsIntent)
            this@CredentialSelectorActivity.finish()
        }
    }

    private fun onInitializationError(e: Exception, intent: Intent) {
        Log.e(Constants.LOG_TAG, "Failed to show the credential selector; closing the activity", e)
        val resultReceiver = intent.getParcelableExtra(
            android.credentials.selection.Constants.EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java
        )
        val finalResponseResultReceiver = intent.getParcelableExtra(
                android.credentials.selection.Constants.EXTRA_FINAL_RESPONSE_RECEIVER,
                ResultReceiver::class.java
        )

        val requestInfo = intent.extras?.getParcelable(
            RequestInfo.EXTRA_REQUEST_INFO,
            RequestInfo::class.java
        )
        CredentialManagerRepo.sendCancellationCode(
            BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE,
            requestInfo?.token, resultReceiver, finalResponseResultReceiver
        )
        this.finish()
    }

    @Composable
    private fun UiCancellationScreen(appDisplayName: String) {
        Snackbar(
            contentText = stringResource(R.string.request_cancelled_by, appDisplayName),
            onDismiss = { this@CredentialSelectorActivity.finish() },
            dismissOnTimeout = true,
        )
    }

    companion object {
        const val ACTION_CREDENTIAL_PROVIDER = "android.settings.CREDENTIAL_PROVIDER"
    }
}
