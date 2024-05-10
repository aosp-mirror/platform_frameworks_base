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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.PageModel
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.util.getIntArg
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TAG = "ArgumentPageModel"

// Defines all the resources for this page.
// In real Settings App, resources data is defined in xml, rather than SPP.
private const val PAGE_TITLE = "Sample page with arguments"
private const val STRING_PARAM_TITLE = "String param value"
private const val INT_PARAM_TITLE = "Int param value"
private const val STRING_PARAM_NAME = "stringParam"
private const val INT_PARAM_NAME = "rt_intParam"
private val ARGUMENT_PAGE_KEYWORDS = listOf("argument keyword1", "argument keyword2")

class ArgumentPageModel : PageModel() {

    companion object {
        val parameter = listOf(
            navArgument(STRING_PARAM_NAME) { type = NavType.StringType },
            navArgument(INT_PARAM_NAME) { type = NavType.IntType },
        )

        fun buildArgument(stringParam: String? = null, intParam: Int? = null): Bundle {
            val args = Bundle()
            if (stringParam != null) args.putString(STRING_PARAM_NAME, stringParam)
            if (intParam != null) args.putInt(INT_PARAM_NAME, intParam)
            return args
        }

        fun buildNextArgument(arguments: Bundle? = null): Bundle {
            val intParam = parameter.getIntArg(INT_PARAM_NAME, arguments)
            val nextIntParam = if (intParam != null) intParam + 1 else null
            return buildArgument(intParam = nextIntParam)
        }

        fun isValidArgument(arguments: Bundle?): Boolean {
            val stringParam = parameter.getStringArg(STRING_PARAM_NAME, arguments)
            return (stringParam != null && listOf("foo", "bar").contains(stringParam))
        }

        fun genStringParamSearchData(): EntrySearchData {
            return EntrySearchData(title = STRING_PARAM_TITLE)
        }

        fun genIntParamSearchData(): EntrySearchData {
            return EntrySearchData(title = INT_PARAM_TITLE)
        }

        fun genInjectSearchData(): EntrySearchData {
            return EntrySearchData(title = PAGE_TITLE, keyword = ARGUMENT_PAGE_KEYWORDS)
        }

        fun genPageTitle(): String {
            return PAGE_TITLE
        }

        @Composable
        fun create(arguments: Bundle?): ArgumentPageModel {
            val pageModel: ArgumentPageModel = viewModel(key = arguments.toString())
            pageModel.initOnce(arguments)
            return pageModel
        }
    }

    private var arguments: Bundle? = null
    private var stringParam: String? = null
    private var intParam: Int? = null

    override fun initialize(arguments: Bundle?) {
        SpaEnvironmentFactory.instance.logger.message(
            TAG, "Initialize with args " + arguments.toString()
        )
        this.arguments = arguments
        stringParam = parameter.getStringArg(STRING_PARAM_NAME, arguments)
        intParam = parameter.getIntArg(INT_PARAM_NAME, arguments)
    }

    @Composable
    fun genStringParamPreferenceModel(): PreferenceModel {
        return object : PreferenceModel {
            override val title = STRING_PARAM_TITLE
            override val summary = { stringParam!! }
        }
    }

    @Composable
    fun genIntParamPreferenceModel(): PreferenceModel {
        return object : PreferenceModel {
            override val title = INT_PARAM_TITLE
            override val summary = { intParam!!.toString() }
        }
    }

    @Composable
    fun genInjectPreferenceModel(): PreferenceModel {
        val summaryArray = listOf(
            "$STRING_PARAM_NAME=" + stringParam!!,
            "$INT_PARAM_NAME=" + intParam!!
        )
        return object : PreferenceModel {
            override val title = PAGE_TITLE
            override val summary = { summaryArray.joinToString(", ") }
            override val onClick = navigator(
                SettingsPageProviderEnum.ARGUMENT.name + parameter.navLink(arguments)
            )
        }
    }
}
