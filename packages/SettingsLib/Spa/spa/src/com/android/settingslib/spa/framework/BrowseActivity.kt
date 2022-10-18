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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapperImpl
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.navRoute

private const val TAG = "BrowseActivity"
private const val NULL_PAGE_NAME = "NULL"

/**
 * The Activity to render ALL SPA pages, and handles jumps between SPA pages.
 *
 * One can open any SPA page by:
 * ```
 * $ adb shell am start -n <BrowseActivityComponent> -e spaActivityDestination <SpaPageRoute>
 * ```
 * - For Gallery, BrowseActivityComponent = com.android.settingslib.spa.gallery/.GalleryMainActivity
 * - For Settings, BrowseActivityComponent = com.android.settings/.spa.SpaActivity
 *
 * Some examples:
 * ```
 * $ adb shell am start -n <BrowseActivityComponent> -e spaActivityDestination HOME
 * $ adb shell am start -n <BrowseActivityComponent> -e spaActivityDestination ARGUMENT/bar/5
 * ```
 */
open class BrowseActivity : ComponentActivity() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpaLib_DayNight)
        super.onCreate(savedInstanceState)
        spaEnvironment.logger.message(TAG, "onCreate", category = LogCategory.FRAMEWORK)

        setContent {
            SettingsTheme {
                MainContent()
            }
        }
    }

    @Composable
    private fun MainContent() {
        val sppRepository by spaEnvironment.pageProviderRepository
        val navController = rememberNavController()
        CompositionLocalProvider(navController.localNavController()) {
            NavHost(navController, NULL_PAGE_NAME) {
                composable(NULL_PAGE_NAME) {}
                for (spp in sppRepository.getAllProviders()) {
                    composable(
                        route = spp.name + spp.parameter.navRoute(),
                        arguments = spp.parameter,
                    ) { navBackStackEntry ->
                        val lifecycleOwner = LocalLifecycleOwner.current
                        val spaLogger = spaEnvironment.logger
                        val sp = spp.createSettingsPage(arguments = navBackStackEntry.arguments)

                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_START) {
                                    spaLogger.event(
                                        sp.id,
                                        "enter page ${sp.formatDisplayTitle()}",
                                        category = LogCategory.FRAMEWORK
                                    )
                                } else if (event == Lifecycle.Event.ON_STOP) {
                                    spaLogger.event(
                                        sp.id,
                                        "leave page ${sp.formatDisplayTitle()}",
                                        category = LogCategory.FRAMEWORK
                                    )
                                }
                            }

                            // Add the observer to the lifecycle
                            lifecycleOwner.lifecycle.addObserver(observer)

                            // When the effect leaves the Composition, remove the observer
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }

                        spp.Page(navBackStackEntry.arguments)
                    }
                }
            }
            InitialDestinationNavigator()
        }
    }

    @Composable
    private fun InitialDestinationNavigator() {
        val sppRepository by spaEnvironment.pageProviderRepository
        val destinationNavigated = rememberSaveable { mutableStateOf(false) }
        if (destinationNavigated.value) return
        destinationNavigated.value = true
        val controller = LocalNavController.current as NavControllerWrapperImpl
        LaunchedEffect(Unit) {
            val destination =
                intent?.getStringExtra(KEY_DESTINATION) ?: sppRepository.getDefaultStartPage()
            val highlightEntryId = intent?.getStringExtra(KEY_HIGHLIGHT_ENTRY)
            if (destination.isNotEmpty()) {
                controller.highlightId = highlightEntryId
                val navController = controller.navController
                navController.navigate(destination) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        inclusive = true
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_DESTINATION = "spaActivityDestination"
        const val KEY_HIGHLIGHT_ENTRY = "highlightEntry"
    }
}
