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

package com.android.settingslib.spa.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.android.settingslib.spa.R
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.SESSION_BROWSE
import com.android.settingslib.spa.framework.util.SESSION_SEARCH
import com.android.settingslib.spa.framework.util.createIntent
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.HomeScaffold
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val TAG = "DebugActivity"
private const val ROUTE_ROOT = "root"
private const val ROUTE_All_PAGES = "pages"
private const val ROUTE_All_ENTRIES = "entries"
private const val ROUTE_PAGE = "page"
private const val ROUTE_ENTRY = "entry"
private const val PARAM_NAME_PAGE_ID = "pid"
private const val PARAM_NAME_ENTRY_ID = "eid"

/**
 * The Debug Activity to display all Spa Pages & Entries.
 * One can open the debug activity by:
 *   $ adb shell am start -n <Package>/com.android.settingslib.spa.debug.DebugActivity
 * For gallery, Package = com.android.settingslib.spa.gallery
 */
class DebugActivity : ComponentActivity() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpaLib)
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
        val navController = rememberNavController()
        CompositionLocalProvider(navController.localNavController()) {
            NavHost(navController, ROUTE_ROOT) {
                composable(route = ROUTE_ROOT) { RootPage() }
                composable(route = ROUTE_All_PAGES) { AllPages() }
                composable(route = ROUTE_All_ENTRIES) { AllEntries() }
                composable(
                    route = "$ROUTE_PAGE/{$PARAM_NAME_PAGE_ID}",
                    arguments = listOf(
                        navArgument(PARAM_NAME_PAGE_ID) { type = NavType.StringType },
                    )
                ) { navBackStackEntry -> OnePage(navBackStackEntry.arguments) }
                composable(
                    route = "$ROUTE_ENTRY/{$PARAM_NAME_ENTRY_ID}",
                    arguments = listOf(
                        navArgument(PARAM_NAME_ENTRY_ID) { type = NavType.StringType },
                    )
                ) { navBackStackEntry -> OneEntry(navBackStackEntry.arguments) }
            }
        }
    }

    @Composable
    fun RootPage() {
        val entryRepository by spaEnvironment.entryRepository
        val allPageWithEntry = remember { entryRepository.getAllPageWithEntry() }
        val allEntry = remember { entryRepository.getAllEntries() }
        HomeScaffold(title = "Settings Debug") {
            Preference(object : PreferenceModel {
                override val title = "List All Pages (${allPageWithEntry.size})"
                override val onClick = navigator(route = ROUTE_All_PAGES)
            })
            Preference(object : PreferenceModel {
                override val title = "List All Entries (${allEntry.size})"
                override val onClick = navigator(route = ROUTE_All_ENTRIES)
            })
        }
    }

    @Composable
    fun AllPages() {
        val entryRepository by spaEnvironment.entryRepository
        val allPageWithEntry = remember { entryRepository.getAllPageWithEntry() }
        RegularScaffold(title = "All Pages (${allPageWithEntry.size})") {
            for (pageWithEntry in allPageWithEntry) {
                val page = pageWithEntry.page
                Preference(object : PreferenceModel {
                    override val title = "${page.debugBrief()} (${pageWithEntry.entries.size})"
                    override val summary = { page.debugArguments() }
                    override val onClick = navigator(route = ROUTE_PAGE + "/${page.id}")
                })
            }
        }
    }

    @Composable
    fun AllEntries() {
        val entryRepository by spaEnvironment.entryRepository
        val allEntry = remember { entryRepository.getAllEntries() }
        RegularScaffold(title = "All Entries (${allEntry.size})") {
            EntryList(allEntry)
        }
    }


    @Composable
    fun OnePage(arguments: Bundle?) {
        val entryRepository by spaEnvironment.entryRepository
        val id = arguments!!.getString(PARAM_NAME_PAGE_ID, "")
        val pageWithEntry = entryRepository.getPageWithEntry(id)!!
        val page = pageWithEntry.page
        RegularScaffold(title = "Page - ${page.debugBrief()}") {
            Text(text = "id = ${page.id}")
            Text(text = page.debugArguments())
            Text(text = "enabled = ${page.isEnabled()}")
            Text(text = "Entry size: ${pageWithEntry.entries.size}")
            Preference(model = object : PreferenceModel {
                override val title = "open page"
                override val enabled = {
                    spaEnvironment.browseActivityClass != null && page.isBrowsable()
                }
                override val onClick = openPage(page)
            })
            EntryList(pageWithEntry.entries)
        }
    }

    @Composable
    fun OneEntry(arguments: Bundle?) {
        val entryRepository by spaEnvironment.entryRepository
        val id = arguments!!.getString(PARAM_NAME_ENTRY_ID, "")
        val entry = entryRepository.getEntry(id)!!
        val entryContent = remember { entry.debugContent(entryRepository) }
        RegularScaffold(title = "Entry - ${entry.debugBrief()}") {
            Preference(model = object : PreferenceModel {
                override val title = "open entry"
                override val enabled = {
                    spaEnvironment.browseActivityClass != null &&
                        entry.containerPage().isBrowsable()
                }
                override val onClick = openEntry(entry)
            })
            Text(text = entryContent)
        }
    }

    @Composable
    private fun EntryList(entries: Collection<SettingsEntry>) {
        for (entry in entries) {
            Preference(object : PreferenceModel {
                override val title = entry.debugBrief()
                override val summary = {
                    "${entry.fromPage?.displayName} -> ${entry.toPage?.displayName}"
                }
                override val onClick = navigator(route = ROUTE_ENTRY + "/${entry.id}")
            })
        }
    }

    @Composable
    private fun openPage(page: SettingsPage): (() -> Unit)? {
        val context = LocalContext.current
        val intent =
            page.createIntent(SESSION_BROWSE) ?: return null
        val route = page.buildRoute()
        return {
            spaEnvironment.logger.message(
                TAG, "OpenPage: $route", category = LogCategory.FRAMEWORK
            )
            context.startActivity(intent)
        }
    }

    @Composable
    private fun openEntry(entry: SettingsEntry): (() -> Unit)? {
        val context = LocalContext.current
        val intent = entry.createIntent(SESSION_SEARCH)
            ?: return null
        val route = entry.containerPage().buildRoute()
        return {
            spaEnvironment.logger.message(
                TAG, "OpenEntry: $route", category = LogCategory.FRAMEWORK
            )
            context.startActivity(intent)
        }
    }
}

/**
 * A blank activity without any page.
 */
class BlankActivity : ComponentActivity()
