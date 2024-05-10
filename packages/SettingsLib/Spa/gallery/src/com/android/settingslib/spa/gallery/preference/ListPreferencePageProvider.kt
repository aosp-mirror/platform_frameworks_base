/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.ListPreference
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

private const val TITLE = "Sample ListPreference"

object ListPreferencePageProvider : SettingsPageProvider {
    override val name = "ListPreference"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?) = listOf(
        SettingsEntryBuilder.create("ListPreference", owner)
            .setUiLayoutFn {
                SampleListPreference()
            }.build(),
        SettingsEntryBuilder.create("ListPreference not changeable", owner)
            .setUiLayoutFn {
                SampleNotChangeableListPreference()
            }.build(),
    )

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    override fun getTitle(arguments: Bundle?) = TITLE
}

@Composable
private fun SampleListPreference() {
    val selectedId = rememberSaveable { mutableIntStateOf(1) }
    ListPreference(remember {
        object : ListPreferenceModel {
            override val title = "Preferred network type"
            override val options = listOf(
                ListPreferenceOption(id = 1, text = "5G (recommended)"),
                ListPreferenceOption(id = 2, text = "LTE"),
                ListPreferenceOption(id = 3, text = "3G"),
            )
            override val selectedId = selectedId
            override val onIdSelected: (id: Int) -> Unit = { selectedId.intValue = it }
        }
    })
}

@Composable
private fun SampleNotChangeableListPreference() {
    val selectedId = rememberSaveable { mutableIntStateOf(1) }
    val enableFlow = flow {
        var enabled = true
        while (true) {
            delay(3.seconds)
            enabled = !enabled
            emit(enabled)
        }
    }
    val enabled = enableFlow.collectAsStateWithLifecycle(initialValue = true)
    ListPreference(remember {
        object : ListPreferenceModel {
            override val title = "Preferred network type"
            override val enabled = { enabled.value }
            override val options = listOf(
                ListPreferenceOption(id = 1, text = "5G (recommended)"),
                ListPreferenceOption(id = 2, text = "LTE"),
                ListPreferenceOption(id = 3, text = "3G"),
            )
            override val selectedId = selectedId
            override val onIdSelected: (id: Int) -> Unit = { selectedId.intValue = it }
        }
    })
}

@Preview
@Composable
private fun ListPreferencePagePreview() {
    SettingsTheme {
        ListPreferencePageProvider.Page(null)
    }
}
