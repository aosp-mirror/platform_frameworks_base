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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel

private const val OPERATOR_PARAM_NAME = "opParam"
private const val ITEM_NAME_PARAM_NAME = "rt_nameParam"
private val ALLOWED_OPERATOR_LIST = listOf("opDnD", "opPiP", "opInstall", "opConnect")

object ItemOperatePageProvider : SettingsPageProvider {
    override val name = SettingsPageProviderEnum.ITEM_OP_PAGE.name
    override val displayName = SettingsPageProviderEnum.ITEM_OP_PAGE.displayName
    override val parameter = listOf(
        navArgument(OPERATOR_PARAM_NAME) { type = NavType.StringType },
        navArgument(ITEM_NAME_PARAM_NAME) { type = NavType.StringType },
    )

    override fun getTitle(arguments: Bundle?): String {
        // Operation name is not a runtime parameter, which should always available
        val operation = parameter.getStringArg(OPERATOR_PARAM_NAME, arguments) ?: "opInValid"
        // Item name is a runtime parameter, which could be missing
        val itemName = parameter.getStringArg(ITEM_NAME_PARAM_NAME, arguments) ?: "[unset]"
        return "$operation on $itemName"
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!isValidArgs(arguments)) return emptyList()

        val owner = createSettingsPage(arguments)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("ItemName", owner)
                .setUiLayoutFn {
                    // Item name is a runtime parameter, which needs to be read inside UiLayoutFn
                    val itemName = parameter.getStringArg(ITEM_NAME_PARAM_NAME, it) ?: "NULL"
                    Preference(
                        object : PreferenceModel {
                            override val title = "Item $itemName"
                        }
                    )
                }.build()
        )

        // Operation name is not a runtime parameter, which can be read outside.
        val opName = parameter.getStringArg(OPERATOR_PARAM_NAME, arguments)!!
        entryList.add(
            SettingsEntryBuilder.create("ItemOp", owner)
                .setUiLayoutFn {
                    var checked by rememberSaveable { mutableStateOf(false) }
                    SwitchPreference(remember {
                        object : SwitchPreferenceModel {
                            override val title = "Item operation: $opName"
                            override val checked = { checked }
                            override val onCheckedChange =
                                { newChecked: Boolean -> checked = newChecked }
                        }
                    })
                }.build(),
        )
        return entryList
    }

    fun buildInjectEntry(opParam: String): SettingsEntryBuilder? {
        val arguments = bundleOf(OPERATOR_PARAM_NAME to opParam)
        if (!isValidArgs(arguments)) return null

        return SettingsEntryBuilder.createInject(
            owner = createSettingsPage(arguments),
            label = "ItemOp_$opParam",
        ).setUiLayoutFn {
            // Item name is a runtime parameter, which needs to be read inside UiLayoutFn
            val itemName = parameter.getStringArg(ITEM_NAME_PARAM_NAME, it) ?: "NULL"
            Preference(
                object : PreferenceModel {
                    override val title = "item: $itemName"
                    override val onClick = navigator(
                        SettingsPageProviderEnum.ITEM_OP_PAGE.name + parameter.navLink(it)
                    )
                }
            )
        }
    }

    fun isValidArgs(arguments: Bundle?): Boolean {
        val opParam = parameter.getStringArg(OPERATOR_PARAM_NAME, arguments)
        return (opParam != null && ALLOWED_OPERATOR_LIST.contains(opParam))
    }

    fun genRuntimeArguments(itemName: String): Bundle {
        return bundleOf(ITEM_NAME_PARAM_NAME to itemName)
    }
}
