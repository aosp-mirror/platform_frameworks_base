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

package com.android.settingslib.spa.tests.testutils

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.BrowseActivity
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.EntryStatusData
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.common.SpaLogger
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.widget.preference.SimplePreferenceMacro

class SpaLoggerForTest : SpaLogger {
    data class MsgCountKey(val msg: String, val category: LogCategory)
    data class EventCountKey(val id: String, val event: LogEvent, val category: LogCategory)

    private val messageCount: MutableMap<MsgCountKey, Int> = mutableMapOf()
    private val eventCount: MutableMap<EventCountKey, Int> = mutableMapOf()

    override fun message(tag: String, msg: String, category: LogCategory) {
        val key = MsgCountKey("[$tag]$msg", category)
        messageCount[key] = (messageCount[key] ?: 0) + 1
    }

    override fun event(id: String, event: LogEvent, category: LogCategory, extraData: Bundle) {
        val key = EventCountKey(id, event, category)
        eventCount[key] = (eventCount[key] ?: 0) + 1
    }

    fun getMessageCount(tag: String, msg: String, category: LogCategory): Int {
        val key = MsgCountKey("[$tag]$msg", category)
        return messageCount[key] ?: 0
    }

    fun getEventCount(id: String, event: LogEvent, category: LogCategory): Int {
        val key = EventCountKey(id, event, category)
        return eventCount[key] ?: 0
    }

    fun reset() {
        messageCount.clear()
        eventCount.clear()
    }
}

class BlankActivity : BrowseActivity()

object SppHome : SettingsPageProvider {
    override val name = "SppHome"

    override fun getTitle(arguments: Bundle?): String {
        return "TitleHome"
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SppLayer1.buildInject().setLink(fromPage = owner).build(),
            SppDialog.buildInject().setLink(fromPage = owner).build(),
        )
    }
}

object SppDisabled : SettingsPageProvider {
    override val name = "SppDisabled"

    override fun isEnabled(arguments: Bundle?): Boolean = false

    override fun getTitle(arguments: Bundle?): String {
        return "TitleDisabled"
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SppLayer1.buildInject().setLink(fromPage = owner).build(),
        )
    }
}

object SppLayer1 : SettingsPageProvider {
    override val name = "SppLayer1"

    override fun getTitle(arguments: Bundle?): String {
        return "TitleLayer1"
    }

    fun buildInject(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(this.createSettingsPage())
            .setMacro {
                SimplePreferenceMacro(
                    title = "SppHome to Layer1",
                    clickRoute = name
                )
            }
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SettingsEntryBuilder.create(owner, "Layer1Entry1").build(),
            SppLayer2.buildInject().setLink(fromPage = owner).build(),
            SettingsEntryBuilder.create(owner, "Layer1Entry2").build(),
        )
    }
}

object SppLayer2 : SettingsPageProvider {
    override val name = "SppLayer2"

    fun buildInject(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(this.createSettingsPage())
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SettingsEntryBuilder.create(owner, "Layer2Entry1").build(),
            SettingsEntryBuilder.create(owner, "Layer2Entry2").build(),
        )
    }
}

object SppDialog : SettingsPageProvider {
    override val name = "SppDialog"
    override val navType = SettingsPageProvider.NavType.Dialog

    const val CONTENT = "SppDialog Content"

    @Composable
    override fun Page(arguments: Bundle?) {
        Text(CONTENT)
    }

    fun buildInject() = SettingsEntryBuilder.createInject(this.createSettingsPage())
        .setMacro { SimplePreferenceMacro(title = name, clickRoute = name) }
}

object SppForSearch : SettingsPageProvider {
    override val name = "SppForSearch"

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = this.createSettingsPage()
        return listOf(
            SettingsEntryBuilder.create(owner, "SearchStaticWithNoStatus")
                .setSearchDataFn { EntrySearchData(title = "SearchStaticWithNoStatus") }
                .build(),
            SettingsEntryBuilder.create(owner, "SearchStaticWithMutableStatus")
                .setHasMutableStatus(true)
                .setSearchDataFn { EntrySearchData(title = "SearchStaticWithMutableStatus") }
                .setStatusDataFn { EntryStatusData(isSwitchOff = true) }
                .build(),
            SettingsEntryBuilder.create(owner, "SearchDynamicWithMutableStatus")
                .setIsSearchDataDynamic(true)
                .setHasMutableStatus(true)
                .setSearchDataFn { EntrySearchData(title = "SearchDynamicWithMutableStatus") }
                .setStatusDataFn { EntryStatusData(isDisabled = true) }
                .build(),
            SettingsEntryBuilder.create(owner, "SearchDynamicWithImmutableStatus")
                .setIsSearchDataDynamic(true)
                .setSearchDataFn {
                    EntrySearchData(
                        title = "SearchDynamicWithImmutableStatus",
                        keyword = listOf("kw1", "kw2")
                    )
                }
                .setStatusDataFn { EntryStatusData(isDisabled = true) }
                .build(),
        )
    }
}

class SpaEnvironmentForTest(
    context: Context,
    rootPages: List<SettingsPage> = emptyList(),
    override val browseActivityClass: Class<out Activity>? = BlankActivity::class.java,
    override val logger: SpaLogger = object : SpaLogger {}
) : SpaEnvironment(context) {

    override val pageProviderRepository = lazy {
        SettingsPageProviderRepository(
            listOf(
                SppHome, SppLayer1, SppLayer2,
                SppForSearch, SppDisabled,
                object : SettingsPageProvider {
                    override val name = "SppWithParam"
                    override val parameter = listOf(
                        navArgument("string_param") { type = NavType.StringType },
                        navArgument("int_param") { type = NavType.IntType },
                    )
                },
                object : SettingsPageProvider {
                    override val name = "SppWithRtParam"
                    override val parameter = listOf(
                        navArgument("string_param") { type = NavType.StringType },
                        navArgument("int_param") { type = NavType.IntType },
                        navArgument("rt_param") { type = NavType.StringType },
                    )
                },
                SppDialog,
            ),
            rootPages
        )
    }

    fun createPage(sppName: String, arguments: Bundle? = null): SettingsPage {
        return pageProviderRepository.value
            .getProviderOrNull(sppName)!!.createSettingsPage(arguments)
    }
}
