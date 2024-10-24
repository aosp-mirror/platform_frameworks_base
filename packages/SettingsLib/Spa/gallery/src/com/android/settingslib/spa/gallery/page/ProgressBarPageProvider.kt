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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.ProgressBarPreference
import com.android.settingslib.spa.widget.preference.ProgressBarPreferenceModel
import com.android.settingslib.spa.widget.preference.ProgressBarWithDataPreference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.CircularProgressBar
import kotlinx.coroutines.delay

private const val TITLE = "Sample ProgressBar"

object ProgressBarPageProvider : SettingsPageProvider {
    override val name = "ProgressBar"

    @Composable
    fun Entry() {
        Preference(object : PreferenceModel {
            override val title = TITLE
            override val onClick = navigator(name)
        })
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = getTitle(arguments)) {
            // Auto update the progress and finally jump tp 0.4f.
            var progress by remember { mutableStateOf(0f) }
            LaunchedEffect(Unit) {
                while (progress < 1f) {
                    delay(100)
                    progress += 0.01f
                }
                delay(500)
                progress = 0.4f
            }

            LargeProgressBar(progress)
            SimpleProgressBar()
            ProgressBarWithData()
            CircularProgressBar(progress = progress, radius = 160f)
        }
    }
}

@Composable
private fun LargeProgressBar(progress: Float) {
    ProgressBarPreference(object : ProgressBarPreferenceModel {
        override val title = "Large Progress Bar"
        override val progress = progress
        override val height = 20f
    })
}

@Composable
private fun SimpleProgressBar() {
    ProgressBarPreference(object : ProgressBarPreferenceModel {
        override val title = "Simple Progress Bar"
        override val progress = 0.2f
        override val icon = Icons.Outlined.SystemUpdate
    })
}

@Composable
private fun ProgressBarWithData() {
    ProgressBarWithDataPreference(model = object : ProgressBarPreferenceModel {
        override val title = "Progress Bar with Data"
        override val progress = 0.2f
        override val icon = Icons.Outlined.Delete
    }, data = "25G")
}

@Preview(showBackground = true)
@Composable
private fun ProgressBarPagePreview() {
    SettingsTheme {
        ProgressBarPageProvider.Page(null)
    }
}
