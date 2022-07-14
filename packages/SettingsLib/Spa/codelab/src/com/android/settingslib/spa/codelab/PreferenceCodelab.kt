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

package com.android.settingslib.spa.codelab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.toState
import com.android.settingslib.spa.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import kotlinx.coroutines.delay

@Composable
fun PreferenceCodelab() {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Preference(object : PreferenceModel {
            override val title = "Preference"
        })

        Preference(object : PreferenceModel {
            override val title = "Preference"
            override val summary = "With summary".toState()
        })

        Preference(object : PreferenceModel {
            override val title = "Preference"
            override val summary = produceState(initialValue = " ") {
                delay(1000L)
                value = "Async summary"
            }
        })

        var count by remember { mutableStateOf(0) }
        Preference(object : PreferenceModel {
            override val title = "Click me"
            override val summary = derivedStateOf { count.toString() }
            override val onClick: (() -> Unit) = { count++ }
        })

        var ticks by remember { mutableStateOf(0) }
        LaunchedEffect(ticks) {
            delay(1000L)
            ticks++
        }
        Preference(object : PreferenceModel {
            override val title = "Ticker"
            override val summary = derivedStateOf { ticks.toString() }
        })

        Preference(object : PreferenceModel {
            override val title = "Disabled"
            override val summary = "Disabled".toState()
            override val enabled = false.toState()
        })
    }
}

@Preview(showBackground = true)
@Composable
private fun PreferenceCodelabPreview() {
    SettingsTheme {
        PreferenceCodelab()
    }
}
