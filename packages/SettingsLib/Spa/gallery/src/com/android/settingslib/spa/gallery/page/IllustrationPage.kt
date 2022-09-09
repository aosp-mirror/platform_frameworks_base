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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.Illustration
import com.android.settingslib.spa.widget.IllustrationModel
import com.android.settingslib.spa.widget.ResourceType
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

object IllustrationPageProvider : SettingsPageProvider {
    override val name = "Illustration"

    @Composable
    override fun Page(arguments: Bundle?) {
        IllustrationPage()
    }

    @Composable
    fun EntryItem() {
        Preference(object : PreferenceModel {
            override val title = "Sample Illustration"
            override val onClick = navigator(name)
        })
    }
}

@Composable
private fun IllustrationPage() {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Preference(object : PreferenceModel {
            override val title = "Lottie Illustration"
        })

        Illustration(object : IllustrationModel {
            override val resId = R.raw.accessibility_shortcut_type_triple_tap
            override val resourceType = ResourceType.LOTTIE
        })

        Preference(object : PreferenceModel {
            override val title = "Image Illustration"
        })

        Illustration(object : IllustrationModel {
            override val resId = R.drawable.accessibility_captioning_banner
            override val resourceType = ResourceType.IMAGE
        })
    }
}

@Preview(showBackground = true)
@Composable
private fun IllustrationPagePreview() {
    SettingsTheme {
        IllustrationPage()
    }
}
