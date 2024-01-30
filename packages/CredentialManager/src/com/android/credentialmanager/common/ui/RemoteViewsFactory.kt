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
import com.android.credentialmanager.model.CredentialType
import android.graphics.drawable.Icon

class RemoteViewsFactory {

    companion object {
        private const val setAdjustViewBoundsMethodName = "setAdjustViewBounds"
        private const val setMaxHeightMethodName = "setMaxHeight"
        private const val setBackgroundResourceMethodName = "setBackgroundResource"
        private const val bulletPoint = "\u2022"
        private const val passwordCharacterLength = 15

        fun createDropdownPresentation(
                context: Context,
                icon: Icon,
                credentialEntryInfo: CredentialEntryInfo
        ): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_presentation_layout
            val remoteViews = RemoteViews(context.packageName, layoutId)
            if (credentialEntryInfo.credentialType == CredentialType.UNKNOWN) {
                return remoteViews
            }
            setRemoteViewsPaddings(remoteViews, context, /* primaryTextBottomPadding=*/0)
            if (credentialEntryInfo.credentialType == CredentialType.PASSKEY) {
                val displayName = credentialEntryInfo.displayName ?: credentialEntryInfo.userName
                remoteViews.setTextViewText(android.R.id.text1, displayName)
                val secondaryText = if (credentialEntryInfo.displayName != null)
                    (credentialEntryInfo.userName + " " + bulletPoint + " "
                            + credentialEntryInfo.credentialTypeDisplayName
                            + " " + bulletPoint + " " + credentialEntryInfo.providerDisplayName)
                else (credentialEntryInfo.credentialTypeDisplayName + " " + bulletPoint + " "
                        + credentialEntryInfo.providerDisplayName)
                remoteViews.setTextViewText(android.R.id.text2, secondaryText)
            } else {
                remoteViews.setTextViewText(android.R.id.text1, credentialEntryInfo.userName)
                remoteViews.setTextViewText(android.R.id.text2,
                        bulletPoint.repeat(passwordCharacterLength))
            }
            val textColorPrimary = ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.text_primary)
            remoteViews.setTextColor(android.R.id.text1, textColorPrimary)
            val textColorSecondary = ContextCompat.getColor(context, com.android
                    .credentialmanager.R.color.text_secondary)
            remoteViews.setTextColor(android.R.id.text2, textColorSecondary)
            remoteViews.setImageViewIcon(android.R.id.icon1, icon);
            remoteViews.setBoolean(
                    android.R.id.icon1, setAdjustViewBoundsMethodName, true);
            remoteViews.setInt(
                    android.R.id.icon1,
                    setMaxHeightMethodName,
                    context.resources.getDimensionPixelSize(
                            com.android.credentialmanager.R.dimen.autofill_icon_size));
            remoteViews.setContentDescription(android.R.id.icon1, credentialEntryInfo
                    .providerDisplayName);
            val drawableId =
                    com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_one
            remoteViews.setInt(
                    android.R.id.content, setBackgroundResourceMethodName, drawableId);
            return remoteViews
        }

        fun createMoreSignInOptionsPresentation(context: Context): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_bottom_sheet
            val remoteViews = RemoteViews(context.packageName, layoutId)
            setRemoteViewsPaddings(remoteViews, context)
            remoteViews.setTextViewText(android.R.id.text1, ContextCompat.getString(context,
                    com.android.credentialmanager
                            .R.string.dropdown_presentation_more_sign_in_options_text))

            val textColorPrimary = ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.text_primary)
            remoteViews.setTextColor(android.R.id.text1, textColorPrimary)
            val icon = Icon.createWithResource(context, com
                    .android.credentialmanager.R.drawable.more_horiz_24px)
            icon.setTint(ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.sign_in_options_icon_color))
            remoteViews.setImageViewIcon(android.R.id.icon1, icon)
            remoteViews.setBoolean(
                    android.R.id.icon1, setAdjustViewBoundsMethodName, true);
            remoteViews.setInt(
                    android.R.id.icon1,
                    setMaxHeightMethodName,
                    context.resources.getDimensionPixelSize(
                            com.android.credentialmanager.R.dimen.autofill_icon_size));
            val drawableId =
                    com.android.credentialmanager.R.drawable.more_options_list_item
            remoteViews.setInt(
                    android.R.id.content, setBackgroundResourceMethodName, drawableId);
            return remoteViews
        }

        private fun setRemoteViewsPaddings(
                remoteViews: RemoteViews, context: Context) {
            val bottomPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_bottom_padding)
            setRemoteViewsPaddings(remoteViews, context, bottomPadding)
        }

        private fun setRemoteViewsPaddings(
                remoteViews: RemoteViews, context: Context, primaryTextBottomPadding: Int) {
            val leftPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_left_padding)
            val iconToTextPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_icon_to_text_padding)
            val rightPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_right_padding)
            val topPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_top_padding)
            val bottomPadding = context.resources.getDimensionPixelSize(
                    com.android.credentialmanager.R.dimen.autofill_view_bottom_padding)
            remoteViews.setViewPadding(
                    android.R.id.icon1,
                    leftPadding,
                    /* top=*/0,
                    /* right=*/0,
                    /* bottom=*/0)
            remoteViews.setViewPadding(
                    android.R.id.text1,
                    iconToTextPadding,
                    /* top=*/topPadding,
                    /* right=*/rightPadding,
                    primaryTextBottomPadding)
            remoteViews.setViewPadding(
                    android.R.id.text2,
                    iconToTextPadding,
                    /* top=*/0,
                    /* right=*/rightPadding,
                    /* bottom=*/bottomPadding)
        }

        private fun isDarkMode(context: Context): Boolean {
            val currentNightMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            return currentNightMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
