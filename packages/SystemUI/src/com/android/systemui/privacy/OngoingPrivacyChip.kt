/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.privacy

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.android.settingslib.Utils
import com.android.systemui.R

class OngoingPrivacyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var iconMargin = 0
    private var iconSize = 0
    private var iconColor = 0

    private lateinit var iconsContainer: LinearLayout

    var privacyList = emptyList<PrivacyItem>()
        set(value) {
            field = value
            updateView(PrivacyChipBuilder(context, field))
        }

    override fun onFinishInflate() {
        super.onFinishInflate()

        iconsContainer = requireViewById(R.id.icons_container)

        updateResources()
    }

    // Should only be called if the builder icons or app changed
    private fun updateView(builder: PrivacyChipBuilder) {
        fun setIcons(chipBuilder: PrivacyChipBuilder, iconsContainer: ViewGroup) {
            iconsContainer.removeAllViews()
            chipBuilder.generateIcons().forEachIndexed { i, it ->
                it.mutate()
                it.setTint(iconColor)
                val image = ImageView(context).apply {
                    setImageDrawable(it)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                iconsContainer.addView(image, iconSize, iconSize)
                if (i != 0) {
                    val lp = image.layoutParams as MarginLayoutParams
                    lp.marginStart = iconMargin
                    image.layoutParams = lp
                }
            }
        }

        if (!privacyList.isEmpty()) {
            generateContentDescription(builder)
            setIcons(builder, iconsContainer)
        } else {
            iconsContainer.removeAllViews()
        }
        requestLayout()
    }

    private fun generateContentDescription(builder: PrivacyChipBuilder) {
        val typesText = builder.joinTypes()
        contentDescription = context.getString(
                R.string.ongoing_privacy_chip_content_multiple_apps, typesText)
    }

    private fun updateResources() {
        iconMargin = context.resources
                .getDimensionPixelSize(R.dimen.ongoing_appops_chip_icon_margin)
        iconSize = context.resources
                .getDimensionPixelSize(R.dimen.ongoing_appops_chip_icon_size)
        iconColor =
                Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorPrimary)

        val padding = context.resources
                .getDimensionPixelSize(R.dimen.ongoing_appops_chip_side_padding)
        iconsContainer.setPaddingRelative(padding, 0, padding, 0)
        iconsContainer.background = context.getDrawable(R.drawable.privacy_chip_bg)
    }
}