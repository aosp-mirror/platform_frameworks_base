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
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationStackAppearanceViewBinder
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationStackAppearanceViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle

abstract class NotificationStackScrollLayoutSection
constructor(
    protected val context: Context,
    private val sceneContainerFlags: SceneContainerFlags,
    private val notificationPanelView: NotificationPanelView,
    private val sharedNotificationContainer: SharedNotificationContainer,
    private val sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    private val notificationStackAppearanceViewModel: NotificationStackAppearanceViewModel,
    private val ambientState: AmbientState,
    private val controller: NotificationStackScrollLayoutController,
    private val notificationStackSizeCalculator: NotificationStackSizeCalculator,
    private val mainDispatcher: CoroutineDispatcher,
) : KeyguardSection() {
    private val placeHolderId = R.id.nssl_placeholder
    private val disposableHandles: MutableList<DisposableHandle> = mutableListOf()

    /**
     * Align the notification placeholder bottom to the top of either the lock icon or the ambient
     * indication area, whichever is higher.
     */
    protected fun addNotificationPlaceholderBarrier(constraintSet: ConstraintSet) {
        val lockId =
            if (DeviceEntryUdfpsRefactor.isEnabled) {
                R.id.device_entry_icon_view
            } else {
                R.id.lock_icon_view
            }

        constraintSet.apply {
            createBarrier(
                R.id.nssl_placeholder_barrier_bottom,
                Barrier.TOP,
                0,
                *intArrayOf(lockId, R.id.ambient_indication_container)
            )
            connect(R.id.nssl_placeholder, BOTTOM, R.id.nssl_placeholder_barrier_bottom, TOP)
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }
        // This moves the existing NSSL view to a different parent, as the controller is a
        // singleton and recreating it has other bad side effects.
        // In the SceneContainer, this is done by the NotificationSection composable.
        notificationPanelView.findViewById<View?>(R.id.notification_stack_scroller)?.let {
            (it.parent as ViewGroup).removeView(it)
            sharedNotificationContainer.addNotificationStackScrollLayout(it)
        }

        val view = View(context, null).apply { id = placeHolderId }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        if (!migrateClocksToBlueprint()) {
            return
        }

        disposeHandles()
        disposableHandles.add(
            SharedNotificationContainerBinder.bind(
                sharedNotificationContainer,
                sharedNotificationContainerViewModel,
                sceneContainerFlags,
                controller,
                notificationStackSizeCalculator,
                mainImmediateDispatcher = mainDispatcher,
            )
        )

        if (sceneContainerFlags.flexiNotifsEnabled()) {
            disposableHandles.add(
                NotificationStackAppearanceViewBinder.bind(
                    context,
                    sharedNotificationContainer,
                    notificationStackAppearanceViewModel,
                    ambientState,
                    controller,
                    mainImmediateDispatcher = mainDispatcher,
                )
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        disposeHandles()
        constraintLayout.removeView(placeHolderId)
    }

    private fun disposeHandles() {
        disposableHandles.forEach { it.dispose() }
        disposableHandles.clear()
    }
}
