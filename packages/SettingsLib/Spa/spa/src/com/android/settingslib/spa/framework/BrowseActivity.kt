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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.NullPageProvider
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SettingsPageProvider.NavType
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapperImpl
import com.android.settingslib.spa.framework.compose.animatedComposable
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.PageLogger
import com.android.settingslib.spa.framework.util.getDestination
import com.android.settingslib.spa.framework.util.getEntryId
import com.android.settingslib.spa.framework.util.getSessionName
import com.android.settingslib.spa.framework.util.navRoute

private const val TAG = "BrowseActivity"

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
        setTheme(R.style.Theme_SpaLib)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        spaEnvironment.logger.message(TAG, "onCreate", category = LogCategory.FRAMEWORK)

        setContent {
            SettingsTheme {
                val sppRepository by spaEnvironment.pageProviderRepository
                BrowseContent(
                    sppRepository = sppRepository,
                    isPageEnabled = ::isPageEnabled,
                    initialIntent = intent,
                )
            }
        }
    }

    open fun isPageEnabled(page: SettingsPage) = page.isEnabled()
}

@VisibleForTesting
@Composable
internal fun BrowseContent(
    sppRepository: SettingsPageProviderRepository,
    isPageEnabled: (SettingsPage) -> Boolean,
    initialIntent: Intent?,
) {
    val navController = rememberNavController()
    CompositionLocalProvider(navController.localNavController()) {
        val controller = LocalNavController.current as NavControllerWrapperImpl
        controller.NavContent(sppRepository.getAllProviders()) { page ->
            if (remember { isPageEnabled(page) }) {
                LaunchedEffect(Unit) {
                    Log.d(TAG, "Launching page ${page.sppName}")
                }
                page.PageLogger()
                page.UiLayout()
            } else {
                LaunchedEffect(Unit) {
                    controller.navigateBack()
                }
            }
        }
        controller.InitialDestination(initialIntent, sppRepository.getDefaultStartPage())
    }
}

@Composable
private fun NavControllerWrapperImpl.NavContent(
    allProvider: Collection<SettingsPageProvider>,
    content: @Composable (SettingsPage) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = NullPageProvider.name,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(NullPageProvider.name) {}
        for (spp in allProvider) {
            destination(spp) { navBackStackEntry ->
                val page = remember { spp.createSettingsPage(navBackStackEntry.arguments) }
                content(page)
            }
        }
    }
}

private fun NavGraphBuilder.destination(
    spp: SettingsPageProvider,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    val route = spp.name + spp.parameter.navRoute()
    when (spp.navType) {
        NavType.Page -> animatedComposable(route, spp.parameter) { content(it) }
        NavType.Dialog -> dialog(route, spp.parameter) { content(it) }
    }
}

@Composable
private fun NavControllerWrapperImpl.InitialDestination(
    initialIntent: Intent?,
    defaultDestination: String
) {
    val destinationNavigated = rememberSaveable { mutableStateOf(false) }
    if (destinationNavigated.value) return
    destinationNavigated.value = true

    val initialDestination = initialIntent?.getDestination() ?: defaultDestination
    if (initialDestination.isEmpty()) return
    val initialEntryId = initialIntent?.getEntryId()
    val sessionSourceName = initialIntent?.getSessionName()

    LaunchedEffect(Unit) {
        highlightId = initialEntryId
        sessionName = sessionSourceName
        navController.navigate(initialDestination) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = true
            }
        }
    }
}
