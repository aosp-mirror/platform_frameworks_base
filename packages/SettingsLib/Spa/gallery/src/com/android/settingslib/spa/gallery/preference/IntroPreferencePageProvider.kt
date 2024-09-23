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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.IntroPreference
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TITLE = "Sample IntroPreference"

object IntroPreferencePageProvider : SettingsPageProvider {
    override val name = "IntroPreference"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("IntroPreference", owner)
                .setUiLayoutFn { SampleIntroPreference() }
                .build()
        )

        return entryList
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner).setUiLayoutFn {
            Preference(
                object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                }
            )
        }
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }
}

@Composable
private fun SampleIntroPreference() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IntroPreference(
            title = "Preferred network type",
            descriptions = listOf("Description"),
            imageVector = Icons.Outlined.AirplanemodeActive,
        )
    }
}
