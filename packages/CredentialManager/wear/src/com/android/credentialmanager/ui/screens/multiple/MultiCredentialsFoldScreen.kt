/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.credentialmanager.ui.screens.multiple

import com.android.credentialmanager.ui.screens.UiState
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import com.android.credentialmanager.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.ui.components.DismissChip
import com.android.credentialmanager.ui.components.CredentialsScreenChip
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.components.SignInOptionsChip
import com.android.credentialmanager.ui.components.LockedProviderChip
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScalingLazyColumnState
import com.android.credentialmanager.model.CredentialType

/**
 * Screen that shows multiple credentials to select from.
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param screenIcon The view model corresponding to the home page.
 * @param columnState ScalingLazyColumn configuration to be be applied
 * @param modifier styling for composable
 * @param viewModel ViewModel that updates ui state for this screen
 * @param navController handles navigation events from this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFoldScreen(
    credentialSelectorUiState: CredentialSelectorUiState.Get.MultipleEntry,
    screenIcon: Drawable?,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
    viewModel: MultiCredentialsFoldViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        UiState.CredentialScreen -> {
            MultiCredentialsFoldScreen(
                state = credentialSelectorUiState,
                onSignInOptionsClicked = viewModel::onSignInOptionsClicked,
                onCredentialClicked = viewModel::onCredentialClicked,
                onCancelClicked = viewModel::onCancelClicked,
                screenIcon = screenIcon,
                columnState = columnState,
                modifier = modifier
            )
        }

        is UiState.CredentialSelected -> {
            val launcher = rememberLauncherForActivityResult(
                StartBalIntentSenderForResultContract()
            ) {
                viewModel.onInfoRetrieved(it.resultCode, null)
            }

            SideEffect {
                state.intentSenderRequest?.let {
                    launcher.launch(it)
                }
            }
        }

        UiState.Cancel -> {
            navController.popBackStack()
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun MultiCredentialsFoldScreen(
    state: CredentialSelectorUiState.Get.MultipleEntry,
    onSignInOptionsClicked: () -> Unit,
    onCredentialClicked: (entryInfo: CredentialEntryInfo) -> Unit,
    onCancelClicked: () -> Unit,
    screenIcon: Drawable?,
    columnState: ScalingLazyColumnState,
    modifier: Modifier,
) {
    ScalingLazyColumn(
        columnState = columnState,
        modifier = modifier.fillMaxSize(),
    ) {
        // flatten all credentials into one
        val credentials = state.accounts.flatMap { it.sortedCredentialEntryList }
        item {
            var title = stringResource(R.string.choose_sign_in_title)
            if (credentials.all{ it.credentialType == CredentialType.PASSKEY }) {
                title = stringResource(R.string.choose_passkey_title)
            } else if (credentials.all { it.credentialType == CredentialType.PASSWORD }) {
                title = stringResource(R.string.choose_password_title)
            }

            SignInHeader(
                icon = screenIcon,
                title = title,
                modifier = Modifier
                    .padding(top = 6.dp),
            )
        }

        credentials.forEach { credential: CredentialEntryInfo ->
                item {
                    CredentialsScreenChip(
                        label = credential.userName,
                        onClick = { onCredentialClicked(credential) },
                        secondaryLabel = credential.credentialTypeDisplayName,
                        icon = credential.icon,
                    )
                }
            }

        state.authenticationEntryList.forEach { authenticationEntryInfo ->
            item {
                LockedProviderChip(authenticationEntryInfo) {
                    // TODO(b/322797032) invoke LockedProviderScreen here using flow engine
                }
            }
        }

        item { SignInOptionsChip(onSignInOptionsClicked)}
        item { DismissChip(onCancelClicked) }
    }
}
