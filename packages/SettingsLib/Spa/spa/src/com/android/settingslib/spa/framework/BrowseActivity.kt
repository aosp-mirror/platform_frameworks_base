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
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.common.ROOT_PAGE_NAME
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.navRoute

open class BrowseActivity(
    private val sppRepository: SettingsPageProviderRepository,
) : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpaLib_DayNight)
        super.onCreate(savedInstanceState)

        setContent {
            SettingsTheme {
                MainContent()
            }
        }
    }

    @Composable
    private fun MainContent() {
        val destination =
            intent?.getStringExtra(KEY_DESTINATION) ?: sppRepository.getDefaultStartPageName()

        val navController = rememberNavController()
        CompositionLocalProvider(navController.localNavController()) {
            NavHost(navController, ROOT_PAGE_NAME) {
                composable(ROOT_PAGE_NAME) {}
                for (page in sppRepository.getAllProviders()) {
                    composable(
                        route = page.name + page.parameter.navRoute() +
                            "?$HIGHLIGHT_ENTRY_PARAM_NAME={$HIGHLIGHT_ENTRY_PARAM_NAME}",
                        arguments = page.parameter + listOf(
                            // add optional parameters
                            navArgument(HIGHLIGHT_ENTRY_PARAM_NAME) { defaultValue = "null" }
                        ),
                    ) { navBackStackEntry ->
                        page.Page(navBackStackEntry.arguments)
                    }
                }
            }

            if (destination.isNotEmpty()) {
                LaunchedEffect(Unit) {
                    navController.navigate(destination) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            inclusive = true
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_DESTINATION = "spa:SpaActivity:destination"
        const val HIGHLIGHT_ENTRY_PARAM_NAME = "highlightEntry"
    }
}
