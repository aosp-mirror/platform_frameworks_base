/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard

import android.content.Context
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.END
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.START
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import javax.inject.Inject

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictate which direction to move and when, via a filter function.
 */
@SysUIUnfoldScope
class KeyguardUnfoldTransition
@Inject
constructor(
    private val context: Context,
    statusBarStateController: StatusBarStateController,
    unfoldProgressProvider: NaturalRotationUnfoldProgressProvider,
) {

    /** Certain views only need to move if they are not currently centered */
    var statusViewCentered = false

    private val filterKeyguardAndSplitShadeOnly: () -> Boolean = {
        statusBarStateController.getState() == KEYGUARD && !statusViewCentered }
    private val filterKeyguard: () -> Boolean = { statusBarStateController.getState() == KEYGUARD }

    private val translateAnimator by lazy {
        UnfoldConstantTranslateAnimator(
            viewsIdToTranslate =
                setOf(
                    ViewIdToTranslate(R.id.keyguard_status_area, START, filterKeyguard,
                        { view, value ->
                            (view as? KeyguardStatusAreaView)?.translateXFromUnfold = value
                        }),
                    ViewIdToTranslate(
                        R.id.lockscreen_clock_view_large, START, filterKeyguardAndSplitShadeOnly),
                    ViewIdToTranslate(R.id.lockscreen_clock_view, START, filterKeyguard),
                    ViewIdToTranslate(
                        R.id.notification_stack_scroller, END, filterKeyguardAndSplitShadeOnly),
                    ViewIdToTranslate(R.id.start_button, START, filterKeyguard),
                    ViewIdToTranslate(R.id.end_button, END, filterKeyguard)),
            progressProvider = unfoldProgressProvider)
    }

    /** Relies on the [parent] to locate views to translate. */
    fun setup(parent: ViewGroup) {
        val translationMax =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_unfold_translation_x).toFloat()
        translateAnimator.init(parent, translationMax)
    }
}
