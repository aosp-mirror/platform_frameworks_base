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
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.android.settingslib.Utils
import com.android.systemui.biometrics.Utils.ellipsize
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlin.math.ceil

private const val TAG = "BiometricCustomizedViewBinder"

/** Sub-binder for Biometric Prompt Customized View */
object BiometricCustomizedViewBinder {
    const val MAX_DESCRIPTION_CHARACTER_NUMBER = 225

    fun bind(
        customizedViewContainer: LinearLayout,
        contentView: PromptContentView?,
        legacyCallback: Spaghetti.Callback
    ) {
        customizedViewContainer.repeatWhenAttached { containerView ->
            if (contentView == null) {
                containerView.visibility = View.GONE
                return@repeatWhenAttached
            }

            containerView.width { containerWidth ->
                if (containerWidth == 0) {
                    return@width
                }
                (containerView as LinearLayout).addView(
                    contentView.toView(containerView.context, containerWidth, legacyCallback),
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                )
                containerView.visibility = View.VISIBLE
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
        descriptionView.text =
            description.ellipsize(BiometricCustomizedViewBinder.MAX_DESCRIPTION_CHARACTER_NUMBER)
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
    context.resources
    val contentView =
        inflater.inflateContentView(
            R.layout.biometric_prompt_vertical_list_content_layout,
            description
        )
    val listItemsToShow = ArrayList<PromptContentItem>(listItems)
    // Show two column by default, once there is an item exceeding max lines, show single
    // item instead.
    val showTwoColumn =
        listItemsToShow.all { !it.doesExceedMaxLinesIfTwoColumn(context, containerViewWidth) }
    // If should show two columns and there are more than one items, make listItems always have odd
    // number items.
    if (showTwoColumn && listItemsToShow.size > 1 && listItemsToShow.size % 2 == 1) {
        listItemsToShow.add(dummyItem())
    }
    var currRow = createNewRowLayout(inflater)
    for (i in 0 until listItemsToShow.size) {
        val item = listItemsToShow[i]
        val itemView = item.toView(context, inflater)
        contentView.removeTopPaddingForFirstRow(description, itemView)

        // If there should be two column, and there is already one item in the current row, add
        // space between two items.
        if (showTwoColumn && currRow.childCount == 1) {
            currRow.addSpaceViewBetweenListItem()
        }
        currRow.addView(itemView)

        // If there should be one column, or there are already two items (plus the space view) in
        // the current row, or it's already the last item, start a new row
        if (!showTwoColumn || currRow.childCount == 3 || i == listItemsToShow.size - 1) {
            contentView.addView(currRow)
            currRow = createNewRowLayout(inflater)
        }
    }
    return contentView
}

private fun createNewRowLayout(inflater: LayoutInflater): LinearLayout {
    return inflater.inflate(R.layout.biometric_prompt_content_row_layout, null) as LinearLayout
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
            var maxWidth = containerViewWidth / 2 - contentViewPadding - listItemPadding
            // Reduce maxWidth a bit since paint#measureText is not accurate. See b/330909104 for
            // more context.
            maxWidth -= contentViewPadding / 2

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
    // Somehow xml layout params settings doesn't work, set it again here.
    val textView =
        inflater.inflate(R.layout.biometric_prompt_content_row_item_text_view, null) as TextView
    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    textView.layoutParams = lp
    val maxCharNumber = PromptVerticalListContentView.getMaxEachItemCharacterNumber()

    when (this) {
        is PromptContentItemPlainText -> {
            textView.text = text.ellipsize(maxCharNumber)
        }
        is PromptContentItemBulletedText -> {
            val bulletedText = SpannableString(text.ellipsize(maxCharNumber))
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

/* [contentView] function */
private fun LinearLayout.addSpaceViewBetweenListItem() =
    addView(
        Space(context),
        LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(
                R.dimen.biometric_prompt_content_space_width_between_items
            ),
            MATCH_PARENT
        )
    )

/* [contentView] function*/
private fun LinearLayout.removeTopPaddingForFirstRow(description: String?, itemView: TextView) {
    // If this item will be in the first row (contentView only has description view and
    // description is empty), remove top padding of this item.
    if (description.isNullOrEmpty() && childCount == 1) {
        itemView.setPadding(itemView.paddingLeft, 0, itemView.paddingRight, itemView.paddingBottom)
    }
}

private fun dummyItem(): PromptContentItem = PromptContentItemPlainText("")

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
