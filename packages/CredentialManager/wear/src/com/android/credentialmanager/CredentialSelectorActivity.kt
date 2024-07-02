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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.android.credentialmanager.ui.theme.WearCredentialSelectorTheme
import com.android.credentialmanager.ui.WearApp
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint(ComponentActivity::class)
class CredentialSelectorActivity : Hilt_CredentialSelectorActivity() {

    private val viewModel: CredentialSelectorViewModel by viewModels()

    @OptIn(ExperimentalHorologistApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate, intent: $intent")
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            WearCredentialSelectorTheme {
                WearApp(
                    flowEngine = viewModel,
                    onCloseApp = { finish() },
                )
            }
        }
        viewModel.updateRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent, intent: $intent")
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.updateRequest(intent)
    }
}
