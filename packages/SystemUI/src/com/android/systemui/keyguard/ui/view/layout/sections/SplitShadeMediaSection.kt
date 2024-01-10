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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.controls.ui.KeyguardMediaController
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import javax.inject.Inject

/** Aligns media on left side for split shade, below smartspace, date, and weather. */
class SplitShadeMediaSection
@Inject
constructor(
    private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val keyguardMediaController: KeyguardMediaController
) : KeyguardSection() {
    private val mediaContainerId = R.id.status_view_media_container

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }

        notificationPanelView.findViewById<View>(mediaContainerId)?.let {
            notificationPanelView.removeView(it)
        }

        val mediaFrame =
            FrameLayout(context, null).apply {
                id = mediaContainerId
                val padding = context.resources.getDimensionPixelSize(R.dimen.qs_media_padding)
                val horizontalPadding =
                    padding +
                        context.resources.getDimensionPixelSize(
                            R.dimen.status_view_margin_horizontal
                        )

                setPaddingRelative(horizontalPadding, padding, horizontalPadding, padding)
            }
        constraintLayout.addView(mediaFrame)
        keyguardMediaController.attachSplitShadeContainer(mediaFrame)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!migrateClocksToBlueprint()) {
            return
        }

        constraintSet.apply {
            constrainWidth(mediaContainerId, MATCH_CONSTRAINT)
            constrainHeight(mediaContainerId, WRAP_CONTENT)
            connect(mediaContainerId, TOP, R.id.smart_space_barrier_bottom, BOTTOM)
            connect(mediaContainerId, START, PARENT_ID, START)
            connect(mediaContainerId, END, R.id.split_shade_guideline, END)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }

        constraintLayout.removeView(mediaContainerId)
    }
}
