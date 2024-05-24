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

package com.android.systemui.biometrics.ui.binder

import android.content.Context
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Paint
import android.hardware.biometrics.PromptContentItem
import android.hardware.biometrics.PromptContentItemBulletedText
import android.hardware.biometrics.PromptContentItemPlainText
import android.hardware.biometrics.PromptContentView
import android.hardware.biometrics.PromptVerticalListContentView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BulletSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.biometrics.ui.BiometricPromptLayout
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Sub-binder for [BiometricPromptLayout.customized_view_container]. */
object BiometricCustomizedViewBinder {
    fun bind(customizedViewContainer: LinearLayout, spaceAbove: Space, viewModel: PromptViewModel) {
        customizedViewContainer.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    val contentView: PromptContentView? = viewModel.contentView.first()

                    if (contentView != null) {
                        val context = customizedViewContainer.context
                        customizedViewContainer.addView(contentView.toView(context))
                        customizedViewContainer.visibility = View.VISIBLE
                        spaceAbove.visibility = View.VISIBLE
                    } else {
                        customizedViewContainer.visibility = View.GONE
                        spaceAbove.visibility = View.GONE
                    }
                }
            }
        }
    }
}

private fun PromptContentView.toView(context: Context): View {
    val resources = context.resources
    val inflater = LayoutInflater.from(context)
    when (this) {
        is PromptVerticalListContentView -> {
            val contentView =
                inflater.inflate(R.layout.biometric_prompt_content_layout, null) as LinearLayout

            val descriptionView = contentView.requireViewById<TextView>(R.id.customized_view_title)
            if (!description.isNullOrEmpty()) {
                descriptionView.text = description
            } else {
                descriptionView.visibility = View.GONE
            }

            // Show two column by default, once there is an item exceeding max lines, show single
            // item instead.
            val showTwoColumn = listItems.all { !it.doesExceedMaxLinesIfTwoColumn(resources) }
            var currRowView = createNewRowLayout(inflater)
            for (item in listItems) {
                val itemView = item.toView(resources, inflater, context.theme)
                currRowView.addView(itemView)

                if (!showTwoColumn || currRowView.childCount == 2) {
                    contentView.addView(currRowView)
                    currRowView = createNewRowLayout(inflater)
                }
            }
            if (currRowView.childCount > 0) {
                contentView.addView(currRowView)
            }

            return contentView
        }
        else -> {
            throw IllegalStateException("No such PromptContentView: $this")
        }
    }
}

private fun createNewRowLayout(inflater: LayoutInflater): LinearLayout {
    return inflater.inflate(R.layout.biometric_prompt_content_row_layout, null) as LinearLayout
}

private fun PromptContentItem.doesExceedMaxLinesIfTwoColumn(
    resources: Resources,
): Boolean {
    val passedInText: String =
        when (this) {
            is PromptContentItemPlainText -> text
            is PromptContentItemBulletedText -> text
            else -> {
                throw IllegalStateException("No such PromptContentItem: $this")
            }
        }

    when (this) {
        is PromptContentItemPlainText,
        is PromptContentItemBulletedText -> {
            val dialogMargin =
                resources.getDimensionPixelSize(R.dimen.biometric_dialog_border_padding)
            val halfDialogWidth =
                Resources.getSystem().displayMetrics.widthPixels / 2 - dialogMargin
            val containerPadding =
                resources.getDimensionPixelSize(
                    R.dimen.biometric_prompt_content_container_padding_horizontal
                )
            val contentPadding =
                resources.getDimensionPixelSize(R.dimen.biometric_prompt_content_padding_horizontal)
            val listItemPadding = getListItemPadding(resources)
            val maxWidth = halfDialogWidth - containerPadding - contentPadding - listItemPadding

            val text = "$passedInText"
            val textSize =
                resources.getDimensionPixelSize(
                    R.dimen.biometric_prompt_content_list_item_text_size
                )
            val paint = Paint()
            paint.textSize = textSize.toFloat()

            val maxLines =
                resources.getInteger(
                    R.integer.biometric_prompt_content_list_item_max_lines_if_two_column
                )
            val numLines = ceil(paint.measureText(text).toDouble() / maxWidth).toInt()
            return numLines > maxLines
        }
        else -> {
            throw IllegalStateException("No such PromptContentItem: $this")
        }
    }
}

private fun PromptContentItem.toView(
    resources: Resources,
    inflater: LayoutInflater,
    theme: Theme,
): TextView {
    val textView =
        inflater.inflate(R.layout.biometric_prompt_content_row_item_text_view, null) as TextView
    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    textView.layoutParams = lp

    when (this) {
        is PromptContentItemPlainText -> {
            textView.text = text
        }
        is PromptContentItemBulletedText -> {
            val bulletedText = SpannableString(text)
            val span =
                BulletSpan(
                    getListItemBulletGapWidth(resources),
                    getListItemBulletColor(resources, theme),
                    getListItemBulletRadius(resources)
                )
            bulletedText.setSpan(span, 0 /* start */, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            textView.text = bulletedText
        }
        else -> {
            throw IllegalStateException("No such PromptContentItem: $this")
        }
    }
    return textView
}

private fun PromptContentItem.getListItemPadding(resources: Resources): Int {
    var listItemPadding =
        resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_content_list_item_padding_horizontal
        ) * 2
    when (this) {
        is PromptContentItemPlainText -> {}
        is PromptContentItemBulletedText -> {
            listItemPadding +=
                getListItemBulletRadius(resources) * 2 + getListItemBulletGapWidth(resources)
        }
        else -> {
            throw IllegalStateException("No such PromptContentItem: $this")
        }
    }
    return listItemPadding
}

private fun getListItemBulletRadius(resources: Resources): Int =
    resources.getDimensionPixelSize(R.dimen.biometric_prompt_content_list_item_bullet_radius)

private fun getListItemBulletGapWidth(resources: Resources): Int =
    resources.getDimensionPixelSize(R.dimen.biometric_prompt_content_list_item_bullet_gap_width)

private fun getListItemBulletColor(resources: Resources, theme: Theme): Int =
    resources.getColor(R.color.biometric_prompt_content_list_item_bullet_color, theme)
