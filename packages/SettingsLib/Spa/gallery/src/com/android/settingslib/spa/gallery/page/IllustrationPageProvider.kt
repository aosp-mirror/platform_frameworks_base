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
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.illustration.Illustration
import com.android.settingslib.spa.widget.illustration.IllustrationModel
import com.android.settingslib.spa.widget.illustration.ResourceType
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TITLE = "Sample Illustration"

object IllustrationPageProvider : SettingsPageProvider {
    override val name = "Illustration"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("Lottie Illustration", owner)
                .setUiLayoutFn {
                    Preference(object : PreferenceModel {
                        override val title = "Lottie Illustration"
                    })

                    Illustration(object : IllustrationModel {
                        override val resId = R.raw.accessibility_shortcut_type_triple_tap
                        override val resourceType = ResourceType.LOTTIE
                    })
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Image Illustration", owner)
                .setUiLayoutFn {
                    Preference(object : PreferenceModel {
                        override val title = "Image Illustration"
                    })

                    Illustration(object : IllustrationModel {
                        override val resId = R.drawable.accessibility_captioning_banner
                        override val resourceType = ResourceType.IMAGE
                    })
                }.build()
        )

        return entryList
    }

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
}

@Preview(showBackground = true)
@Composable
private fun IllustrationPagePreview() {
    SettingsTheme {
        IllustrationPageProvider.Page(null)
    }
}
