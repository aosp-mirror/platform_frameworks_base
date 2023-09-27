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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.MaterialTheme
import com.android.credentialmanager.ui.WearApp
import com.android.credentialmanager.ui.screens.single.password.SinglePasswordScreen
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.belowTimeTextPreview
import kotlinx.coroutines.launch

class CredentialSelectorActivity : ComponentActivity() {

    private val viewModel: CredentialSelectorViewModel by viewModels {
        CredentialSelectorViewModel.Factory
    }

    @OptIn(ExperimentalHorologistApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        // TODO: b/301027810 due to this issue with compose in Main platform, we are implementing a
        // workaround. Once the issue is fixed, remove the "else" bracket and leave only the
        // contents of the "if" bracket.
        if (false) {
            setContent {
                MaterialTheme {
                    WearApp(
                        viewModel = viewModel,
                        onCloseApp = ::finish,
                    )
                }
            }
        } else {
            // TODO: b/301027810 Remove the content of this "else" bracket fully once issue is fixed
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.uiState.collect { uiState ->
                        when (uiState) {
                            CredentialSelectorUiState.Idle -> {
                                // Don't display anything, assuming that there should be minimal latency
                                // to parse the Credential Manager intent and define the state of the
                                // app. If latency is big, then a "loading" screen should be displayed
                                // to the user.
                            }

                            is CredentialSelectorUiState.Get -> {
                                setContent {
                                    MaterialTheme {
                                        SinglePasswordScreen(
                                            columnState = belowTimeTextPreview(),
                                            onCloseApp = ::finish,
                                        )
                                    }
                                }
                            }

                            else -> finish()
                        }
                    }
                }
            }
        }

        viewModel.onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val previousIntent = getIntent()
        setIntent(intent)

        viewModel.onNewIntent(intent, previousIntent)
    }
}
