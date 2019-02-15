/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.statusbar.policy.KeyguardMonitor

class OngoingPrivacyChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttrs: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val iconMarginExpanded = context.resources.getDimensionPixelSize(
                    R.dimen.ongoing_appops_chip_icon_margin_expanded)
    private val iconMarginCollapsed = context.resources.getDimensionPixelSize(
                    R.dimen.ongoing_appops_chip_icon_margin_collapsed)
    private val iconSize =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_icon_size)
    private val iconColor = context.resources.getColor(
            R.color.status_bar_clock_color, context.theme)
    private val sidePadding =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_appops_chip_side_padding)
    private val backgroundDrawable = context.getDrawable(R.drawable.privacy_chip_bg)
    private lateinit var text: TextView
    private lateinit var iconsContainer: LinearLayout
    private lateinit var back: LinearLayout
    var expanded = false
        set(value) {
            if (value != field) {
                field = value
                updateView()
            }
        }
    @Suppress("DEPRECATION")
    private val keyguardMonitor = Dependency.get(KeyguardMonitor::class.java)
    var builder = PrivacyDialogBuilder(context, emptyList<PrivacyItem>())
    var privacyList = emptyList<PrivacyItem>()
        set(value) {
            field = value
            builder = PrivacyDialogBuilder(context, value)
            updateView()
        }

    override fun onFinishInflate() {
        super.onFinishInflate()

        back = findViewById(R.id.background)
        text = findViewById(R.id.text_container)
        iconsContainer = findViewById(R.id.icons_container)
    }

    // Should only be called if the builder icons or app changed
    private fun updateView() {
        back.background = if (expanded) backgroundDrawable else null
        back.setPaddingRelative(0, 0, if (expanded) sidePadding else 0, 0)
        fun setIcons(dialogBuilder: PrivacyDialogBuilder, iconsContainer: ViewGroup) {
            iconsContainer.removeAllViews()
            dialogBuilder.generateIcons().forEachIndexed { i, it ->
                it.mutate()
                it.setTint(iconColor)
                val image = ImageView(context).apply {
                    setImageDrawable(it)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                iconsContainer.addView(image, iconSize, iconSize)
                if (i != 0) {
                    val lp = image.layoutParams as MarginLayoutParams
                    lp.marginStart = if (expanded) iconMarginExpanded else iconMarginCollapsed
                    image.layoutParams = lp
                }
            }
        }

        if (!privacyList.isEmpty()) {
            generateContentDescription()
            setIcons(builder, iconsContainer)
            setApplicationText()
        } else {
            text.visibility = GONE
            iconsContainer.removeAllViews()
        }
        requestLayout()
    }

    private fun setApplicationText() {
        text.visibility = if (builder.types.size == 1 && expanded) VISIBLE else GONE
        if (builder.types.size == 1 && expanded) {
            if (builder.app != null && !amISecure()) {
                text.setText(builder.app?.applicationName)
            } else {
                text.text = context.resources.getQuantityString(
                        R.plurals.ongoing_privacy_chip_multiple_apps,
                        builder.appsAndTypes.size, builder.appsAndTypes.size)
            }
        }
    }

    private fun amISecure() = keyguardMonitor.isShowing && keyguardMonitor.isSecure

    private fun generateContentDescription() {
        val typesText = builder.joinTypes()
        if (builder.types.size > 1) {
            contentDescription = context.getString(
                    R.string.ongoing_privacy_chip_content_multiple_apps, typesText)
        } else {
            if (builder.app != null && !amISecure()) {
                contentDescription =
                        context.getString(R.string.ongoing_privacy_chip_content_single_app,
                                builder.app?.applicationName, typesText)
            } else {
                contentDescription = context.resources.getQuantityString(
                        R.plurals.ongoing_privacy_chip_content_multiple_apps_single_op,
                        builder.appsAndTypes.size, builder.appsAndTypes.size, typesText)
            }
        }
    }
}