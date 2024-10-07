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

package com.android.settingslib.spa.gallery

import android.content.Context
import com.android.settingslib.spa.debug.DebugLogger
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.gallery.button.ActionButtonPageProvider
import com.android.settingslib.spa.gallery.banner.BannerPageProvider
import com.android.settingslib.spa.gallery.chart.ChartPageProvider
import com.android.settingslib.spa.gallery.dialog.DialogMainPageProvider
import com.android.settingslib.spa.gallery.dialog.NavDialogProvider
import com.android.settingslib.spa.gallery.editor.EditorMainPageProvider
import com.android.settingslib.spa.gallery.editor.SettingsDropdownBoxPageProvider
import com.android.settingslib.spa.gallery.editor.SettingsDropdownCheckBoxProvider
import com.android.settingslib.spa.gallery.home.HomePageProvider
import com.android.settingslib.spa.gallery.editor.SettingsOutlinedTextFieldPageProvider
import com.android.settingslib.spa.gallery.editor.SettingsTextFieldPasswordPageProvider
import com.android.settingslib.spa.gallery.page.ArgumentPageProvider
import com.android.settingslib.spa.gallery.page.FooterPageProvider
import com.android.settingslib.spa.gallery.page.IllustrationPageProvider
import com.android.settingslib.spa.gallery.page.LoadingBarPageProvider
import com.android.settingslib.spa.gallery.page.ProgressBarPageProvider
import com.android.settingslib.spa.gallery.scaffold.NonScrollablePagerPageProvider
import com.android.settingslib.spa.gallery.page.SliderPageProvider
import com.android.settingslib.spa.gallery.preference.IntroPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.ListPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.MainSwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.PreferenceMainPageProvider
import com.android.settingslib.spa.gallery.preference.PreferencePageProvider
import com.android.settingslib.spa.gallery.preference.SwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.TopIntroPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.TwoTargetSwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.ZeroStatePreferencePageProvider
import com.android.settingslib.spa.gallery.scaffold.PagerMainPageProvider
import com.android.settingslib.spa.gallery.scaffold.SearchScaffoldPageProvider
import com.android.settingslib.spa.gallery.scaffold.SuwScaffoldPageProvider
import com.android.settingslib.spa.gallery.ui.CategoryPageProvider
import com.android.settingslib.spa.gallery.ui.CopyablePageProvider
import com.android.settingslib.spa.gallery.scaffold.ScrollablePagerPageProvider
import com.android.settingslib.spa.gallery.ui.SpinnerPageProvider

/**
 * Enum to define all SPP name here.
 * Since the SPP name would be used in log, DO NOT change it once it is set. One can still change
 * the display name for better readability if necessary.
 */
enum class SettingsPageProviderEnum(val displayName: String) {
    HOME("home"),

    // Add your SPPs
}

class GallerySpaEnvironment(context: Context) : SpaEnvironment(context) {
    override val pageProviderRepository = lazy {
        SettingsPageProviderRepository(
            allPageProviders = listOf(
                HomePageProvider,
                PreferenceMainPageProvider,
                PreferencePageProvider,
                SwitchPreferencePageProvider,
                MainSwitchPreferencePageProvider,
                ListPreferencePageProvider,
                TwoTargetSwitchPreferencePageProvider,
                ZeroStatePreferencePageProvider,
                ArgumentPageProvider,
                SliderPageProvider,
                SpinnerPageProvider,
                PagerMainPageProvider,
                NonScrollablePagerPageProvider,
                ScrollablePagerPageProvider,
                FooterPageProvider,
                IllustrationPageProvider,
                CategoryPageProvider,
                ActionButtonPageProvider,
                ProgressBarPageProvider,
                LoadingBarPageProvider,
                ChartPageProvider,
                DialogMainPageProvider,
                NavDialogProvider,
                EditorMainPageProvider,
                SettingsOutlinedTextFieldPageProvider,
                SettingsDropdownBoxPageProvider,
                SettingsDropdownCheckBoxProvider,
                SettingsTextFieldPasswordPageProvider,
                SearchScaffoldPageProvider,
                SuwScaffoldPageProvider,
                BannerPageProvider,
                CopyablePageProvider,
                IntroPreferencePageProvider,
                TopIntroPreferencePageProvider,
            ),
            rootPages = listOf(
                HomePageProvider.createSettingsPage(),
            )
        )
    }

    override val logger = DebugLogger()

    override val browseActivityClass = GalleryMainActivity::class.java

    // For debugging
    override val searchProviderAuthorities = "com.android.spa.gallery.search.provider"
}
