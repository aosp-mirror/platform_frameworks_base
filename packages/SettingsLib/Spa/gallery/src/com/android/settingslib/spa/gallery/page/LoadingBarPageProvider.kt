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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.CircularLoadingBar
import com.android.settingslib.spa.widget.ui.LinearLoadingBar

private const val TITLE = "Sample LoadingBar"

object LoadingBarPageProvider : SettingsPageProvider {
    override val name = "LoadingBar"

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = createSettingsPage())
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        var loading by remember { mutableStateOf(true) }
        RegularScaffold(title = getTitle(arguments)) {
            Button(
                onClick = { loading = !loading },
                modifier = Modifier.padding(start = 20.dp)
            ) {
                if (loading) {
                    Text(text = "Stop")
                } else {
                    Text(text = "Resume")
                }
            }
            Spacer(modifier = Modifier.height(SettingsDimension.itemPaddingVertical))
            LinearLoadingBar(isLoading = loading)
            Spacer(modifier = Modifier.height(SettingsDimension.itemPaddingVertical))
            CircularLoadingBar(isLoading = loading)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingBarPagePreview() {
    SettingsTheme {
        LoadingBarPageProvider.Page(null)
    }
}
