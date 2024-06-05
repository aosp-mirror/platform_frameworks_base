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

package com.android.settingslib.spa.gallery.home

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.gallery.button.ActionButtonPageProvider
import com.android.settingslib.spa.gallery.card.CardPageProvider
import com.android.settingslib.spa.gallery.chart.ChartPageProvider
import com.android.settingslib.spa.gallery.dialog.DialogMainPageProvider
import com.android.settingslib.spa.gallery.editor.EditorMainPageProvider
import com.android.settingslib.spa.gallery.itemList.OperateListPageProvider
import com.android.settingslib.spa.gallery.page.ArgumentPageModel
import com.android.settingslib.spa.gallery.page.ArgumentPageProvider
import com.android.settingslib.spa.gallery.page.FooterPageProvider
import com.android.settingslib.spa.gallery.page.IllustrationPageProvider
import com.android.settingslib.spa.gallery.page.LoadingBarPageProvider
import com.android.settingslib.spa.gallery.page.ProgressBarPageProvider
import com.android.settingslib.spa.gallery.page.SliderPageProvider
import com.android.settingslib.spa.gallery.preference.PreferenceMainPageProvider
import com.android.settingslib.spa.gallery.scaffold.PagerMainPageProvider
import com.android.settingslib.spa.gallery.scaffold.SearchScaffoldPageProvider
import com.android.settingslib.spa.gallery.scaffold.SuwScaffoldPageProvider
import com.android.settingslib.spa.gallery.ui.CategoryPageProvider
import com.android.settingslib.spa.gallery.ui.CopyablePageProvider
import com.android.settingslib.spa.gallery.ui.SpinnerPageProvider
import com.android.settingslib.spa.widget.scaffold.HomeScaffold

object HomePageProvider : SettingsPageProvider {
    override val name = SettingsPageProviderEnum.HOME.name
    override val displayName = SettingsPageProviderEnum.HOME.displayName
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        return listOf(
            PreferenceMainPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            OperateListPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ArgumentPageProvider.buildInjectEntry("foo")!!.setLink(fromPage = owner).build(),
            SearchScaffoldPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SuwScaffoldPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SliderPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SpinnerPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            PagerMainPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            FooterPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            IllustrationPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            CategoryPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ActionButtonPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ProgressBarPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            LoadingBarPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ChartPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            DialogMainPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            EditorMainPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            CardPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            CopyablePageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
        )
    }

    override fun getTitle(arguments: Bundle?): String {
        return SpaEnvironmentFactory.instance.appContext.getString(R.string.app_name)
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val title = remember { getTitle(arguments) }
        val entries = remember { buildEntry(arguments) }
        HomeScaffold(title) {
            for (entry in entries) {
                if (entry.owner.isCreateBy(SettingsPageProviderEnum.ARGUMENT.name)) {
                    entry.UiLayout(ArgumentPageModel.buildArgument(intParam = 0))
                } else {
                    entry.UiLayout()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SpaEnvironmentFactory.resetForPreview()
    SettingsTheme {
        HomePageProvider.Page(null)
    }
}
