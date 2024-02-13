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

package com.android.credentialmanager.ui.screens.single.signInWithProvider

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.R
import com.android.credentialmanager.activity.StartBalIntentSenderForResultContract
import com.android.credentialmanager.ui.components.AccountRow
import com.android.credentialmanager.ui.components.ContinueChip
import com.android.credentialmanager.ui.components.DismissChip
import com.android.credentialmanager.ui.components.SignInHeader
import com.android.credentialmanager.ui.components.SignInOptionsChip
import com.android.credentialmanager.ui.screens.single.SingleAccountScreen
import com.android.credentialmanager.ui.screens.UiState
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnState

/**
 * Screen that shows sign in with provider credential.
 *
 * @param credentialSelectorUiState The app bar view model.
 * @param columnState ScalingLazyColumn configuration to be be applied to SingleAccountScreen
 * @param modifier styling for composable
 * @param viewModel ViewModel that updates ui state for this screen
 * @param navController handles navigation events from this screen
 */
@OptIn(ExperimentalHorologistApi::class)
@Composable
fun SignInWithProviderScreen(
    credentialSelectorUiState: CredentialSelectorUiState.Get.SingleEntry,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
    viewModel: SignInWithProviderViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    viewModel.initialize(credentialSelectorUiState.entry)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState) {
        UiState.CredentialScreen -> {
            SignInWithProviderScreen(
                credentialSelectorUiState.entry,
                columnState,
                modifier,
                viewModel
            )
        }

        is UiState.CredentialSelected -> {
            val launcher = rememberLauncherForActivityResult(
                StartBalIntentSenderForResultContract()
            ) {
                viewModel.onInfoRetrieved(it.resultCode, null)
            }

            SideEffect {
                (uiState as UiState.CredentialSelected).intentSenderRequest?.let {
                    launcher.launch(it)
                }
            }
        }

        UiState.Cancel -> {
            // TODO(b/322797032) add valid navigation path here for going back
            navController.popBackStack()
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun SignInWithProviderScreen(
    entry: CredentialEntryInfo,
    columnState: ScalingLazyColumnState,
    modifier: Modifier = Modifier,
    viewModel: SignInWithProviderViewModel,
) {
    SingleAccountScreen(
        headerContent = {
            SignInHeader(
                icon = entry.icon,
                title = stringResource(R.string.use_sign_in_with_provider_title,
                    entry.providerDisplayName),
            )
        },
        accountContent = {
            val displayName = entry.displayName
            if (displayName != null) {
                AccountRow(
                    primaryText = displayName,
                    secondaryText = entry.userName,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                AccountRow(
                    primaryText = entry.userName,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        columnState = columnState,
        modifier = modifier.padding(horizontal = 10.dp)
    ) {
       item {
           Column {
               ContinueChip(viewModel::onContinueClick)
               SignInOptionsChip(viewModel::onSignInOptionsClick)
               DismissChip(viewModel::onDismissClick)
           }
       }
    }
}