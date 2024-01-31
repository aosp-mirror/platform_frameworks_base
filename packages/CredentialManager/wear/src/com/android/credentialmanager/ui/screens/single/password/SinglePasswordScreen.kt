/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalHorologistApi::class)

package com.android.credentialmanager.ui.screens.single.password

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.credentialmanager.CredentialSelectorUiState.Get.SingleEntry
import com.android.credentialmanager.R
import com.android.credentialmanager.TAG
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.ui.components.PasswordRow
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.model.PasswordUiModel
import com.android.credentialmanager.ui.screens.single.SingleAccountScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState

@Composable
fun SinglePasswordScreen(
    state: SingleEntry,
    columnState: ScalingLazyColumnState,
    onCloseApp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SinglePasswordScreenViewModel = hiltViewModel(),
) {
    viewModel.initialize(state.entry)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        SinglePasswordScreenUiState.Idle -> {
            // TODO: b/301206470 implement latency version of the screen
        }

        is SinglePasswordScreenUiState.Loaded -> {
            SinglePasswordScreen(
                passwordUiModel = state.passwordUiModel,
                columnState = columnState,
                modifier = modifier
            )
        }

        is SinglePasswordScreenUiState.PasswordSelected -> {
            val launcher = rememberLauncherForActivityResult(
                StartBalIntentSenderForResultContract()
            ) {
                viewModel.onPasswordInfoRetrieved(it.resultCode, it.data)
            }

            SideEffect {
                state.intentSenderRequest?.let {
                    launcher.launch(it)
                }
            }
        }

        SinglePasswordScreenUiState.Cancel -> {
            // TODO: b/301206470 implement navigation for when user taps cancel
        }

        SinglePasswordScreenUiState.Error -> {
            // TODO: b/301206470 implement navigation for when there is an error to load screen
        }

        SinglePasswordScreenUiState.Completed -> {
            Log.d(TAG, "Received signal to finish the activity.")
            onCloseApp()
        }
    }
}

@Composable
fun SinglePasswordScreen(
    passwordUiModel: PasswordUiModel,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
) {
    SingleAccountScreen(
        headerContent = {
            SignInHeader(
                icon = null,
                title = stringResource(R.string.use_password_title),
            )
        },
        accountContent = {
            PasswordRow(
                email = passwordUiModel.email,
                modifier = Modifier.padding(top = 10.dp),
            )
        },
        columnState = columnState,
        modifier = modifier.padding(horizontal = 10.dp)
    ) {
        item {
        }
    }
}
