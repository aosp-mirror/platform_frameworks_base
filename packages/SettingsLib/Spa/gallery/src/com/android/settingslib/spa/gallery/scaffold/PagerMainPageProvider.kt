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

package com.android.settingslib.spa.gallery.scaffold

import android.os.Bundle
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

object PagerMainPageProvider : SettingsPageProvider {
    override val name = "PagerMain"
    private val owner = createSettingsPage()
    private const val TITLE = "Category: Pager"

    override fun buildEntry(arguments: Bundle?) = listOf(
        NonScrollablePagerPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
        ScrollablePagerPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
    )

    fun buildInjectEntry() = SettingsEntryBuilder.createInject(owner = owner)
        .setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            })
        }

    override fun getTitle(arguments: Bundle?) = TITLE
}
