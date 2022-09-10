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
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.android.settingslib.spa.framework.BrowseActivity
import com.android.settingslib.spa.framework.common.PageModel
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.util.getIntArg
import com.android.settingslib.spa.framework.util.getStringArg
import com.android.settingslib.spa.framework.util.navLink
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TITLE = "Sample page with arguments"
private const val STRING_PARAM_NAME = "stringParam"
private const val INT_PARAM_NAME = "intParam"

class ArgumentPageModel : PageModel() {

    companion object {
        const val name = "Argument"
        val parameter = listOf(
            navArgument(STRING_PARAM_NAME) { type = NavType.StringType },
            navArgument(INT_PARAM_NAME) { type = NavType.IntType },
        )

        fun buildArgument(stringParam: String, intParam: Int? = null): Bundle {
            val args = Bundle()
            args.putString(STRING_PARAM_NAME, stringParam)
            if (intParam != null) args.putInt(INT_PARAM_NAME, intParam)
            return args
        }

        fun buildNextArgument(newStringParam: String, arguments: Bundle? = null): Bundle {
            val intParam = parameter.getIntArg(INT_PARAM_NAME, arguments)
            return if (intParam == null)
                buildArgument(newStringParam)
            else
                buildArgument(newStringParam, intParam + 1)
        }

        fun isValidArgument(arguments: Bundle?): Boolean {
            val stringParam = parameter.getStringArg(STRING_PARAM_NAME, arguments)
            return (stringParam != null && listOf("foo", "bar").contains(stringParam))
        }

        fun getInjectEntryName(arguments: Bundle?): String {
            return "${name}_${parameter.getStringArg(STRING_PARAM_NAME, arguments)}"
        }

        @Composable
        fun create(arguments: Bundle?): ArgumentPageModel {
            val pageModel: ArgumentPageModel = viewModel(key = arguments.toString())
            pageModel.initOnce(arguments)
            return pageModel
        }
    }

    private val title = TITLE
    private var arguments: Bundle? = null
    private var stringParam: String? = null
    private var intParam: Int? = null
    private var highlightName: String? = null

    override fun initialize(arguments: Bundle?) {
        logMsg("init with args " + arguments.toString())
        this.arguments = arguments
        stringParam = parameter.getStringArg(STRING_PARAM_NAME, arguments)
        intParam = parameter.getIntArg(INT_PARAM_NAME, arguments)
        highlightName = arguments?.getString(BrowseActivity.HIGHLIGHT_ENTRY_PARAM_NAME)
    }

    @Composable
    fun genPageTitle(): String {
        return title
    }

    @Composable
    fun genStringParamPreferenceModel(): PreferenceModel {
        return object : PreferenceModel {
            override val title = "String param value"
            override val summary = stateOf(stringParam!!)
        }
    }

    @Composable
    fun genIntParamPreferenceModel(): PreferenceModel {
        return object : PreferenceModel {
            override val title = "Int param value"
            override val summary = stateOf(intParam!!.toString())
        }
    }

    @Composable
    fun genInjectPreferenceModel(): PreferenceModel {
        val summaryArray = listOf(
            "$STRING_PARAM_NAME=" + stringParam!!,
            "$INT_PARAM_NAME=" + intParam!!
        )
        return object : PreferenceModel {
            override val title = genPageTitle()
            override val summary = stateOf(summaryArray.joinToString(", "))
            override val onClick = navigator(name + parameter.navLink(arguments))
        }
    }
}

private fun logMsg(message: String) {
    Log.d("ArgumentPageModel", message)
}
