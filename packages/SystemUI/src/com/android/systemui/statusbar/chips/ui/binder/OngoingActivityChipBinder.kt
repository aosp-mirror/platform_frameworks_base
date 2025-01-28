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
import android.widget.DateTimeView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.UiThread
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.binder.ContentDescriptionViewBinder
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.view.ChipChronometer
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder.IconViewStore

/** Binder for ongoing activity chip views. */
object OngoingActivityChipBinder {
    /** Binds the given [chipModel] data to the given [chipView]. */
    fun bind(
        chipModel: OngoingActivityChipModel,
        viewBinding: OngoingActivityChipViewBinding,
        iconViewStore: IconViewStore?,
    ) {
        val chipContext = viewBinding.rootView.context
        val chipDefaultIconView = viewBinding.defaultIconView
        val chipTimeView = viewBinding.timeView
        val chipTextView = viewBinding.textView
        val chipShortTimeDeltaView = viewBinding.shortTimeDeltaView
        val chipBackgroundView = viewBinding.backgroundView

        when (chipModel) {
            is OngoingActivityChipModel.Shown -> {
                // Data
                setChipIcon(chipModel, chipBackgroundView, chipDefaultIconView, iconViewStore)
                setChipMainContent(chipModel, chipTextView, chipTimeView, chipShortTimeDeltaView)

                viewBinding.rootView.setOnClickListener(chipModel.onClickListenerLegacy)
                updateChipPadding(
                    chipModel,
                    chipBackgroundView,
                    chipTextView,
                    chipTimeView,
                    chipShortTimeDeltaView,
                )

                // Accessibility
                setChipAccessibility(chipModel, viewBinding.rootView, chipBackgroundView)

                // Colors
                val textColor = chipModel.colors.text(chipContext)
                chipTimeView.setTextColor(textColor)
                chipTextView.setTextColor(textColor)
                chipShortTimeDeltaView.setTextColor(textColor)
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

    /** Stores [rootView] and relevant child views in an object for easy reference. */
    fun createBinding(rootView: View): OngoingActivityChipViewBinding {
        return OngoingActivityChipViewBinding(
            rootView = rootView,
            timeView = rootView.requireViewById(R.id.ongoing_activity_chip_time),
            textView = rootView.requireViewById(R.id.ongoing_activity_chip_text),
            shortTimeDeltaView =
                rootView.requireViewById(R.id.ongoing_activity_chip_short_time_delta),
            defaultIconView = rootView.requireViewById(R.id.ongoing_activity_chip_icon),
            backgroundView = rootView.requireViewById(R.id.ongoing_activity_chip_background),
        )
    }

    /**
     * Resets any width restrictions that were placed on the primary chip's contents.
     *
     * Should be used when the user's screen bounds changed because there may now be more room in
     * the status bar to show additional content.
     */
    fun resetPrimaryChipWidthRestrictions(
        primaryChipViewBinding: OngoingActivityChipViewBinding,
        currentPrimaryChipViewModel: OngoingActivityChipModel,
    ) {
        if (currentPrimaryChipViewModel is OngoingActivityChipModel.Hidden) {
            return
        }
        resetChipMainContentWidthRestrictions(
            primaryChipViewBinding,
            currentPrimaryChipViewModel as OngoingActivityChipModel.Shown,
        )
    }

    /**
     * Resets any width restrictions that were placed on the secondary chip and its contents.
     *
     * Should be used when the user's screen bounds changed because there may now be more room in
     * the status bar to show additional content.
     */
    fun resetSecondaryChipWidthRestrictions(
        secondaryChipViewBinding: OngoingActivityChipViewBinding,
        currentSecondaryChipModel: OngoingActivityChipModel,
    ) {
        if (currentSecondaryChipModel is OngoingActivityChipModel.Hidden) {
            return
        }
        secondaryChipViewBinding.rootView.resetWidthRestriction()
        resetChipMainContentWidthRestrictions(
            secondaryChipViewBinding,
            currentSecondaryChipModel as OngoingActivityChipModel.Shown,
        )
    }

    private fun resetChipMainContentWidthRestrictions(
        viewBinding: OngoingActivityChipViewBinding,
        model: OngoingActivityChipModel.Shown,
    ) {
        when (model) {
            is OngoingActivityChipModel.Shown.Text -> viewBinding.textView.resetWidthRestriction()
            is OngoingActivityChipModel.Shown.Timer -> viewBinding.timeView.resetWidthRestriction()
            is OngoingActivityChipModel.Shown.ShortTimeDelta ->
                viewBinding.shortTimeDeltaView.resetWidthRestriction()
            is OngoingActivityChipModel.Shown.IconOnly,
            is OngoingActivityChipModel.Shown.Countdown -> {}
        }
    }

    /**
     * Resets any width restrictions that were placed on the given view.
     *
     * Should be used when the user's screen bounds changed because there may now be more room in
     * the status bar to show additional content.
     */
    @UiThread
    fun View.resetWidthRestriction() {
        // View needs to be visible in order to be re-measured
        visibility = View.VISIBLE
        forceLayout()
    }

    private fun setChipIcon(
        chipModel: OngoingActivityChipModel.Shown,
        backgroundView: ChipBackgroundContainer,
        defaultIconView: ImageView,
        iconViewStore: IconViewStore?,
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
            is OngoingActivityChipModel.ChipIcon.StatusBarView -> {
                StatusBarConnectedDisplays.assertInLegacyMode()
                setStatusBarIconView(
                    defaultIconView,
                    icon.impl,
                    icon.contentDescription,
                    iconTint,
                    backgroundView,
                )
            }
            is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon -> {
                StatusBarConnectedDisplays.assertInNewMode()
                val iconView = fetchStatusBarIconView(iconViewStore, icon)
                if (iconView == null) {
                    // This means that the notification key doesn't exist anymore.
                    return
                }
                setStatusBarIconView(
                    defaultIconView,
                    iconView,
                    icon.contentDescription,
                    iconTint,
                    backgroundView,
                )
            }
        }
    }

    private fun fetchStatusBarIconView(
        iconViewStore: IconViewStore?,
        icon: OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon,
    ): StatusBarIconView? {
        StatusBarConnectedDisplays.assertInNewMode()
        if (iconViewStore == null) {
            throw IllegalStateException("Store should always be non-null when flag is enabled.")
        }
        return iconViewStore.iconView(icon.notificationKey)
    }

    private fun setStatusBarIconView(
        defaultIconView: ImageView,
        iconView: StatusBarIconView,
        iconContentDescription: ContentDescription,
        iconTint: Int,
        backgroundView: ChipBackgroundContainer,
    ) {
        // Hide the default icon since we'll show this custom icon instead.
        defaultIconView.visibility = View.GONE

        // 1. Set up the right visual params.
        with(iconView) {
            id = CUSTOM_ICON_VIEW_ID
            if (StatusBarNotifChips.isEnabled) {
                ContentDescriptionViewBinder.bind(iconContentDescription, this)
            } else {
                contentDescription =
                    context.resources.getString(R.string.ongoing_call_content_description)
            }
            tintView(iconTint)
        }

        // 2. If we just reinflated the view, we may need to detach the icon view from the old chip
        // before we reattach it to the new one.
        // See also: NotificationIconContainerViewBinder#bindIcons.
        val currentParent = iconView.parent as? ViewGroup
        if (currentParent != null && currentParent != backgroundView) {
            currentParent.removeView(iconView)
            currentParent.removeTransientView(iconView)
        }

        // 3: Add the icon as the starting view.
        backgroundView.addView(iconView, /* index= */ 0, generateCustomIconLayoutParams(iconView))
    }

    private fun View.getCustomIconView(): StatusBarIconView? {
        return this.findViewById(CUSTOM_ICON_VIEW_ID)
    }

    private fun ImageView.tintView(color: Int) {
        this.imageTintList = ColorStateList.valueOf(color)
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
        chipShortTimeDeltaView: DateTimeView,
    ) {
        when (chipModel) {
            is OngoingActivityChipModel.Shown.Countdown -> {
                chipTextView.text = chipModel.secondsUntilStarted.toString()
                chipTextView.visibility = View.VISIBLE

                chipTimeView.hide()
                chipShortTimeDeltaView.visibility = View.GONE
            }
            is OngoingActivityChipModel.Shown.Text -> {
                chipTextView.text = chipModel.text
                chipTextView.visibility = View.VISIBLE

                chipTimeView.hide()
                chipShortTimeDeltaView.visibility = View.GONE
            }
            is OngoingActivityChipModel.Shown.Timer -> {
                ChipChronometerBinder.bind(chipModel.startTimeMs, chipTimeView)
                chipTimeView.visibility = View.VISIBLE

                chipTextView.visibility = View.GONE
                chipShortTimeDeltaView.visibility = View.GONE
            }
            is OngoingActivityChipModel.Shown.ShortTimeDelta -> {
                chipShortTimeDeltaView.setTime(chipModel.time)
                chipShortTimeDeltaView.visibility = View.VISIBLE
                chipShortTimeDeltaView.isShowRelativeTime = true
                chipShortTimeDeltaView.setRelativeTimeDisambiguationTextMask(
                    DateTimeView.DISAMBIGUATION_TEXT_PAST
                )
                chipShortTimeDeltaView.setRelativeTimeUnitDisplayLength(
                    DateTimeView.UNIT_DISPLAY_LENGTH_MEDIUM
                )

                chipTextView.visibility = View.GONE
                chipTimeView.hide()
            }
            is OngoingActivityChipModel.Shown.IconOnly -> {
                chipTextView.visibility = View.GONE
                chipShortTimeDeltaView.visibility = View.GONE
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
        chipShortTimeDeltaView: DateTimeView,
    ) {
        val icon = chipModel.icon
        if (icon != null) {
            if (iconRequiresEmbeddedPadding(icon)) {
                // If the icon is a custom [StatusBarIconView], then it should've come from
                // `Notification.smallIcon`, which is required to embed its own paddings. We need to
                // adjust the other paddings to make everything look good :)
                backgroundView.setBackgroundPaddingForEmbeddedPaddingIcon()
                chipTextView.setTextPaddingForEmbeddedPaddingIcon()
                chipTimeView.setTextPaddingForEmbeddedPaddingIcon()
                chipShortTimeDeltaView.setTextPaddingForEmbeddedPaddingIcon()
            } else {
                backgroundView.setBackgroundPaddingForNormalIcon()
                chipTextView.setTextPaddingForNormalIcon()
                chipTimeView.setTextPaddingForNormalIcon()
                chipShortTimeDeltaView.setTextPaddingForNormalIcon()
            }
        } else {
            backgroundView.setBackgroundPaddingForNoIcon()
            chipTextView.setTextPaddingForNoIcon()
            chipTimeView.setTextPaddingForNoIcon()
            chipShortTimeDeltaView.setTextPaddingForNoIcon()
        }
    }

    private fun iconRequiresEmbeddedPadding(icon: OngoingActivityChipModel.ChipIcon) =
        icon is OngoingActivityChipModel.ChipIcon.StatusBarView ||
            icon is OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon

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
            if (StatusBarNotifChips.isEnabled) {
                0
            } else {
                context.resources.getDimensionPixelSize(
                    R.dimen.ongoing_activity_chip_side_padding_for_embedded_padding_icon
                )
            }
        setPaddingRelative(sidePadding, paddingTop, sidePadding, paddingBottom)
    }

    private fun View.setBackgroundPaddingForNormalIcon() {
        val sidePadding =
            context.resources.getDimensionPixelSize(R.dimen.ongoing_activity_chip_side_padding)
        setPaddingRelative(sidePadding, paddingTop, sidePadding, paddingBottom)
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
            is OngoingActivityChipModel.Shown.ShortTimeDelta,
            is OngoingActivityChipModel.Shown.IconOnly -> {
                chipView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            }
        }
        // Clickable chips need to be a minimum size for accessibility purposes, but let
        // non-clickable chips be smaller.
        val minimumWidth =
            if (chipModel.onClickListenerLegacy != null) {
                chipBackgroundView.context.resources.getDimensionPixelSize(
                    R.dimen.min_clickable_item_size
                )
            } else {
                0
            }
        // The background view needs the minimum width so it only fills the area required (e.g. the
        // 3-2-1 screen record countdown chip isn't tappable so it should have a small-width
        // background).
        chipBackgroundView.minimumWidth = minimumWidth
        // The root view needs the minimum width so the second chip can hide if there isn't enough
        // room for the chip -- see [SecondaryOngoingActivityChip].
        chipView.minimumWidth = minimumWidth
    }

    @IdRes private val CUSTOM_ICON_VIEW_ID = R.id.ongoing_activity_chip_custom_icon
}
