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
import android.content.res.Configuration
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.android.credentialmanager.model.get.CredentialEntryInfo
import android.graphics.drawable.Icon

class RemoteViewsFactory {

    companion object {
        private const val setAdjustViewBoundsMethodName = "setAdjustViewBounds"
        private const val setMaxHeightMethodName = "setMaxHeight"
        private const val setBackgroundResourceMethodName = "setBackgroundResource"

        fun createDropdownPresentation(
                context: Context,
                icon: Icon,
                credentialEntryInfo: CredentialEntryInfo
        ): RemoteViews {
            val padding = context.resources.getDimensionPixelSize(com.android
                    .credentialmanager.R.dimen.autofill_view_padding)
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .autofill_dataset_left_with_item_tag_hint
            val remoteViews = RemoteViews(context.packageName, layoutId)
            setRemoteViewsPaddings(remoteViews, padding)
            val textColorPrimary = getTextColorPrimary(isDarkMode(context), context);
            remoteViews.setTextColor(android.R.id.text1, textColorPrimary);
            remoteViews.setTextViewText(android.R.id.text1, credentialEntryInfo.userName)

            remoteViews.setImageViewIcon(android.R.id.icon1, icon);
            remoteViews.setBoolean(
                    android.R.id.icon1, setAdjustViewBoundsMethodName, true);
            remoteViews.setInt(
                    android.R.id.icon1,
                     setMaxHeightMethodName,
                    context.resources.getDimensionPixelSize(
                            com.android.credentialmanager.R.dimen.autofill_icon_size));
            val drawableId = if (isDarkMode(context))
                com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_one_dark
            else com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_one
            remoteViews.setInt(
                    android.R.id.content, setBackgroundResourceMethodName, drawableId);
            return remoteViews
        }

        private fun setRemoteViewsPaddings(
                remoteViews: RemoteViews,
                padding: Int) {
            val halfPadding = padding / 2
            remoteViews.setViewPadding(
                    android.R.id.text1,
                    halfPadding,
                    halfPadding,
                    halfPadding,
                    halfPadding)
        }

        private fun isDarkMode(context: Context): Boolean {
            val currentNightMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            return currentNightMode == Configuration.UI_MODE_NIGHT_YES
        }

        private fun getTextColorPrimary(darkMode: Boolean, context: Context): Int {
            return if (darkMode) ContextCompat.getColor(
                    context, com.android.credentialmanager.R.color.text_primary_dark_mode)
            else ContextCompat.getColor(context, com.android.credentialmanager.R.color.text_primary)
        }
    }
}
