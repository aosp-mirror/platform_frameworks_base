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

package com.android.settingslib.spa.framework.util

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

const val URL_SPAN_TAG = "URL_SPAN_TAG"

@Composable
fun annotatedStringResource(@StringRes id: Int): AnnotatedString {
    val resources = LocalContext.current.resources
    val urlSpanColor = MaterialTheme.colorScheme.primary
    return remember(id) {
        val text = resources.getText(id)
        spannableStringToAnnotatedString(text, urlSpanColor)
    }
}

private fun spannableStringToAnnotatedString(text: CharSequence, urlSpanColor: Color) =
    if (text is Spanned) {
        buildAnnotatedString {
            append((text.toString()))
            for (span in text.getSpans(0, text.length, Any::class.java)) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                when (span) {
                    is StyleSpan -> addStyleSpan(span, start, end)
                    is URLSpan -> addUrlSpan(span, urlSpanColor, start, end)
                    else -> addStyle(SpanStyle(), start, end)
                }
            }
        }
    } else {
        AnnotatedString(text.toString())
    }

private fun AnnotatedString.Builder.addStyleSpan(styleSpan: StyleSpan, start: Int, end: Int) {
    when (styleSpan.style) {
        Typeface.NORMAL -> addStyle(
            SpanStyle(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal),
            start,
            end,
        )

        Typeface.BOLD -> addStyle(
            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal),
            start,
            end,
        )

        Typeface.ITALIC -> addStyle(
            SpanStyle(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Italic),
            start,
            end,
        )

        Typeface.BOLD_ITALIC -> addStyle(
            SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
            start,
            end,
        )
    }
}

private fun AnnotatedString.Builder.addUrlSpan(
    urlSpan: URLSpan,
    urlSpanColor: Color,
    start: Int,
    end: Int,
) {
    addStyle(
        SpanStyle(color = urlSpanColor, textDecoration = TextDecoration.Underline),
        start,
        end,
    )
    if (!urlSpan.url.isNullOrEmpty()) {
        addStringAnnotation(URL_SPAN_TAG, urlSpan.url, start, end)
    }
}
