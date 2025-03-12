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
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.gallery.banner.BannerPageProvider
import com.android.settingslib.spa.gallery.button.ActionButtonPageProvider
import com.android.settingslib.spa.gallery.card.CardPageProvider
import com.android.settingslib.spa.gallery.chart.ChartPageProvider
import com.android.settingslib.spa.gallery.dialog.DialogMainPageProvider
import com.android.settingslib.spa.gallery.editor.EditorMainPageProvider
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
import com.android.settingslib.spa.widget.ui.Category

object HomePageProvider : SettingsPageProvider {
    override val name = SettingsPageProviderEnum.HOME.name
    override val displayName = SettingsPageProviderEnum.HOME.displayName

    override fun getTitle(arguments: Bundle?): String {
        return SpaEnvironmentFactory.instance.appContext.getString(R.string.app_name)
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val title = remember { getTitle(arguments) }
        HomeScaffold(title) {
            Category {
                PreferenceMainPageProvider.Entry()
            }
            Category {
                SearchScaffoldPageProvider.Entry()
                SuwScaffoldPageProvider.Entry()
                ArgumentPageProvider.EntryItem(stringParam = "foo", intParam = 0)
            }
            Category {
                SliderPageProvider.Entry()
                SpinnerPageProvider.Entry()
                PagerMainPageProvider.Entry()
                FooterPageProvider.Entry()
                IllustrationPageProvider.Entry()
                CategoryPageProvider.Entry()
                ActionButtonPageProvider.Entry()
                ProgressBarPageProvider.Entry()
                LoadingBarPageProvider.Entry()
                ChartPageProvider.Entry()
                DialogMainPageProvider.Entry()
                EditorMainPageProvider.Entry()
                BannerPageProvider.Entry()
                CardPageProvider.Entry()
                CopyablePageProvider.Entry()
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
