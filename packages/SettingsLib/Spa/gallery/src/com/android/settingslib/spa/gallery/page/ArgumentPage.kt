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
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
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

    @Composable
    override fun Page(arguments: Bundle?) {
        ArgumentPage(
            stringParam = arguments!!.getString(STRING_PARAM_NAME, "default"),
            intParam = arguments.getInt(INT_PARAM_NAME),
        )
    }

    @Composable
    fun EntryItem(stringParam: String, intParam: Int) {
        Preference(object : PreferenceModel {
            override val title = TITLE
            override val summary = { "$STRING_PARAM_NAME=$stringParam, $INT_PARAM_NAME=$intParam" }
            override val onClick = navigator("$name/$stringParam/$intParam")
        })
    }
}

@Composable
fun ArgumentPage(stringParam: String, intParam: Int) {
    RegularScaffold(title = TITLE) {
        Preference(object : PreferenceModel {
            override val title = "String param value"
            override val summary = { stringParam }
        })

        Preference(object : PreferenceModel {
            override val title = "Int param value"
            override val summary = { intParam.toString() }
        })

        ArgumentPageProvider.EntryItem(stringParam = "foo", intParam = intParam + 1)

        ArgumentPageProvider.EntryItem(stringParam = "bar", intParam = intParam + 1)
    }
}

@Preview(showBackground = true)
@Composable
private fun ArgumentPagePreview() {
    SettingsTheme {
        ArgumentPage(stringParam = "foo", intParam = 0)
    }
}
