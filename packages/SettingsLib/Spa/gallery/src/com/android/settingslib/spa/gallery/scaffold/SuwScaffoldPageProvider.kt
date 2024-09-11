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

package com.android.settingslib.spa.gallery.scaffold

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.illustration.Illustration
import com.android.settingslib.spa.widget.illustration.IllustrationModel
import com.android.settingslib.spa.widget.illustration.ResourceType
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.BottomAppBarButton
import com.android.settingslib.spa.widget.scaffold.SuwScaffold
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.Spinner
import com.android.settingslib.spa.widget.ui.SpinnerOption

private const val TITLE = "Sample SuwScaffold"

object SuwScaffoldPageProvider : SettingsPageProvider {
    override val name = "SuwScaffold"

    private val owner = createSettingsPage()

    fun buildInjectEntry() = SettingsEntryBuilder.createInject(owner = owner)
        .setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            })
        }

    @Composable
    override fun Page(arguments: Bundle?) {
        Page()
    }
}

@Composable
private fun Page() {
    SuwScaffold(
        imageVector = Icons.Outlined.SignalCellularAlt,
        title = "Connect to mobile network",
        actionButton = BottomAppBarButton("Next") {},
        dismissButton = BottomAppBarButton("Cancel") {},
    ) {
        var selectedId by rememberSaveable { mutableIntStateOf(1) }
        Spinner(
            options = (1..3).map { SpinnerOption(id = it, text = "Option $it") },
            selectedId = selectedId,
            setId = { selectedId = it },
        )
        Column(Modifier.padding(SettingsDimension.itemPadding)) {
            SettingsBody("To add another SIM, download a new eSIM.")
        }
        Illustration(object : IllustrationModel {
            override val resId = R.drawable.accessibility_captioning_banner
            override val resourceType = ResourceType.IMAGE
        })
    }
}
