/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.privacy

import android.content.Context
import android.graphics.drawable.Drawable
import com.android.systemui.R

class PrivacyDialogBuilder(private val context: Context, itemsList: List<PrivacyItem>) {

    val appsAndTypes: List<Pair<PrivacyApplication, List<PrivacyType>>>
    val types: List<PrivacyType>
    private val separator = context.getString(R.string.ongoing_privacy_dialog_separator)
    private val lastSeparator = context.getString(R.string.ongoing_privacy_dialog_last_separator)

    init {
        appsAndTypes = itemsList.groupBy({ it.application }, { it.privacyType })
                .toList()
                .sortedWith(compareBy({ -it.second.size }, // Sort by number of AppOps
                        { it.second.min() })) // Sort by "smallest" AppOpp (Location is largest)
        types = itemsList.map { it.privacyType }.distinct().sorted()
    }

    fun generateIconsForApp(types: List<PrivacyType>): List<Drawable> {
        return types.sorted().map { it.getIcon(context) }
    }

    fun generateIcons() = types.map { it.getIcon(context) }

    private fun <T> List<T>.joinWithAnd(): StringBuilder {
        return subList(0, size - 1).joinTo(StringBuilder(), separator = separator).apply {
            append(lastSeparator)
            append(this@joinWithAnd.last())
        }
    }

    fun joinTypes(): String {
        return when (types.size) {
            0 -> ""
            1 -> types[0].getName(context)
            else -> types.map { it.getName(context) }.joinWithAnd().toString()
        }
    }

    fun getDialogTitle(): String {
        return context.getString(R.string.ongoing_privacy_dialog_multiple_apps_title,
                    joinTypes())
    }
}
