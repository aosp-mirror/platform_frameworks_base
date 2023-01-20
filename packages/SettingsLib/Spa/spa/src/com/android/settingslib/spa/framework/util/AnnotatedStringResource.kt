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

import android.content.res.Resources
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density

const val URLSPAN_TAG = "URLSPAN_TAG"

@Composable
fun annotatedStringResource(@StringRes id: Int, urlSpanColor: Color): AnnotatedString {
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    val density = LocalDensity.current
    return remember(id) {
        val text = resources.getText(id)
        spannableStringToAnnotatedString(text, density, urlSpanColor)
    }
}

private fun spannableStringToAnnotatedString(text: CharSequence, density: Density, urlSpanColor: Color): AnnotatedString {
    return if (text is Spanned) {
        with(density) {
            buildAnnotatedString {
                append((text.toString()))
                text.getSpans(0, text.length, Any::class.java).forEach {
                    val start = text.getSpanStart(it)
                    val end = text.getSpanEnd(it)
                    when (it) {
                        is StyleSpan ->
                            when (it.style) {
                                Typeface.NORMAL -> addStyle(
                                        SpanStyle(
                                                fontWeight = FontWeight.Normal,
                                                fontStyle = FontStyle.Normal
                                        ),
                                        start,
                                        end
                                )
                                Typeface.BOLD -> addStyle(
                                        SpanStyle(
                                                fontWeight = FontWeight.Bold,
                                                fontStyle = FontStyle.Normal
                                        ),
                                        start,
                                        end
                                )
                                Typeface.ITALIC -> addStyle(
                                        SpanStyle(
                                                fontWeight = FontWeight.Normal,
                                                fontStyle = FontStyle.Italic
                                        ),
                                        start,
                                        end
                                )
                                Typeface.BOLD_ITALIC -> addStyle(
                                        SpanStyle(
                                                fontWeight = FontWeight.Bold,
                                                fontStyle = FontStyle.Italic
                                        ),
                                        start,
                                        end
                                )
                            }
                        is URLSpan -> {
                            addStyle(
                                    SpanStyle(
                                            color = urlSpanColor,
                                    ),
                                    start,
                                    end
                            )
                            if (!it.url.isNullOrEmpty()) {
                                addStringAnnotation(
                                        URLSPAN_TAG,
                                        it.url,
                                        start,
                                        end
                                )
                            }
                        }
                        else -> addStyle(SpanStyle(), start, end)
                    }
                }
            }
        }
    } else {
        AnnotatedString(text.toString())
    }
}
