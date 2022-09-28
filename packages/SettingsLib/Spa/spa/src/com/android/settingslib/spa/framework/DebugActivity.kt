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
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.android.settingslib.spa.framework.BrowseActivity.Companion.KEY_DESTINATION
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.HomeScaffold
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

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
 *   $ adb shell am start -n <Activity>
 * For gallery, Activity = com.android.settingslib.spa.gallery/.GalleryDebugActivity
 * For SettingsGoogle, Activity = com.android.settings/.spa.SpaDebugActivity
 */
open class DebugActivity(private val spaEnvironment: SpaEnvironment) : ComponentActivity() {
    private val entryRepository by spaEnvironment.entryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpaLib_DayNight)
        super.onCreate(savedInstanceState)
        displayDebugMessage()

        setContent {
            SettingsTheme {
                MainContent()
            }
        }
    }

    private fun displayDebugMessage() {
        val entryProviderAuthorities = spaEnvironment.entryProviderAuthorities ?: return

        try {
            val query = EntryProvider.QueryEnum.PAGE_INFO_QUERY
            contentResolver.query(
                Uri.parse("content://$entryProviderAuthorities/${query.queryPath}"),
                null, null, null
            ).use { cursor ->
                while (cursor != null && cursor.moveToNext()) {
                    val route = cursor.getString(query, EntryProvider.ColumnEnum.PAGE_ROUTE)
                    val entryCount = cursor.getInt(query, EntryProvider.ColumnEnum.PAGE_ENTRY_COUNT)
                    val hasRuntimeParam =
                        cursor.getBoolean(query, EntryProvider.ColumnEnum.HAS_RUNTIME_PARAM)
                    Log.d(
                        "DEBUG ACTIVITY", "Page Info: $route ($entryCount) " +
                            (if (hasRuntimeParam) "with" else "no") + "-runtime-params"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DEBUG ACTIVITY", "Provider querying exception:", e)
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
        val allPageWithEntry = remember { entryRepository.getAllPageWithEntry() }
        RegularScaffold(title = "All Pages (${allPageWithEntry.size})") {
            for (pageWithEntry in allPageWithEntry) {
                Preference(object : PreferenceModel {
                    override val title =
                        "${pageWithEntry.page.displayName} (${pageWithEntry.entries.size})"
                    override val summary = pageWithEntry.page.formatArguments().toState()
                    override val onClick =
                        navigator(route = ROUTE_PAGE + "/${pageWithEntry.page.id}")
                })
            }
        }
    }

    @Composable
    fun AllEntries() {
        val allEntry = remember { entryRepository.getAllEntries() }
        RegularScaffold(title = "All Entries (${allEntry.size})") {
            EntryList(allEntry)
        }
    }

    @Composable
    fun OnePage(arguments: Bundle?) {
        val id = arguments!!.getString(PARAM_NAME_PAGE_ID, "")
        val pageWithEntry = entryRepository.getPageWithEntry(id)!!
        RegularScaffold(title = "Page - ${pageWithEntry.page.displayName}") {
            Text(text = "id = ${pageWithEntry.page.id}")
            Text(text = pageWithEntry.page.formatArguments())
            Text(text = "Entry size: ${pageWithEntry.entries.size}")
            Preference(model = object : PreferenceModel {
                override val title = "open page"
                override val enabled = (!pageWithEntry.page.hasRuntimeParam()).toState()
                override val onClick = openPage(pageWithEntry.page)
            })
            EntryList(pageWithEntry.entries)
        }
    }

    @Composable
    fun OneEntry(arguments: Bundle?) {
        val id = arguments!!.getString(PARAM_NAME_ENTRY_ID, "")
        val entry = entryRepository.getEntry(id)!!
        val entryContent = remember { entry.formatContent() }
        RegularScaffold(title = "Entry - ${entry.displayTitle()}") {
            Preference(model = object : PreferenceModel {
                override val title = "open entry"
                override val enabled = (!entry.hasRuntimeParam()).toState()
                override val onClick = openEntry(entry)
            })
            Text(text = entryContent)
        }
    }

    @Composable
    private fun EntryList(entries: Collection<SettingsEntry>) {
        for (entry in entries) {
            Preference(object : PreferenceModel {
                override val title = entry.displayTitle()
                override val summary =
                    "${entry.fromPage?.displayName} -> ${entry.toPage?.displayName}".toState()
                override val onClick = navigator(route = ROUTE_ENTRY + "/${entry.id}")
            })
        }
    }

    @Composable
    private fun openPage(page: SettingsPage): (() -> Unit)? {
        if (page.hasRuntimeParam()) return null
        val context = LocalContext.current
        val route = page.buildRoute()
        val intent = Intent(context, spaEnvironment.browseActivityClass).apply {
            putExtra(KEY_DESTINATION, route)
        }
        return {
            Log.d("DEBUG ACTIVITY", "Open page: $route")
            context.startActivity(intent)
        }
    }

    @Composable
    private fun openEntry(entry: SettingsEntry): (() -> Unit)? {
        if (entry.hasRuntimeParam()) return null
        val context = LocalContext.current
        val route = entry.buildRoute()
        val intent = Intent(context, spaEnvironment.browseActivityClass).apply {
            putExtra(KEY_DESTINATION, route)
        }
        return {
            Log.d("DEBUG ACTIVITY", "Open entry: $route")
            context.startActivity(intent)
        }
    }
}
