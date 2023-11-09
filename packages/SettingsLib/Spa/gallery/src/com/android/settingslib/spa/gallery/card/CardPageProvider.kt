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

package com.android.settingslib.spa.gallery.card

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.card.CardButton
import com.android.settingslib.spa.widget.card.CardModel
import com.android.settingslib.spa.widget.card.SettingsCard
import com.android.settingslib.spa.widget.card.SettingsCollapsibleCard
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object CardPageProvider : SettingsPageProvider {
    override val name = "CardPage"

    override fun getTitle(arguments: Bundle?) = TITLE

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            SettingsCardWithIcon()
            SettingsCardWithoutIcon()
            SampleSettingsCollapsibleCard()
        }
    }

    @Composable
    private fun SettingsCardWithIcon() {
        SettingsCard(
            CardModel(
                title = stringResource(R.string.sample_title),
                text = stringResource(R.string.sample_text),
                imageVector = Icons.Outlined.WarningAmber,
                buttons = listOf(
                    CardButton(text = "Action") {},
                    CardButton(text = "Action", isMain = true) {},
                )
            )
        )
    }

    @Composable
    private fun SettingsCardWithoutIcon() {
        SettingsCard(
            CardModel(
                title = stringResource(R.string.sample_title),
                text = stringResource(R.string.sample_text),
                buttons = listOf(
                    CardButton(text = "Action") {},
                ),
            )
        )
    }

    @Composable
    fun SampleSettingsCollapsibleCard() {
        SettingsCollapsibleCard(
            title = "More alerts",
            imageVector = Icons.Outlined.Error,
            models = listOf(
                CardModel(
                    title = stringResource(R.string.sample_title),
                    text = stringResource(R.string.sample_text),
                    imageVector = Icons.Outlined.PowerOff,
                    buttons = listOf(
                        CardButton(text = "Action") {},
                    )
                ),
                CardModel(
                    title = stringResource(R.string.sample_title),
                    text = stringResource(R.string.sample_text),
                    imageVector = Icons.Outlined.Shield,
                    buttons = listOf(
                        CardButton(text = "Action") {},
                        CardButton(text = "Main action", isMain = true) {},
                    )
                )
            )
        )
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = createSettingsPage())
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    private const val TITLE = "Sample Card"
}

@Preview
@Composable
private fun CardPagePreview() {
    SettingsTheme {
        CardPageProvider.Page(null)
    }
}
