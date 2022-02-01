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
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.LEFT
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.RIGHT
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import javax.inject.Inject

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter function.
 */
@SysUIUnfoldScope
class KeyguardUnfoldTransition
@Inject
constructor(
    private val context: Context,
    unfoldProgressProvider: NaturalRotationUnfoldProgressProvider
) {

    /** Certain views only need to move if they are not currently centered */
    var statusViewCentered = false

    private val filterSplitShadeOnly = { !statusViewCentered }
    private val filterNever = { true }

    private val translateAnimator by lazy {
        UnfoldConstantTranslateAnimator(
            viewsIdToTranslate =
                setOf(
                    ViewIdToTranslate(R.id.keyguard_status_area, LEFT, filterNever),
                    ViewIdToTranslate(R.id.controls_button, LEFT, filterNever),
                    ViewIdToTranslate(R.id.lockscreen_clock_view_large, LEFT, filterSplitShadeOnly),
                    ViewIdToTranslate(R.id.lockscreen_clock_view, LEFT, filterNever),
                    ViewIdToTranslate(
                        R.id.notification_stack_scroller, RIGHT, filterSplitShadeOnly),
                    ViewIdToTranslate(R.id.wallet_button, RIGHT, filterNever)),
            progressProvider = unfoldProgressProvider)
    }

    /** Relies on the [parent] to locate views to translate. */
    fun setup(parent: ViewGroup) {
        val translationMax =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_unfold_translation_x).toFloat()
        translateAnimator.init(parent, translationMax)
    }
}
