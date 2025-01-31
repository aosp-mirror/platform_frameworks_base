/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.promoted

import android.app.Flags
import android.app.Notification
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewStub
import android.widget.Chronometer
import android.widget.DateTimeView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import com.android.app.tracing.traceSection
import com.android.internal.R
import com.android.internal.widget.BigPictureNotificationImageView
import com.android.internal.widget.CachingIconView
import com.android.internal.widget.ImageFloatingTextView
import com.android.internal.widget.NotificationExpandButton
import com.android.internal.widget.NotificationProgressBar
import com.android.internal.widget.NotificationProgressModel
import com.android.internal.widget.NotificationRowIconView
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R as systemuiR
import com.android.systemui.statusbar.notification.promoted.AodPromotedNotificationColor.PrimaryText
import com.android.systemui.statusbar.notification.promoted.AodPromotedNotificationColor.SecondaryText
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.Style
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.When
import com.android.systemui.statusbar.notification.promoted.ui.viewmodel.AODPromotedNotificationViewModel

@Composable
fun AODPromotedNotification(viewModelFactory: AODPromotedNotificationViewModel.Factory) {
    if (!PromotedNotificationUiAod.isEnabled) {
        return
    }

    val viewModel = rememberViewModel(traceName = "$TAG.viewModel") { viewModelFactory.create() }

    val content = viewModel.content ?: return
    val audiblyAlertedIconVisible = viewModel.audiblyAlertedIconVisible

    key(content.identity) {
        val layoutResource = content.layoutResource ?: return

        val topPadding = dimensionResource(systemuiR.dimen.below_clock_padding_start_icons)
        val sidePaddings = dimensionResource(systemuiR.dimen.notification_side_paddings)
        val paddingValues =
            PaddingValues(top = topPadding, start = sidePaddings, end = sidePaddings, bottom = 0.dp)

        val borderStroke = BorderStroke(1.dp, SecondaryText.brush)

        val borderRadius = dimensionResource(systemuiR.dimen.notification_corner_radius)
        val borderShape = RoundedCornerShape(borderRadius)

        Box(modifier = Modifier.padding(paddingValues)) {
            AODPromotedNotificationView(
                layoutResource = layoutResource,
                content = content,
                audiblyAlertedIconVisible = audiblyAlertedIconVisible,
                modifier = Modifier.border(borderStroke, borderShape),
            )
        }
    }
}

@Composable
fun AODPromotedNotificationView(
    layoutResource: Int,
    content: PromotedNotificationContentModel,
    audiblyAlertedIconVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val view =
                traceSection("$TAG.inflate") {
                    LayoutInflater.from(context).inflate(layoutResource, /* root= */ null)
                }

            val updater =
                traceSection("$TAG.findViews") { AODPromotedNotificationViewUpdater(view) }

            view.setTag(viewUpdaterTagId, updater)

            view
        },
        update = { view ->
            val updater = view.getTag(viewUpdaterTagId) as AODPromotedNotificationViewUpdater

            traceSection("$TAG.update") { updater.update(content, audiblyAlertedIconVisible) }
        },
        modifier = modifier,
    )
}

private val PromotedNotificationContentModel.layoutResource: Int?
    get() {
        return if (Flags.notificationsRedesignTemplates()) {
            when (style) {
                Style.Base -> R.layout.notification_2025_template_expanded_base
                Style.BigPicture -> R.layout.notification_2025_template_expanded_big_picture
                Style.BigText -> R.layout.notification_2025_template_expanded_big_text
                Style.Call -> R.layout.notification_2025_template_expanded_call
                Style.Progress -> R.layout.notification_2025_template_expanded_progress
                Style.Ineligible -> null
            }
        } else {
            when (style) {
                Style.Base -> R.layout.notification_template_material_big_base
                Style.BigPicture -> R.layout.notification_template_material_big_picture
                Style.BigText -> R.layout.notification_template_material_big_text
                Style.Call -> R.layout.notification_template_material_big_call
                Style.Progress -> R.layout.notification_template_material_progress
                Style.Ineligible -> null
            }
        }
    }

private class AODPromotedNotificationViewUpdater(root: View) {
    private val alertedIcon: ImageView? = root.findViewById(R.id.alerted_icon)
    private val alternateExpandTarget: View? = root.findViewById(R.id.alternate_expand_target)
    private val appNameDivider: View? = root.findViewById(R.id.app_name_divider)
    private val appNameText: TextView? = root.findViewById(R.id.app_name_text)
    private val bigPicture: BigPictureNotificationImageView? = root.findViewById(R.id.big_picture)
    private val bigText: ImageFloatingTextView? = root.findViewById(R.id.big_text)
    private var chronometerStub: ViewStub? = root.findViewById(R.id.chronometer)
    private var chronometer: Chronometer? = null
    private val closeButton: View? = root.findViewById(R.id.close_button)
    private val conversationText: TextView? = root.findViewById(R.id.conversation_text)
    private val expandButton: NotificationExpandButton? = root.findViewById(R.id.expand_button)
    private val headerText: TextView? = root.findViewById(R.id.header_text)
    private val headerTextDivider: View? = root.findViewById(R.id.header_text_divider)
    private val headerTextSecondary: TextView? = root.findViewById(R.id.header_text_secondary)
    private val headerTextSecondaryDivider: View? =
        root.findViewById(R.id.header_text_secondary_divider)
    private val icon: NotificationRowIconView? = root.findViewById(R.id.icon)
    private val leftIcon: ImageView? = root.findViewById(R.id.left_icon)
    private val notificationProgressEndIcon: CachingIconView? =
        root.findViewById(R.id.notification_progress_end_icon)
    private val notificationProgressStartIcon: CachingIconView? =
        root.findViewById(R.id.notification_progress_start_icon)
    private val profileBadge: ImageView? = root.findViewById(R.id.profile_badge)
    private val text: ImageFloatingTextView? = root.findViewById(R.id.text)
    private val time: DateTimeView? = root.findViewById(R.id.time)
    private val timeDivider: View? = root.findViewById(R.id.time_divider)
    private val title: TextView? = root.findViewById(R.id.title)
    private val verificationDivider: View? = root.findViewById(R.id.verification_divider)
    private val verificationIcon: ImageView? = root.findViewById(R.id.verification_icon)
    private val verificationText: TextView? = root.findViewById(R.id.verification_text)

    private var oldProgressBarStub = root.findViewById<View>(R.id.progress) as? ViewStub
    private var oldProgressBar: ProgressBar? = null
    private val newProgressBar = root.findViewById<View>(R.id.progress) as? NotificationProgressBar

    init {
        // Hide views that are never visible in the skeleton promoted notification.
        alternateExpandTarget?.visibility = GONE
        bigPicture?.visibility = GONE
        closeButton?.visibility = GONE
        expandButton?.visibility = GONE
        leftIcon?.visibility = GONE
        notificationProgressEndIcon?.visibility = GONE
        notificationProgressStartIcon?.visibility = GONE

        // Make one-time changes needed for the skeleton promoted notification.
        alertedIcon
            ?.drawable
            ?.mutate()
            ?.setColorFilter(SecondaryText.colorInt, PorterDuff.Mode.SRC_IN)
    }

    fun update(content: PromotedNotificationContentModel, audiblyAlertedIconVisible: Boolean) {
        when (content.style) {
            Style.Base -> updateBase(content)
            Style.BigPicture -> updateBigPictureStyle(content)
            Style.BigText -> updateBigTextStyle(content)
            Style.Call -> updateCallStyle(content)
            Style.Progress -> updateProgressStyle(content)
            Style.Ineligible -> {}
        }

        alertedIcon?.isVisible = audiblyAlertedIconVisible
    }

    private fun updateBase(
        content: PromotedNotificationContentModel,
        textView: ImageFloatingTextView? = null,
        showOldProgress: Boolean = true,
    ) {
        updateHeader(content)

        updateTitle(title, content)
        updateText(textView ?: text, content)

        if (showOldProgress) {
            updateOldProgressBar(content)
        }
    }

    private fun updateBigPictureStyle(content: PromotedNotificationContentModel) {
        updateBase(content)
    }

    private fun updateBigTextStyle(content: PromotedNotificationContentModel) {
        updateBase(content, textView = bigText)
    }

    private fun updateCallStyle(content: PromotedNotificationContentModel) {
        updateConversationHeader(content)

        updateText(text, content)
    }

    private fun updateProgressStyle(content: PromotedNotificationContentModel) {
        updateBase(content, showOldProgress = false)

        updateNewProgressBar(content)
    }

    private fun updateOldProgressBar(content: PromotedNotificationContentModel) {
        if (
            content.oldProgress == null ||
                content.oldProgress.max == 0 ||
                content.oldProgress.isIndeterminate
        ) {
            oldProgressBar?.visibility = GONE
            return
        }

        inflateOldProgressBar()

        val oldProgressBar = oldProgressBar ?: return

        oldProgressBar.progress = content.oldProgress.progress
        oldProgressBar.max = content.oldProgress.max
        oldProgressBar.isIndeterminate = content.oldProgress.isIndeterminate
        oldProgressBar.visibility = VISIBLE
    }

    private fun updateNewProgressBar(content: PromotedNotificationContentModel) {
        val newProgressBar = newProgressBar ?: return

        if (content.newProgress != null && !content.newProgress.isIndeterminate) {
            newProgressBar.setProgressModel(content.newProgress.toSkeleton().toBundle())
            newProgressBar.visibility = VISIBLE
        } else {
            newProgressBar.visibility = GONE
        }
    }

    private fun updateHeader(content: PromotedNotificationContentModel) {
        updateAppName(content)
        updateTextView(headerTextSecondary, content.subText)
        updateTitle(headerText, content)
        updateTimeAndChronometer(content)

        updateHeaderDividers(content)
    }

    private fun updateHeaderDividers(content: PromotedNotificationContentModel) {
        val hasAppName = content.appName != null && content.appName.isNotEmpty()
        val hasSubText = content.subText != null && content.subText.isNotEmpty()
        val hasHeader = content.title != null && content.title.isNotEmpty()
        val hasTimeOrChronometer = content.time != null

        val hasTextBeforeSubText = hasAppName
        val hasTextBeforeHeader = hasAppName || hasSubText
        val hasTextBeforeTime = hasAppName || hasSubText || hasHeader

        val showDividerBeforeSubText = hasTextBeforeSubText && hasSubText
        val showDividerBeforeHeader = hasTextBeforeHeader && hasHeader
        val showDividerBeforeTime = hasTextBeforeTime && hasTimeOrChronometer

        headerTextSecondaryDivider?.isVisible = showDividerBeforeSubText
        headerTextDivider?.isVisible = showDividerBeforeHeader
        timeDivider?.isVisible = showDividerBeforeTime
    }

    private fun updateConversationHeader(content: PromotedNotificationContentModel) {
        updateTitle(conversationText, content)
        updateAppName(content)
        updateTimeAndChronometer(content)

        updateConversationHeaderDividers(content)

        updateTextView(verificationText, content.verificationText)
    }

    private fun updateConversationHeaderDividers(content: PromotedNotificationContentModel) {
        val hasTitle = content.title != null
        val hasAppName = content.appName != null
        val hasTimeOrChronometer = content.time != null
        val hasVerification = content.verificationIcon != null || content.verificationText != null

        val hasTextBeforeAppName = hasTitle
        val hasTextBeforeTime = hasTitle || hasAppName
        val hasTextBeforeVerification = hasTitle || hasAppName || hasTimeOrChronometer

        val showDividerBeforeAppName = hasTextBeforeAppName && hasAppName
        val showDividerBeforeTime = hasTextBeforeTime && hasTimeOrChronometer
        val showDividerBeforeVerification = hasTextBeforeVerification && hasVerification

        appNameDivider?.isVisible = showDividerBeforeAppName
        timeDivider?.isVisible = showDividerBeforeTime
        verificationDivider?.isVisible = showDividerBeforeVerification
    }

    private fun updateAppName(content: PromotedNotificationContentModel) {
        updateTextView(appNameText, content.appName)
    }

    private fun updateTitle(titleView: TextView?, content: PromotedNotificationContentModel) {
        updateTextView(titleView, content.title, color = PrimaryText)
    }

    private fun updateTimeAndChronometer(content: PromotedNotificationContentModel) {
        setTextViewColor(time, SecondaryText)
        setTextViewColor(chronometer, SecondaryText)

        val timeValue = content.time

        if (timeValue == null) {
            time?.visibility = GONE
            chronometer?.visibility = GONE
        } else if (timeValue.mode == When.Mode.BasicTime) {
            time?.visibility = VISIBLE
            time?.setTime(timeValue.time)
            chronometer?.visibility = GONE
        } else {
            inflateChronometer()

            time?.visibility = GONE
            chronometer?.visibility = VISIBLE
            chronometer?.base = timeValue.time
            chronometer?.isCountDown = (timeValue.mode == When.Mode.CountDown)
            chronometer?.setStarted(true)
        }
    }

    private fun inflateChronometer() {
        if (chronometer != null) {
            return
        }

        chronometer = chronometerStub?.inflate() as Chronometer
        chronometerStub = null
    }

    private fun inflateOldProgressBar() {
        if (oldProgressBar != null) {
            return
        }

        oldProgressBar = oldProgressBarStub?.inflate() as ProgressBar
        oldProgressBarStub = null
    }

    private fun updateText(
        view: ImageFloatingTextView?,
        content: PromotedNotificationContentModel,
    ) {
        view?.setHasImage(false)
        updateTextView(view, content.text)
    }

    private fun updateTextView(
        view: TextView?,
        text: CharSequence?,
        color: AodPromotedNotificationColor = SecondaryText,
    ) {
        setTextViewColor(view, color)

        if (text != null && text.isNotEmpty()) {
            view?.text = text.toSkeleton()
            view?.visibility = VISIBLE
        } else {
            view?.text = ""
            view?.visibility = GONE
        }
    }

    private fun setTextViewColor(view: TextView?, color: AodPromotedNotificationColor) {
        view?.setTextColor(color.colorInt)
    }
}

private fun CharSequence.toSkeleton(): CharSequence {
    return this.toString()
}

private fun NotificationProgressModel.toSkeleton(): NotificationProgressModel {
    if (isIndeterminate) {
        return NotificationProgressModel(/* indeterminateColor= */ SecondaryText.colorInt)
    }

    return NotificationProgressModel(
        listOf(Notification.ProgressStyle.Segment(progressMax).toSkeleton()),
        points.map { it.toSkeleton() }.toList(),
        progress,
        /* isStyledByProgress = */ true,
        /* segmentsFallbackColor = */ SecondaryText.colorInt,
    )
}

private fun Notification.ProgressStyle.Segment.toSkeleton(): Notification.ProgressStyle.Segment {
    return Notification.ProgressStyle.Segment(length).also {
        it.id = id
        it.color = SecondaryText.colorInt
    }
}

private fun Notification.ProgressStyle.Point.toSkeleton(): Notification.ProgressStyle.Point {
    return Notification.ProgressStyle.Point(position).also {
        it.id = id
        it.color = SecondaryText.colorInt
    }
}

private enum class AodPromotedNotificationColor(colorUInt: UInt) {
    Background(0x00000000u),
    PrimaryText(0xFFFFFFFFu),
    SecondaryText(0xFFCCCCCCu);

    val colorInt = colorUInt.toInt()
    val color = Color(colorInt)
    val brush = SolidColor(color)
}

private val viewUpdaterTagId = systemuiR.id.aod_promoted_notification_view_updater_tag

private const val TAG = "AODPromotedNotification"
