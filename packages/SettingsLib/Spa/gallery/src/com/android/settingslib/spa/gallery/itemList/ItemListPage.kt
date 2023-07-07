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

package com.android.settingslib.spa.gallery.itemList

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val OPERATOR_PARAM_NAME = "opParam"

object ItemListPageProvider : SettingsPageProvider {
    override val name = SettingsPageProviderEnum.ITEM_LIST.name
    override val displayName = SettingsPageProviderEnum.ITEM_LIST.displayName
    override val parameter = listOf(
        navArgument(OPERATOR_PARAM_NAME) { type = NavType.StringType },
    )

    override fun getTitle(arguments: Bundle?): String {
        val operation = parameter.getStringArg(OPERATOR_PARAM_NAME, arguments) ?: "NULL"
        return "Operation: $operation"
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!ItemOperatePageProvider.isValidArgs(arguments)) return emptyList()
        val operation = parameter.getStringArg(OPERATOR_PARAM_NAME, arguments)!!
        val owner = createSettingsPage(arguments)
        return listOf(
            ItemOperatePageProvider.buildInjectEntry(operation)!!.setLink(fromPage = owner).build(),
        )
    }

    fun buildInjectEntry(opParam: String): SettingsEntryBuilder? {
        val arguments = bundleOf(OPERATOR_PARAM_NAME to opParam)
        if (!ItemOperatePageProvider.isValidArgs(arguments)) return null

        return SettingsEntryBuilder.createInject(
            owner = createSettingsPage(arguments),
            label = "ItemList_$opParam",
        ).setUiLayoutFn {
            Preference(
                object : PreferenceModel {
                    override val title = opParam
                    override val onClick = navigator(
                        SettingsPageProviderEnum.ITEM_LIST.name + parameter.navLink(it)
                    )
                }
            )
        }.setSearchDataFn {
            EntrySearchData(title = "Operation: $opParam")
        }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val title = remember { getTitle(arguments) }
        val entries = remember { buildEntry(arguments) }
        val itemList = remember {
            // Add logic to get item List during runtime.
            listOf("itemFoo", "itemBar", "itemToy")
        }
        RegularScaffold(title) {
            for (item in itemList) {
                val rtArgs = ItemOperatePageProvider.genRuntimeArguments(item)
                for (entry in entries) {
                    entry.UiLayout(rtArgs)
                }
            }
        }
    }
}
