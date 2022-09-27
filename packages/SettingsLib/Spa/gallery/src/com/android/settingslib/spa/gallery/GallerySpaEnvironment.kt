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

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.gallery.button.ActionButtonPageProvider
import com.android.settingslib.spa.gallery.home.HomePageProvider
import com.android.settingslib.spa.gallery.page.ArgumentPageProvider
import com.android.settingslib.spa.gallery.page.FooterPageProvider
import com.android.settingslib.spa.gallery.page.IllustrationPageProvider
import com.android.settingslib.spa.gallery.page.SettingsPagerPageProvider
import com.android.settingslib.spa.gallery.page.SliderPageProvider
import com.android.settingslib.spa.gallery.preference.MainSwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.PreferenceMainPageProvider
import com.android.settingslib.spa.gallery.preference.PreferencePageProvider
import com.android.settingslib.spa.gallery.preference.SwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.preference.TwoTargetSwitchPreferencePageProvider
import com.android.settingslib.spa.gallery.ui.CategoryPageProvider
import com.android.settingslib.spa.gallery.ui.SpinnerPageProvider

/**
 * Enum to define all SPP name here.
 * Since the SPP name would be used in log, DO NOT change it once it is set. One can still change
 * the display name for better readability if necessary.
 */
enum class SettingsPageProviderEnum(val displayName: String) {
    HOME("home"),
    PREFERENCE("preference"),
    ARGUMENT("argument"),

    // Add your SPPs
}

fun createSettingsPage(
    SppName: SettingsPageProviderEnum,
    parameter: List<NamedNavArgument> = emptyList(),
    arguments: Bundle? = null
): SettingsPage {
    return SettingsPage.create(
        name = SppName.name,
        displayName = SppName.displayName,
        parameter = parameter,
        arguments = arguments,
    )
}

object GallerySpaEnvironment : SpaEnvironment() {
    override val pageProviderRepository = lazy {
        SettingsPageProviderRepository(
            allPageProviders = listOf(
                HomePageProvider,
                PreferenceMainPageProvider,
                PreferencePageProvider,
                SwitchPreferencePageProvider,
                MainSwitchPreferencePageProvider,
                TwoTargetSwitchPreferencePageProvider,
                ArgumentPageProvider,
                SliderPageProvider,
                SpinnerPageProvider,
                SettingsPagerPageProvider,
                FooterPageProvider,
                IllustrationPageProvider,
                CategoryPageProvider,
                ActionButtonPageProvider,
            ),
            rootPages = listOf(
                createSettingsPage(SettingsPageProviderEnum.HOME)
            )
        )
    }

    override val browseActivityClass = MainActivity::class.java

    override val entryProviderAuthorities = "com.android.spa.gallery.provider"
}
