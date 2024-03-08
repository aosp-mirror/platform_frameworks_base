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

        fun createDropdownPresentation(
            context: Context,
            icon: Icon,
            credentialEntryInfo: CredentialEntryInfo,
            isFirstEntry: Boolean,
            isLastEntry: Boolean,
        ): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_presentation_layout
            val remoteViews = RemoteViews(context.packageName, layoutId)
            if (credentialEntryInfo.credentialType == CredentialType.UNKNOWN) {
                return remoteViews
            }
            val displayName = credentialEntryInfo.displayName ?: credentialEntryInfo.userName
            remoteViews.setTextViewText(android.R.id.text1, displayName)
            val secondaryText =
                if (credentialEntryInfo.displayName != null
                    && (credentialEntryInfo.displayName != credentialEntryInfo.userName))
                    (credentialEntryInfo.userName + " " + bulletPoint + " "
                            + credentialEntryInfo.credentialTypeDisplayName
                            + " " + bulletPoint + " " + credentialEntryInfo.providerDisplayName)
                else (credentialEntryInfo.credentialTypeDisplayName + " " + bulletPoint + " "
                        + credentialEntryInfo.providerDisplayName)
            remoteViews.setTextViewText(android.R.id.text2, secondaryText)
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
                if (isFirstEntry)
                    com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_one else
                    com.android.credentialmanager.R.drawable.fill_dialog_dynamic_list_item_middle
            remoteViews.setInt(
                android.R.id.content, setBackgroundResourceMethodName, drawableId);
            if (isFirstEntry) remoteViews.setViewPadding(
                com.android.credentialmanager.R.id.credential_card,
                /* left=*/0,
                /* top=*/8,
                /* right=*/0,
                /* bottom=*/0)
            if (isLastEntry) remoteViews.setViewPadding(
                com.android.credentialmanager.R.id.credential_card,
                /*left=*/0,
                /* top=*/0,
                /* right=*/0,
                /* bottom=*/8)
            return remoteViews
        }

        fun createMoreSignInOptionsPresentation(context: Context): RemoteViews {
            var layoutId: Int = com.android.credentialmanager.R.layout
                    .credman_dropdown_bottom_sheet
            val remoteViews = RemoteViews(context.packageName, layoutId)
            remoteViews.setTextViewText(android.R.id.text1, ContextCompat.getString(context,
                com.android.credentialmanager
                        .R.string.dropdown_presentation_more_sign_in_options_text))
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
    }
}
