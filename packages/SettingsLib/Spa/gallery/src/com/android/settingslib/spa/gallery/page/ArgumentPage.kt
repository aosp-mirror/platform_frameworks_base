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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.getIntArg
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.framework.util.normalize
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val TITLE = "Sample page with arguments"
private const val STRING_PARAM_NAME = "stringParam"
private const val INT_PARAM_NAME = "intParam"

object ArgumentPageProvider : SettingsPageProvider {
    override val name = "Argument"

    override val parameter = listOf(
        navArgument(STRING_PARAM_NAME) { type = NavType.StringType },
        navArgument(INT_PARAM_NAME) { type = NavType.IntType },
    )

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!parameter.isValid(arguments)) return emptyList()

        val owner = SettingsPage(name, parameter.normalize(arguments))
        val entryList = mutableListOf<SettingsEntry>()
        val stringParamEntry = SettingsEntryBuilder.create("string_param", owner)
        stringParamEntry.setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = "String param value"
                override val summary =
                    parameter.getStringArg(STRING_PARAM_NAME, arguments)!!.toState()
            })
        }
        entryList.add(stringParamEntry.build())

        val intParamEntry = SettingsEntryBuilder.create("int_param", owner)
        intParamEntry.setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = "Int param value"
                override val summary =
                    parameter.getIntArg(INT_PARAM_NAME, arguments)!!.toString().toState()
            })
        }
        entryList.add(intParamEntry.build())

        val intParam = parameter.getIntArg(INT_PARAM_NAME, arguments)
        val entryFoo = buildInjectEntry(buildNextArgument("foo", intParam))
        val entryBar = buildInjectEntry(buildNextArgument("bar", intParam))
        if (entryFoo != null) entryList.add(entryFoo.setLink(fromPage = owner).build())
        if (entryBar != null) entryList.add(entryBar.setLink(fromPage = owner).build())

        return entryList
    }

    private fun buildInjectEntry(arguments: Bundle?): SettingsEntryBuilder? {
        if (!parameter.isValid(arguments)) return null

        val seBuilder =
            SettingsEntryBuilder.createInject(name, parameter.normalize(arguments))
        seBuilder.setIsAllowSearch(false)

        seBuilder.setUiLayoutFn {
            val summaryArray = listOf(
                "$STRING_PARAM_NAME=" + parameter.getStringArg(STRING_PARAM_NAME, arguments)!!,
                "$INT_PARAM_NAME=" + parameter.getIntArg(INT_PARAM_NAME, arguments)!!
            )
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val summary = summaryArray.joinToString(", ").toState()
                override val onClick = navigator(name + parameter.navLink(arguments))
            })
        }

        return seBuilder
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            for (entry in buildEntry(arguments)) {
                entry.uiLayout()
            }
        }
    }

    @Composable
    fun EntryItem(stringParam: String, intParam: Int) {
        buildInjectEntry(buildArgument(stringParam, intParam))?.build()?.uiLayout?.let { it() }
    }

    fun buildArgument(stringParam: String, intParam: Int? = null): Bundle {
        val args = Bundle()
        args.putString(STRING_PARAM_NAME, stringParam)
        if (intParam != null) args.putInt(INT_PARAM_NAME, intParam)
        return args
    }

    private fun buildNextArgument(stringParam: String, intParam: Int? = null): Bundle {
        return if (intParam == null)
            buildArgument(stringParam)
        else
            buildArgument(stringParam, intParam + 1)
    }
}

@Preview(showBackground = true)
@Composable
private fun ArgumentPagePreview() {
    SettingsTheme {
        ArgumentPageProvider.Page(
            ArgumentPageProvider.buildArgument(stringParam = "foo", intParam = 0)
        )
    }
}

private fun List<NamedNavArgument>.isValid(arguments: Bundle?): Boolean {
    val stringParam = getStringArg(STRING_PARAM_NAME, arguments)
    return (stringParam != null && listOf("foo", "bar").contains(stringParam))
}
