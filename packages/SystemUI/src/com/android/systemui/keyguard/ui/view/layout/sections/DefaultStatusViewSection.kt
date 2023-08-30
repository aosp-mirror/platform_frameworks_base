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
import com.android.systemui.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.KeyguardViewConfigurator
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.media.controls.ui.KeyguardMediaController
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.NotificationPanelViewController
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.Utils
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

class DefaultStatusViewSection
@Inject
constructor(
    private val context: Context,
    private val featureFlags: FeatureFlags,
    private val notificationPanelView: NotificationPanelView,
    private val keyguardStatusViewComponentFactory: KeyguardStatusViewComponent.Factory,
    private val keyguardViewConfigurator: Lazy<KeyguardViewConfigurator>,
    private val notificationPanelViewController: Lazy<NotificationPanelViewController>,
    private val keyguardMediaController: KeyguardMediaController,
) : KeyguardSection {
    private val statusViewId = R.id.keyguard_status_view

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun addViews(constraintLayout: ConstraintLayout) {
        // At startup, 2 views with the ID `R.id.keyguard_status_view` will be available.
        // Disable one of them
        if (featureFlags.isEnabled(Flags.MIGRATE_KEYGUARD_STATUS_VIEW)) {
            notificationPanelView.findViewById<View>(statusViewId)?.let {
                notificationPanelView.removeView(it)
            }
            if (constraintLayout.findViewById<View>(statusViewId) == null) {
                val keyguardStatusView =
                    (LayoutInflater.from(context)
                            .inflate(R.layout.keyguard_status_view, constraintLayout, false)
                            as KeyguardStatusView)
                        .apply { clipChildren = false }

                val statusViewComponent =
                    keyguardStatusViewComponentFactory.build(keyguardStatusView)
                val controller = statusViewComponent.keyguardStatusViewController
                controller.init()
                constraintLayout.addView(keyguardStatusView)
                keyguardMediaController.attachSplitShadeContainer(
                    keyguardStatusView.requireViewById<ViewGroup>(R.id.status_view_media_container)
                )
                keyguardViewConfigurator.get().keyguardStatusViewController = controller
                notificationPanelViewController.get().updateStatusBarViewController()
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(statusViewId, MATCH_CONSTRAINT)
            constrainHeight(statusViewId, WRAP_CONTENT)
            connect(statusViewId, TOP, PARENT_ID, TOP)
            connect(statusViewId, START, PARENT_ID, START)
            connect(statusViewId, END, PARENT_ID, END)

            val margin =
                if (LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)) {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
                } else {
                    context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
                        Utils.getStatusBarHeaderHeightKeyguard(context)
                }
            setMargin(statusViewId, TOP, margin)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onDestroy() {
        keyguardViewConfigurator.get().keyguardStatusViewController = null
    }
}
