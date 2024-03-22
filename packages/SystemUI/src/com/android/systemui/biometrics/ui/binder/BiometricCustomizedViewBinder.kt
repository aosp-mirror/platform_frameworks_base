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
import android.hardware.biometrics.PromptContentItem
import android.hardware.biometrics.PromptContentItemBulletedText
import android.hardware.biometrics.PromptContentItemPlainText
import android.hardware.biometrics.PromptContentView
import android.hardware.biometrics.PromptContentViewWithMoreOptionsButton
import android.hardware.biometrics.PromptVerticalListContentView
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BulletSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.android.settingslib.Utils
import com.android.systemui.biometrics.ui.BiometricPromptLayout
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlin.math.ceil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Sub-binder for [BiometricPromptLayout.customized_view_container]. */
object BiometricCustomizedViewBinder {
    fun bind(
        customizedViewContainer: LinearLayout,
        viewModel: PromptViewModel,
        legacyCallback: Spaghetti.Callback
    ) {
        customizedViewContainer.repeatWhenAttached { containerView ->
            lifecycleScope.launch {
                val contentView: PromptContentView? = viewModel.contentView.first()
                if (contentView == null) {
                    containerView.visibility = View.GONE
                    return@launch
                }

                containerView.width { containerWidth ->
                    if (containerWidth == 0) {
                        return@width
                    }
                    (containerView as LinearLayout).addView(
                        contentView.toView(containerView.context, containerWidth, legacyCallback)
                    )
                    containerView.visibility = View.VISIBLE
                }
            }
        }
    }
}

private fun PromptContentView.toView(
    context: Context,
    containerViewWidth: Int,
    legacyCallback: Spaghetti.Callback
): View {
    return when (this) {
        is PromptVerticalListContentView -> initLayout(context, containerViewWidth)
        is PromptContentViewWithMoreOptionsButton -> initLayout(context, legacyCallback)
        else -> {
            throw IllegalStateException("No such PromptContentView: $this")
        }
    }
}

private fun LayoutInflater.inflateContentView(id: Int, description: String?): LinearLayout {
    val contentView = inflate(id, null) as LinearLayout

    val descriptionView = contentView.requireViewById<TextView>(R.id.customized_view_description)
    if (!description.isNullOrEmpty()) {
        descriptionView.text = description
    } else {
        descriptionView.visibility = View.GONE
    }
    return contentView
}

private fun PromptContentViewWithMoreOptionsButton.initLayout(
    context: Context,
    legacyCallback: Spaghetti.Callback
): View {
    val inflater = LayoutInflater.from(context)
    val contentView =
        inflater.inflateContentView(
            R.layout.biometric_prompt_content_with_button_layout,
            description
        )
    val buttonView = contentView.requireViewById<Button>(R.id.customized_view_more_options_button)
    buttonView.setOnClickListener { legacyCallback.onContentViewMoreOptionsButtonPressed() }
    return contentView
}

private fun PromptVerticalListContentView.initLayout(
    context: Context,
    containerViewWidth: Int
): View {
    val inflater = LayoutInflater.from(context)
    val resources = context.resources
    val contentView =
        inflater.inflateContentView(
            R.layout.biometric_prompt_vertical_list_content_layout,
            description
        )
    // Show two column by default, once there is an item exceeding max lines, show single
    // item instead.
    val showTwoColumn =
        listItems.all { !it.doesExceedMaxLinesIfTwoColumn(context, containerViewWidth) }
    var currRowView = createNewRowLayout(inflater)
    for (item in listItems) {
        val itemView = item.toView(context, inflater)
        // If this item will be in the first row (contentView only has description view) and
        // description is empty, remove top padding of this item.
        if (contentView.childCount == 1 && description.isNullOrEmpty()) {
            itemView.setPadding(
                itemView.paddingLeft,
                0,
                itemView.paddingRight,
                itemView.paddingBottom
            )
        }
        currRowView.addView(itemView)

        // If this is the first item in the current row, add space behind it.
        if (currRowView.childCount == 1 && showTwoColumn) {
            currRowView.addSpaceView(
                resources.getDimensionPixelSize(
                    R.dimen.biometric_prompt_content_space_width_between_items
                ),
                MATCH_PARENT
            )
        }

        // If there are already two items (plus the space view) in the current row, or it
        // should be one column, start a new row
        if (currRowView.childCount == 3 || !showTwoColumn) {
            contentView.addView(currRowView)
            currRowView = createNewRowLayout(inflater)
        }
    }
    if (currRowView.childCount > 0) {
        contentView.addView(currRowView)
    }
    return contentView
}

private fun createNewRowLayout(inflater: LayoutInflater): LinearLayout {
    return inflater.inflate(R.layout.biometric_prompt_content_row_layout, null) as LinearLayout
}

private fun LinearLayout.addSpaceView(width: Int, height: Int) {
    addView(Space(context), LinearLayout.LayoutParams(width, height))
}

private fun PromptContentItem.doesExceedMaxLinesIfTwoColumn(
    context: Context,
    containerViewWidth: Int,
): Boolean {
    val resources = context.resources
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
            val contentViewPadding =
                resources.getDimensionPixelSize(R.dimen.biometric_prompt_content_padding_horizontal)
            val listItemPadding = getListItemPadding(resources)
            val maxWidth = containerViewWidth / 2 - contentViewPadding - listItemPadding

            val paint = TextPaint()
            val attributes =
                context.obtainStyledAttributes(
                    R.style.TextAppearance_AuthCredential_ContentViewListItem,
                    intArrayOf(android.R.attr.textSize)
                )
            paint.textSize = attributes.getDimensionPixelSize(0, 0).toFloat()
            val textWidth = paint.measureText(passedInText)
            attributes.recycle()

            val maxLines =
                resources.getInteger(
                    R.integer.biometric_prompt_content_list_item_max_lines_if_two_column
                )
            val numLines = ceil(textWidth / maxWidth).toInt()
            return numLines > maxLines
        }
        else -> {
            throw IllegalStateException("No such PromptContentItem: $this")
        }
    }
}

private fun PromptContentItem.toView(
    context: Context,
    inflater: LayoutInflater,
): TextView {
    val resources = context.resources
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
                    getListItemBulletColor(context),
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
            R.dimen.biometric_prompt_content_space_width_between_items
        ) / 2
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

private fun getListItemBulletColor(context: Context): Int =
    Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.materialColorOnSurface)

private fun <T : View> T.width(function: (Int) -> Unit) {
    if (width == 0)
        viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (measuredWidth > 0) {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                    function(measuredWidth)
                }
            }
        )
    else function(measuredWidth)
}
