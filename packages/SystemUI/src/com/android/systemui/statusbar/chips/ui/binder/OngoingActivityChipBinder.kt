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

package com.android.systemui.statusbar.chips.ui.binder

import android.annotation.IdRes
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.ron.shared.StatusBarRonChips
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.view.ChipChronometer

/** Binder for ongoing activity chip views. */
object OngoingActivityChipBinder {
    /** Binds the given [chipModel] data to the given [chipView]. */
    fun bind(chipModel: OngoingActivityChipModel, chipView: View) {
        val chipContext = chipView.context
        val chipDefaultIconView: ImageView =
            chipView.requireViewById(R.id.ongoing_activity_chip_icon)
        val chipTimeView: ChipChronometer =
            chipView.requireViewById(R.id.ongoing_activity_chip_time)
        val chipTextView: TextView = chipView.requireViewById(R.id.ongoing_activity_chip_text)
        val chipBackgroundView: ChipBackgroundContainer =
            chipView.requireViewById(R.id.ongoing_activity_chip_background)

        when (chipModel) {
            is OngoingActivityChipModel.Shown -> {
                // Data
                setChipIcon(chipModel, chipBackgroundView, chipDefaultIconView)
                setChipMainContent(chipModel, chipTextView, chipTimeView)
                chipView.setOnClickListener(chipModel.onClickListener)
                updateChipPadding(
                    chipModel,
                    chipBackgroundView,
                    chipTextView,
                    chipTimeView,
                )

                // Accessibility
                setChipAccessibility(chipModel, chipView, chipBackgroundView)

                // Colors
                val textColor = chipModel.colors.text(chipContext)
                chipTimeView.setTextColor(textColor)
                chipTextView.setTextColor(textColor)
                (chipBackgroundView.background as GradientDrawable).color =
                    chipModel.colors.background(chipContext)
            }
            is OngoingActivityChipModel.Hidden -> {
                // The Chronometer should be stopped to prevent leaks -- see b/192243808 and
                // [Chronometer.start].
                chipTimeView.stop()
            }
        }
    }

    private fun setChipIcon(
        chipModel: OngoingActivityChipModel.Shown,
        backgroundView: ChipBackgroundContainer,
        defaultIconView: ImageView,
    ) {
        // Always remove any previously set custom icon. If we have a new custom icon, we'll re-add
        // it.
        backgroundView.removeView(backgroundView.getCustomIconView())

        val iconTint = chipModel.colors.text(defaultIconView.context)

        when (val icon = chipModel.icon) {
            null -> {
                defaultIconView.visibility = View.GONE
            }
            is OngoingActivityChipModel.ChipIcon.SingleColorIcon -> {
                IconViewBinder.bind(icon.impl, defaultIconView)
                defaultIconView.visibility = View.VISIBLE
                defaultIconView.tintView(iconTint)
            }
            is OngoingActivityChipModel.ChipIcon.FullColorAppIcon -> {
                StatusBarRonChips.assertInNewMode()
                IconViewBinder.bind(icon.impl, defaultIconView)
                defaultIconView.visibility = View.VISIBLE
                defaultIconView.untintView()
            }
            is OngoingActivityChipModel.ChipIcon.StatusBarView -> {
                // Hide the default icon since we'll show this custom icon instead.
                defaultIconView.visibility = View.GONE

                // Add the new custom icon:
                // 1. Set up the right visual params.
                val iconView = icon.impl
                with(iconView) {
                    id = CUSTOM_ICON_VIEW_ID
                    // TODO(b/354930838): Update the content description to not include "phone" and
                    // maybe include the app name.
                    contentDescription =
                        context.resources.getString(R.string.ongoing_phone_call_content_description)
                    tintView(iconTint)
                }

                // 2. If we just reinflated the view, we may need to detach the icon view from the
                // old chip before we reattach it to the new one.
                // See also: NotificationIconContainerViewBinder#bindIcons.
                val currentParent = iconView.parent as? ViewGroup
                if (currentParent != null && currentParent != backgroundView) {
                    currentParent.removeView(iconView)
                    currentParent.removeTransientView(iconView)
                }

                // 3: Add the icon as the starting view.
                backgroundView.addView(
                    iconView,
                    /* index= */ 0,
                    generateCustomIconLayoutParams(iconView),
                )
            }
        }
    }

    private fun View.getCustomIconView(): StatusBarIconView? {
        return this.findViewById(CUSTOM_ICON_VIEW_ID)
    }

    private fun ImageView.tintView(color: Int) {
        this.imageTintList = ColorStateList.valueOf(color)
    }

    private fun ImageView.untintView() {
        this.imageTintList = null
    }

    private fun generateCustomIconLayoutParams(iconView: ImageView): FrameLayout.LayoutParams {
        val customIconSize =
            iconView.context.resources.getDimensionPixelSize(
                R.dimen.ongoing_activity_chip_embedded_padding_icon_size
            )
        return FrameLayout.LayoutParams(customIconSize, customIconSize)
    }

    private fun setChipMainContent(
        chipModel: OngoingActivityChipModel.Shown,
        chipTextView: TextView,
        chipTimeView: ChipChronometer,
    ) {
        when (chipModel) {
            is OngoingActivityChipModel.Shown.Countdown -> {
                chipTextView.text = chipModel.secondsUntilStarted.toString()
                chipTextView.visibility = View.VISIBLE

                chipTimeView.hide()
            }
            is OngoingActivityChipModel.Shown.Text -> {
                chipTextView.text = chipModel.text
                chipTextView.visibility = View.VISIBLE

                chipTimeView.hide()
            }
            is OngoingActivityChipModel.Shown.Timer -> {
                ChipChronometerBinder.bind(chipModel.startTimeMs, chipTimeView)
                chipTimeView.visibility = View.VISIBLE

                chipTextView.visibility = View.GONE
            }
            is OngoingActivityChipModel.Shown.IconOnly -> {
                chipTextView.visibility = View.GONE
                chipTimeView.hide()
            }
        }
    }

    private fun ChipChronometer.hide() {
        // The Chronometer should be stopped to prevent leaks -- see b/192243808 and
        // [Chronometer.start].
        this.stop()
        this.visibility = View.GONE
    }

    private fun updateChipPadding(
        chipModel: OngoingActivityChipModel.Shown,
        backgroundView: View,
        chipTextView: TextView,
        chipTimeView: ChipChronometer,
    ) {
        if (chipModel.icon != null) {
            if (chipModel.icon is OngoingActivityChipModel.ChipIcon.StatusBarView) {
                // If the icon is a custom [StatusBarIconView], then it should've come from
                // `Notification.smallIcon`, which is required to embed its own paddings. We need to
                // adjust the other paddings to make everything look good :)
                backgroundView.setBackgroundPaddingForEmbeddedPaddingIcon()
                chipTextView.setTextPaddingForEmbeddedPaddingIcon()
                chipTimeView.setTextPaddingForEmbeddedPaddingIcon()
            } else {
                backgroundView.setBackgroundPaddingForNormalIcon()
                chipTextView.setTextPaddingForNormalIcon()
                chipTimeView.setTextPaddingForNormalIcon()
            }
        } else {
            backgroundView.setBackgroundPaddingForNoIcon()
            chipTextView.setTextPaddingForNoIcon()
            chipTimeView.setTextPaddingForNoIcon()
        }
    }

    private fun View.setTextPaddingForEmbeddedPaddingIcon() {
        val newPaddingEnd =
            context.resources.getDimensionPixelSize(
                R.dimen.ongoing_activity_chip_text_end_padding_for_embedded_padding_icon
            )
        setPaddingRelative(
            // The icon should embed enough padding between the icon and time view.
            /* start= */ 0,
            this.paddingTop,
            newPaddingEnd,
            this.paddingBottom,
        )
    }

    private fun View.setTextPaddingForNormalIcon() {
        this.setPaddingRelative(
            this.context.resources.getDimensionPixelSize(
                R.dimen.ongoing_activity_chip_icon_text_padding
            ),
            paddingTop,
            // The background view will contain the right end padding.
            /* end= */ 0,
            paddingBottom,
        )
    }

    private fun View.setTextPaddingForNoIcon() {
        // The background view will have even start & end paddings, so we don't want the text view
        // to add any additional padding.
        this.setPaddingRelative(/* start= */ 0, paddingTop, /* end= */ 0, paddingBottom)
    }

    private fun View.setBackgroundPaddingForEmbeddedPaddingIcon() {
        val sidePadding =
            context.resources.getDimensionPixelSize(
                R.dimen.ongoing_activity_chip_side_padding_for_embedded_padding_icon
            )
        setPaddingRelative(
            sidePadding,
            paddingTop,
            sidePadding,
            paddingBottom,
        )
    }

    private fun View.setBackgroundPaddingForNormalIcon() {
        val sidePadding =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_activity_chip_side_padding)
        setPaddingRelative(
            sidePadding,
            paddingTop,
            sidePadding,
            paddingBottom,
        )
    }

    private fun View.setBackgroundPaddingForNoIcon() {
        // The padding for the normal icon is also appropriate for no icon.
        setBackgroundPaddingForNormalIcon()
    }

    private fun setChipAccessibility(
        chipModel: OngoingActivityChipModel.Shown,
        chipView: View,
        chipBackgroundView: View,
    ) {
        when (chipModel) {
            is OngoingActivityChipModel.Shown.Countdown -> {
                // Set as assertive so talkback will announce the countdown
                chipView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            }
            is OngoingActivityChipModel.Shown.Timer,
            is OngoingActivityChipModel.Shown.Text,
            is OngoingActivityChipModel.Shown.IconOnly -> {
                chipView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            }
        }
        // Clickable chips need to be a minimum size for accessibility purposes, but let
        // non-clickable chips be smaller.
        if (chipModel.onClickListener != null) {
            chipBackgroundView.minimumWidth =
                chipBackgroundView.context.resources.getDimensionPixelSize(
                    R.dimen.min_clickable_item_size
                )
        } else {
            chipBackgroundView.minimumWidth = 0
        }
    }

    @IdRes private val CUSTOM_ICON_VIEW_ID = R.id.ongoing_activity_chip_custom_icon
}
