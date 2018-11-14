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
import com.android.systemui.R
import java.lang.Math.max

class PrivacyDialogBuilder(val context: Context, itemsList: List<PrivacyItem>) {
    companion object {
        val MILLIS_IN_MINUTE: Long = 1000 * 60
    }

    private val itemsByType: Map<PrivacyType, List<PrivacyItem>>
    val app: PrivacyApplication?

    init {
        itemsByType = itemsList.groupBy { it.privacyType }
        val apps = itemsList.map { it.application }.distinct()
        val singleApp = apps.size == 1
        app = if (singleApp) apps.get(0) else null
    }

    private fun buildTextForItem(type: PrivacyType, now: Long): String {
        val items = itemsByType.getOrDefault(type, emptyList<PrivacyItem>())
        return when (items.size) {
            0 -> throw IllegalStateException("List cannot be empty")
            1 -> {
                val item = items.get(0)
                val minutesUsed = max(((now - item.timeStarted) / MILLIS_IN_MINUTE).toInt(), 1)
                context.getString(R.string.ongoing_privacy_dialog_app_item,
                        item.application.applicationName, type.getName(context), minutesUsed)
            }
            else -> {
                val apps = items.map { it.application.applicationName }.joinToString()
                context.getString(R.string.ongoing_privacy_dialog_apps_item,
                        apps, type.getName(context))
            }
        }
    }

    private fun buildTextForApp(types: Set<PrivacyType>): List<String> {
        app?.let {
            val typesText = types.map { it.getName(context) }.sorted().joinToString()
            return listOf(context.getString(R.string.ongoing_privacy_dialog_single_app,
                    it.applicationName, typesText))
        } ?: throw IllegalStateException("There has to be a single app")
    }

    fun generateText(now: Long): List<String> {
        if (app == null || itemsByType.keys.size == 1) {
            return itemsByType.keys.map { buildTextForItem(it, now) }
        } else {
            return buildTextForApp(itemsByType.keys)
        }
    }

    fun generateTypesText() = itemsByType.keys.map { it.getName(context) }.sorted().joinToString()

    fun generateIcons() = itemsByType.keys.map { it.getIcon(context) }
}
