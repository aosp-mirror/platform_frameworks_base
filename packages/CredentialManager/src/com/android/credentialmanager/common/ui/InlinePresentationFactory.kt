/*
* Copyright (C) 2024 The Android Open Source Project
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
import android.util.Size
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.UiVersions.Style
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.ContextCompat
import android.util.TypedValue
import android.graphics.Typeface


class InlinePresentationsFactory {
    companion object {
        private const val googleSansMediumFontFamily = "google-sans-medium"
        private const val googleSansTextFontFamily = "google-sans-text"
        // There is no min width required for now but this is needed for the spec builder
        private const val minInlineWidth = 5000


        fun modifyInlinePresentationSpec(context: Context,
                                         originalSpec: InlinePresentationSpec): InlinePresentationSpec {
            return InlinePresentationSpec.Builder(Size(originalSpec.minSize.width, originalSpec
                    .minSize.height),
                    Size(minInlineWidth, originalSpec
                            .maxSize.height))
                    .setStyle(UiVersions.newStylesBuilder().addStyle(getStyle(context)).build())
                    .build()
        }


        fun getStyle(context: Context): Style {
            val textColorPrimary = ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.text_primary)
            val textColorSecondary = ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.text_secondary)
            val textColorBackground = ContextCompat.getColor(context,
                    com.android.credentialmanager.R.color.inline_background)
            val chipHorizontalPadding = context.resources.getDimensionPixelSize(com.android
                    .credentialmanager.R.dimen.horizontal_chip_padding)
            val chipVerticalPadding = context.resources.getDimensionPixelSize(com.android
                    .credentialmanager.R.dimen.vertical_chip_padding)
            return InlineSuggestionUi.newStyleBuilder()
                    .setChipStyle(
                            ViewStyle.Builder().setPadding(chipHorizontalPadding,
                                    chipVerticalPadding,
                                    chipHorizontalPadding, chipVerticalPadding).build()
                    )
                    .setTitleStyle(
                            TextViewStyle.Builder().setTextColor(textColorPrimary).setTextSize
                            (TypedValue.COMPLEX_UNIT_DIP, 14F)
                                    .setTypeface(googleSansMediumFontFamily,
                                            Typeface.NORMAL).setBackgroundColor(textColorBackground)
                                    .build()
                    )
                    .setSubtitleStyle(TextViewStyle.Builder().setTextColor(textColorSecondary)
                            .setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12F).setTypeface
                            (googleSansTextFontFamily, Typeface.NORMAL).setBackgroundColor
                            (textColorBackground).build())
                    .build()
        }
    }
}