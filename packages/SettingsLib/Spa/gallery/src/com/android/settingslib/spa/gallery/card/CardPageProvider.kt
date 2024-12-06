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
import androidx.compose.material.icons.filled.Stars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.card.SuggestionCard
import com.android.settingslib.spa.widget.card.SuggestionCardModel
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object CardPageProvider : SettingsPageProvider {
    override val name = "Card"

    override fun getTitle(arguments: Bundle?) = TITLE

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            SuggestionCard()
            SuggestionCardWithLongTitle()
            SuggestionCardDismissible()
        }
    }

    @Composable
    private fun SuggestionCard() {
        SuggestionCard(
            SuggestionCardModel(
                title = "Suggestion card",
                description = "Suggestion card description",
                imageVector = Icons.Filled.Stars,
            )
        )
    }

    @Composable
    private fun SuggestionCardWithLongTitle() {
        SuggestionCard(
            SuggestionCardModel(
                title = "Top level suggestion card with a really, really long title",
                imageVector = Icons.Filled.Stars,
                onClick = {},
            )
        )
    }

    @Composable
    private fun SuggestionCardDismissible() {
        var isVisible by rememberSaveable { mutableStateOf(true) }
        SuggestionCard(
            SuggestionCardModel(
                title = "Suggestion card",
                description = "Suggestion card description",
                imageVector = Icons.Filled.Stars,
                onDismiss = { isVisible = false },
                isVisible = isVisible,
            )
        )
    }

    @Composable
    fun Entry() {
        Preference(
            object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            }
        )
    }

    private const val TITLE = "Sample Card"
}

@Preview
@Composable
private fun CardPagePreview() {
    SettingsTheme { CardPageProvider.Page(null) }
}
