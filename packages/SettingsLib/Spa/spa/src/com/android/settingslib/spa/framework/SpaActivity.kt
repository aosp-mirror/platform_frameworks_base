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

package com.android.settingslib.spa.framework

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.api.SettingsPageRepository
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme

open class SpaActivity(
    private val settingsPageRepository: SettingsPageRepository,
) : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpaLib_DayNight)
        super.onCreate(savedInstanceState)
        setContent {
            MainContent()
        }
    }

    @Composable
    private fun MainContent() {
        SettingsTheme {
            val navController = rememberNavController()
            CompositionLocalProvider(navController.localNavController()) {
                NavHost(navController, settingsPageRepository.startDestination) {
                    for (page in settingsPageRepository.allPages) {
                        composable(
                            route = page.route,
                            arguments = page.arguments,
                        ) { navBackStackEntry ->
                            page.Page(navBackStackEntry.arguments)
                        }
                    }
                }
            }
        }
    }

    private val SettingsPageProvider.route: String
        get() = name + arguments.joinToString("") { argument -> "/{${argument.name}}" }
}
