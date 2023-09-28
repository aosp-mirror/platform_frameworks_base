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

@file:OptIn(ExperimentalHorologistApi::class)

package com.android.credentialmanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.CredentialSelectorViewModel
import com.android.credentialmanager.ui.screens.LoadingScreen
import com.android.credentialmanager.ui.screens.single.password.SinglePasswordScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.navscaffold.WearNavScaffold
import com.google.android.horologist.compose.navscaffold.composable
import com.google.android.horologist.compose.navscaffold.scrollable

@Composable
fun WearApp(
    viewModel: CredentialSelectorViewModel,
    onCloseApp: () -> Unit,
) {
    val navController = rememberSwipeDismissableNavController()
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val navHostState =
        rememberSwipeDismissableNavHostState(swipeToDismissBoxState = swipeToDismissBoxState)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WearNavScaffold(
        startDestination = Screen.Loading.route,
        navController = navController,
        state = navHostState,
    ) {
        composable(Screen.Loading.route) {
            LoadingScreen()
        }

        scrollable(Screen.SinglePasswordScreen.route) {
            SinglePasswordScreen(
                columnState = it.columnState,
                onCloseApp = onCloseApp,
            )
        }
    }

    when (val state = uiState) {
        CredentialSelectorUiState.Idle -> {
            if (navController.currentDestination?.route != Screen.Loading.route) {
                navController.navigateToLoading()
            }
        }

        is CredentialSelectorUiState.Get -> {
            handleGetNavigation(
                navController = navController,
                state = state,
                onCloseApp = onCloseApp,
            )
        }

        CredentialSelectorUiState.Create -> {
            // TODO: b/301206624 - Implement create flow
            onCloseApp()
        }

        is CredentialSelectorUiState.Cancel -> {
            // TODO: b/300422310 - Implement cancel with message flow
            onCloseApp()
        }

        CredentialSelectorUiState.Close -> {
            onCloseApp()
        }
    }
}

private fun handleGetNavigation(
    navController: NavController,
    state: CredentialSelectorUiState.Get,
    onCloseApp: () -> Unit,
) {
    when (state) {
        is CredentialSelectorUiState.Get.SingleProviderSinglePassword -> {
            navController.navigateToSinglePasswordScreen()
        }

        else -> {
            // TODO: b/301206470 - Implement other get flows
            onCloseApp()
        }
    }
}
