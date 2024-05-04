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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ViewFlipper
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag
import com.android.systemui.statusbar.notification.row.ui.viewbinder.NotificationViewFlipperBinder
import com.android.systemui.statusbar.notification.row.ui.viewmodel.NotificationViewFlipperViewModel
import com.android.systemui.statusbar.notification.shared.NotificationViewFlipperPausing
import javax.inject.Inject

/**
 * A factory which owns the construction of any ViewFlipper inside of Notifications, and binds it
 * with a view model. This ensures that ViewFlippers are paused when the keyguard is showing.
 */
class NotificationViewFlipperFactory
@Inject
constructor(
    private val viewModel: NotificationViewFlipperViewModel,
) : NotifRemoteViewsFactory {
    init {
        /* check if */ NotificationViewFlipperPausing.isUnexpectedlyInLegacyMode()
    }

    override fun instantiate(
        row: ExpandableNotificationRow,
        @InflationFlag layoutType: Int,
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        return when (name) {
            ViewFlipper::class.java.name,
            ViewFlipper::class.java.simpleName ->
                ViewFlipper(context, attrs).also { viewFlipper ->
                    NotificationViewFlipperBinder.bindWhileAttached(viewFlipper, viewModel)
                }
            else -> null
        }
    }
}
