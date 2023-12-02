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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.keyguard.KeyguardStatusView
import com.android.keyguard.dagger.KeyguardStatusViewComponent
import com.android.systemui.keyguard.KeyguardViewConfigurator
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.controls.ui.KeyguardMediaController
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.Utils
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

class DefaultStatusViewSection
@Inject
constructor(
    private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val keyguardStatusViewComponentFactory: KeyguardStatusViewComponent.Factory,
    private val keyguardViewConfigurator: Lazy<KeyguardViewConfigurator>,
    private val notificationPanelViewController: Lazy<NotificationPanelViewController>,
    private val keyguardMediaController: KeyguardMediaController,
    private val splitShadeStateController: SplitShadeStateController,
) : KeyguardSection() {
    private val statusViewId = R.id.keyguard_status_view

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!KeyguardShadeMigrationNssl.isEnabled) {
            return
        }
        // At startup, 2 views with the ID `R.id.keyguard_status_view` will be available.
        // Disable one of them
        notificationPanelView.findViewById<View>(statusViewId)?.let {
            notificationPanelView.removeView(it)
        }
        val keyguardStatusView =
            (LayoutInflater.from(context)
                    .inflate(R.layout.keyguard_status_view, constraintLayout, false)
                    as KeyguardStatusView)
                .apply { clipChildren = false }

        // This is diassembled and moved to [AodNotificationIconsSection]
        keyguardStatusView.findViewById<View>(R.id.left_aligned_notification_icon_container)?.let {
            it.setVisibility(View.GONE)
        }
        // Should keep this even if flag, migrating clocks to blueprint, is on
        // cause some events in clockEventController rely on keyguardStatusViewController
        // TODO(b/313499340): clean up
        constraintLayout.addView(keyguardStatusView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (KeyguardShadeMigrationNssl.isEnabled) {
            constraintLayout.findViewById<KeyguardStatusView?>(R.id.keyguard_status_view)?.let {
                val statusViewComponent =
                    keyguardStatusViewComponentFactory.build(it, context.display)
                val controller = statusViewComponent.keyguardStatusViewController
                controller.init()
                keyguardMediaController.attachSplitShadeContainer(
                    it.requireViewById<ViewGroup>(R.id.status_view_media_container)
                )
                keyguardViewConfigurator.get().keyguardStatusViewController = controller
                notificationPanelViewController.get().updateStatusViewController()
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(statusViewId, MATCH_CONSTRAINT)
            constrainHeight(statusViewId, WRAP_CONTENT)
            // TODO(b/296122465): Constrain to the top of [DefaultStatusBarSection] and remove the
            // extra margin below.
            connect(statusViewId, TOP, PARENT_ID, TOP)
            connect(statusViewId, START, PARENT_ID, START)
            connect(statusViewId, END, PARENT_ID, END)

            val margin =
                if (splitShadeStateController.shouldUseSplitNotificationShade(context.resources)) {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
                } else {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                        Utils.getStatusBarHeaderHeightKeyguard(context)
                }
            setMargin(statusViewId, TOP, margin)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(statusViewId)
        keyguardViewConfigurator.get().keyguardStatusViewController = null
    }
}
