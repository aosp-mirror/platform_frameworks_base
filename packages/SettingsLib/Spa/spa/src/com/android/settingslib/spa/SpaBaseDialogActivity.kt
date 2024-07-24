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

package com.android.settingslib.spa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.theme.SettingsTheme

abstract class SpaBaseDialogActivity : ComponentActivity() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spaEnvironment.logger.message(TAG, "onCreate", category = LogCategory.FRAMEWORK)
        setContent {
            SettingsTheme {
                Content()
            }
        }
    }

    @Composable
    abstract fun Content()

    companion object {
        private const val TAG = "SpaBaseDialogActivity"
    }
}
