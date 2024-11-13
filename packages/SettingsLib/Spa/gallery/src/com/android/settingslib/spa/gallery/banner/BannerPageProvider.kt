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

package com.android.settingslib.spa.gallery.banner

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.banner.BannerButton
import com.android.settingslib.spa.widget.banner.BannerModel
import com.android.settingslib.spa.widget.banner.SettingsBanner
import com.android.settingslib.spa.widget.banner.SettingsBannerContent
import com.android.settingslib.spa.widget.banner.SettingsCollapsibleBanner
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object BannerPageProvider : SettingsPageProvider {
    override val name = "Banner"

    override fun getTitle(arguments: Bundle?) = TITLE

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            SettingsBannerWithIcon()
            SettingsBannerWithoutIcon()
            SampleSettingsCollapsibleBanner()
            SampleSettingsBannerContent()
        }
    }

    @Composable
    private fun SettingsBannerWithIcon() {
        SettingsBanner(
            BannerModel(
                title = stringResource(R.string.sample_title),
                text = stringResource(R.string.sample_text),
                imageVector = Icons.Outlined.WarningAmber,
                buttons = listOf(
                    BannerButton(text = "Action") {},
                ),
                tintColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        )
    }

    @Composable
    private fun SettingsBannerWithoutIcon() {
        val sampleTitle = stringResource(R.string.sample_title)
        var title by remember { mutableStateOf(sampleTitle) }
        SettingsBanner(
            BannerModel(
                title = title,
                text = stringResource(R.string.sample_text),
            ) { title = "Clicked" }
        )
    }

    @Composable
    fun SampleSettingsCollapsibleBanner() {
        val context = LocalContext.current
        var isVisible0 by rememberSaveable { mutableStateOf(true) }
        var isVisible1 by rememberSaveable { mutableStateOf(true) }
        val banners = remember {
            mutableStateListOf(
                BannerModel(
                    title = context.getString(R.string.sample_title),
                    text = context.getString(R.string.sample_text),
                    imageVector = Icons.Outlined.PowerOff,
                    isVisible = { isVisible0 },
                    onDismiss = { isVisible0 = false },
                    buttons = listOf(
                        BannerButton(text = "Override") {},
                        BannerButton(text = "Learn more") {},
                    ),
                ),
                BannerModel(
                    title = context.getString(R.string.sample_title),
                    text = context.getString(R.string.sample_text),
                    imageVector = Icons.Outlined.Shield,
                    isVisible = { isVisible1 },
                    onDismiss = { isVisible1 = false },
                    buttons = listOf(
                        BannerButton(text = "Action") {},
                    ),
                )
            )
        }
        SettingsCollapsibleBanner(
            title = "More alerts",
            imageVector = Icons.Outlined.Error,
            models = banners.toList()
        )
    }

    @Composable
    fun SampleSettingsBannerContent() {
        SettingsBanner {
            SettingsBannerContent {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(SettingsDimension.dialogItemPadding),
                ) {
                    Text(text = "Abc")
                }
            }
            SettingsBannerContent {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(SettingsDimension.dialogItemPadding),
                ) {
                    Text(text = "123")
                }
            }
        }
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

    private const val TITLE = "Sample Banner"
}

@Preview
@Composable
private fun BannerPagePreview() {
    SettingsTheme {
        BannerPageProvider.Page(null)
    }
}
