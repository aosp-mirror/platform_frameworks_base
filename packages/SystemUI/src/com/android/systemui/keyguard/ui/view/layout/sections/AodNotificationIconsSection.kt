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
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.AlwaysOnDisplayNotificationIconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.StatusBarIconViewBindingFailureTracker
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.util.ui.value
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

class AodNotificationIconsSection
@Inject
constructor(
    private val context: Context,
    private val configurationState: ConfigurationState,
    private val iconBindingFailureTracker: StatusBarIconViewBindingFailureTracker,
    private val nicAodViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val nicAodIconViewStore: AlwaysOnDisplayNotificationIconViewStore,
    private val systemBarUtilsState: SystemBarUtilsState,
    private val rootViewModel: KeyguardRootViewModel,
) : KeyguardSection() {

    private var nicBindingDisposable: DisposableHandle? = null
    private val nicId = R.id.aod_notification_icon_container
    private lateinit var nic: NotificationIconContainer

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }
        nic =
            NotificationIconContainer(context, null).apply {
                id = nicId
                setPaddingRelative(
                    resources.getDimensionPixelSize(R.dimen.below_clock_padding_start_icons),
                    0,
                    0,
                    0
                )
                setVisibility(View.INVISIBLE)
            }

        constraintLayout.addView(nic)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        nicBindingDisposable?.dispose()
        nicBindingDisposable =
            NotificationIconContainerViewBinder.bindWhileAttached(
                nic,
                nicAodViewModel,
                configurationState,
                systemBarUtilsState,
                iconBindingFailureTracker,
                nicAodIconViewStore,
            )
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!MigrateClocksToBlueprint.isEnabled) {
            return
        }

        val bottomMargin =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)
        val isVisible = rootViewModel.isNotifIconContainerVisible.value
        constraintSet.apply {
            connect(nicId, TOP, R.id.smart_space_barrier_bottom, BOTTOM, bottomMargin)
            setGoneMargin(nicId, BOTTOM, bottomMargin)
            setVisibility(nicId, if (isVisible.value) VISIBLE else GONE)

            connect(
                nicId,
                START,
                PARENT_ID,
                START,
                context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
            )
            connect(
                nicId,
                END,
                PARENT_ID,
                END,
                context.resources.getDimensionPixelSize(R.dimen.status_view_margin_horizontal)
            )
            constrainHeight(
                nicId,
                context.resources.getDimensionPixelSize(R.dimen.notification_shelf_height)
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(nicId)
        nicBindingDisposable?.dispose()
    }
}
