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

package com.android.systemui.keyguard.ui.view.layout.sections

import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.promoted.AODPromotedNotification
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationLogger
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUiAod
import com.android.systemui.statusbar.notification.promoted.ui.viewmodel.AODPromotedNotificationViewModel
import javax.inject.Inject

class AodPromotedNotificationSection
@Inject
constructor(
    private val viewModelFactory: AODPromotedNotificationViewModel.Factory,
    private val shadeInteractor: ShadeInteractor,
    private val logger: PromotedNotificationLogger,
) : KeyguardSection() {
    var view: ComposeView? = null

    init {
        logger.logSectionCreated(this)
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!PromotedNotificationUiAod.isEnabled) {
            return
        }

        check(view == null)

        view =
            ComposeView(constraintLayout.context).apply {
                setContent { AODPromotedNotification(viewModelFactory) }
                id = viewId
                constraintLayout.addView(this)
            }

        logger.logSectionAddedViews(this)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!PromotedNotificationUiAod.isEnabled) {
            return
        }

        checkNotNull(view)

        // Do nothing; the binding happens in the AODPromotedNotification Composable.

        logger.logSectionBoundData(this)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!PromotedNotificationUiAod.isEnabled) {
            return
        }

        // view may have been created by a different instance of the section (!), and we don't
        // actually *need* it to set constraints, so don't check for it here.

        constraintSet.apply {
            val isShadeLayoutWide = shadeInteractor.isShadeLayoutWide.value
            val endGuidelineId = if (isShadeLayoutWide) R.id.split_shade_guideline else PARENT_ID

            connect(viewId, TOP, R.id.smart_space_barrier_bottom, BOTTOM, 0)
            connect(viewId, START, PARENT_ID, START, 0)
            connect(viewId, END, endGuidelineId, END, 0)

            constrainWidth(viewId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(viewId, ConstraintSet.WRAP_CONTENT)
        }

        logger.logSectionAppliedConstraints(this)
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        if (!PromotedNotificationUiAod.isEnabled) {
            return
        }

        constraintLayout.removeView(checkNotNull(view))

        view = null

        logger.logSectionRemovedViews(this)
    }

    companion object {
        val viewId = R.id.aod_promoted_notification_frame
    }
}
