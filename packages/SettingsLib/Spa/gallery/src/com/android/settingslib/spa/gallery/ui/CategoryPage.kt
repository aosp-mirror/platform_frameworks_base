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

package com.android.settingslib.spa.gallery.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.CategoryTitle

private const val TITLE = "Sample Category"

object CategoryPageProvider : SettingsPageProvider {
    override val name = "Spinner"

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = SettingsPage.create(name))
            .setIsAllowSearch(true)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        CategoryPage()
    }
}

@Composable
private fun CategoryPage() {
    RegularScaffold(title = TITLE) {
        CategoryTitle("Category A")
        Preference(remember {
            object : PreferenceModel {
                override val title = "Preference 1"
                override val summary = stateOf("Summary 1")
            }
        })
        Preference(remember {
            object : PreferenceModel {
                override val title = "Preference 2"
                override val summary = stateOf("Summary 2")
            }
        })
        Category("Category B") {
            Preference(remember {
                object : PreferenceModel {
                    override val title = "Preference 3"
                    override val summary = stateOf("Summary 3")
                }
            })
            Preference(remember {
                object : PreferenceModel {
                    override val title = "Preference 4"
                    override val summary = stateOf("Summary 4")
                }
            })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpinnerPagePreview() {
    SettingsTheme {
        SpinnerPageProvider.Page(null)
    }
}
