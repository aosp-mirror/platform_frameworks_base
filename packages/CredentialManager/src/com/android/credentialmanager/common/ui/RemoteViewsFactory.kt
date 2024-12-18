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

package com.android.credentialmanager.common.ui

import android.content.Context
import android.util.Log
import com.android.credentialmanager.common.Constants
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.EntryInfo
import android.graphics.drawable.Icon

class RemoteViewsFactory {

    companion object {
        private const val SET_ADJUST_VIEW_BOUNDS_METHOD_NAME = "setAdjustViewBounds"
        private const val SET_MAX_HEIGHT_METHOD_NAME = "setMaxHeight"
        private const val SET_BACKGROUND_RESOURCE_METHOD_NAME = "setBackgroundResource"
        private const val SEPARATOR = " " + "\u2022" + " "

        // TODO(jbabs): RemoteViews#setViewPadding renders this as 8dp on the display. Debug why.
        private const val END_ITEMS_PADDING = 28

        fun createDropdownPresentation(
            context: Context,
            icon: Icon,
            entryInfo: EntryInfo,
            isFirstEntry: Boolean,
            isLastEntry: Boolean,
        ): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_presentation_layout
            val remoteViews = RemoteViews(context.packageName, layoutId)
            if (entryInfo is CredentialEntryInfo) {
                val displayName = entryInfo.displayName ?: entryInfo.userName
                remoteViews.setTextViewText(android.R.id.text1, displayName)
                val secondaryText = getSecondaryText(entryInfo)
                if (secondaryText.isNullOrBlank()) {
                    Log.w(Constants.LOG_TAG, "Secondary text for dropdown credential entry is null")
                } else {
                    remoteViews.setTextViewText(android.R.id.text2, secondaryText)
                }
                remoteViews.setContentDescription(
                    android.R.id.icon1, entryInfo
                        .providerDisplayName
                )
            } else if (entryInfo is ActionEntryInfo) {
                remoteViews.setTextViewText(android.R.id.text1, entryInfo.title)
                remoteViews.setTextViewText(android.R.id.text2, entryInfo.subTitle)
            }
            remoteViews.setImageViewIcon(android.R.id.icon1, icon)
            remoteViews.setBoolean(
                android.R.id.icon1, SET_ADJUST_VIEW_BOUNDS_METHOD_NAME, true
            )
            remoteViews.setInt(
                android.R.id.icon1,
                SET_MAX_HEIGHT_METHOD_NAME,
                context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_icon_size
                )
            )
            val drawableId =
                if (isFirstEntry)
                    com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_one else
                    com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_middle
            remoteViews.setInt(
                android.R.id.content, SET_BACKGROUND_RESOURCE_METHOD_NAME, drawableId)
            if (isFirstEntry) remoteViews.setViewPadding(
                com.android.credentialmanager.R.id.credential_card,
                /* left=*/0,
                /* top=*/END_ITEMS_PADDING,
                /* right=*/0,
                /* bottom=*/0)
            if (isLastEntry) remoteViews.setViewPadding(
                com.android.credentialmanager.R.id.credential_card,
                /*left=*/0,
                /* top=*/0,
                /* right=*/0,
                /* bottom=*/END_ITEMS_PADDING)
            return remoteViews
        }

        /**
         * Computes the secondary text for dropdown presentation based on available fields.
         *
         * <p> Format for secondary text is [username] . [credentialType] . [providerDisplayName]
         * If display name and username are the same, we do not display username
         * If credential type is missing as in the case with SiwG, we just display
         * providerDisplayName. Both credential type and provider display name should not be empty.
         */
        private fun getSecondaryText(credentialEntryInfo: CredentialEntryInfo): String? {
            return listOf(if (credentialEntryInfo.displayName != null &&
                (credentialEntryInfo.displayName != credentialEntryInfo.userName))
                (credentialEntryInfo.userName) else null,
                credentialEntryInfo.credentialTypeDisplayName,
                credentialEntryInfo.providerDisplayName).filterNot { it.isNullOrBlank() }
                    .let { itemsToDisplay ->
                        if (itemsToDisplay.isEmpty()) null
                        else itemsToDisplay.joinToString(separator = SEPARATOR)
                    }
        }

        fun createMoreSignInOptionsPresentation(context: Context): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_bottom_sheet
            val remoteViews = RemoteViews(context.packageName, layoutId)
            remoteViews.setTextViewText(android.R.id.text1, ContextCompat.getString(context,
                com.android.credentialmanager
                        .R.string.dropdown_presentation_more_sign_in_options_text))
            remoteViews.setBoolean(
                android.R.id.icon1, SET_ADJUST_VIEW_BOUNDS_METHOD_NAME, true)
            remoteViews.setInt(
                android.R.id.icon1,
                SET_MAX_HEIGHT_METHOD_NAME,
                context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_icon_size))
            val drawableId =
                com.android.credentialmanager.R.drawable.more_options_list_item
            remoteViews.setInt(
                android.R.id.content, SET_BACKGROUND_RESOURCE_METHOD_NAME, drawableId)
            return remoteViews
        }
    }
}
