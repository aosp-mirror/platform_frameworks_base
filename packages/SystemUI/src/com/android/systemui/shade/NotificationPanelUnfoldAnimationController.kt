/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

import android.content.Context
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.END
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.START
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import javax.inject.Inject

@SysUIUnfoldScope
class NotificationPanelUnfoldAnimationController
@Inject
constructor(
    private val context: Context,
    statusBarStateController: StatusBarStateController,
    progressProvider: NaturalRotationUnfoldProgressProvider,
) {

    private val filterShade: () -> Boolean = { statusBarStateController.getState() == SHADE ||
        statusBarStateController.getState() == SHADE_LOCKED }

    private val translateAnimator by lazy {
        UnfoldConstantTranslateAnimator(
            viewsIdToTranslate =
                setOf(
                    ViewIdToTranslate(R.id.quick_settings_panel, START, filterShade),
                    ViewIdToTranslate(R.id.notification_stack_scroller, END, filterShade),
                    ViewIdToTranslate(R.id.statusIcons, END, filterShade),
                    ViewIdToTranslate(R.id.privacy_container, END, filterShade),
                    ViewIdToTranslate(R.id.batteryRemainingIcon, END, filterShade),
                    ViewIdToTranslate(R.id.carrier_group, END, filterShade),
                    ViewIdToTranslate(R.id.clock, START, filterShade),
                    ViewIdToTranslate(R.id.date, START, filterShade)),
            progressProvider = progressProvider)
    }

    fun setup(root: ViewGroup) {
        val translationMax =
            context.resources.getDimensionPixelSize(R.dimen.notification_side_paddings).toFloat()
        translateAnimator.init(root, translationMax)
    }
}
