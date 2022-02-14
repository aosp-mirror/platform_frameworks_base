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
import android.view.View
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import javax.inject.Inject

/**
 * Translates items away/towards the hinge when the device is opened/closed. This is controlled by
 * the set of ids, which also dictact which direction to move and when, via a filter function.
 */
@SysUIUnfoldScope
class KeyguardUnfoldTransition @Inject constructor(
    val context: Context,
    val unfoldProgressProvider: NaturalRotationUnfoldProgressProvider
) {

    companion object {
        final val LEFT = -1
        final val RIGHT = 1
    }

    private val filterSplitShadeOnly = { !statusViewCentered }
    private val filterNever = { true }

    private val ids = setOf(
        Triple(R.id.keyguard_status_area, LEFT, filterNever),
        Triple(R.id.controls_button, LEFT, filterNever),
        Triple(R.id.lockscreen_clock_view_large, LEFT, filterSplitShadeOnly),
        Triple(R.id.lockscreen_clock_view, LEFT, filterNever),
        Triple(R.id.notification_stack_scroller, RIGHT, filterSplitShadeOnly),
        Triple(R.id.wallet_button, RIGHT, filterNever)
    )
    private var parent: ViewGroup? = null
    private var views = listOf<Triple<View, Int, () -> Boolean>>()
    private var xTranslationMax = 0f

    /**
     * Certain views only need to move if they are not currently centered
     */
    var statusViewCentered = false

    init {
        unfoldProgressProvider.addCallback(
            object : TransitionProgressListener {
                override fun onTransitionStarted() {
                    findViews()
                }

                override fun onTransitionProgress(progress: Float) {
                    translateViews(progress)
                }

                override fun onTransitionFinished() {
                    translateViews(1f)
                }
            }
        )
    }

    /**
     * Relies on the [parent] to locate views to translate
     */
    fun setup(parent: ViewGroup) {
        this.parent = parent
        xTranslationMax = context.resources.getDimensionPixelSize(
            R.dimen.keyguard_unfold_translation_x).toFloat()
    }

    /**
     * Manually translate views based on set direction. At the moment
     * [UnfoldMoveFromCenterAnimator] exists but moves all views a dynamic distance
     * from their mid-point. This code instead will only ever translate by a fixed amount.
     */
    private fun translateViews(progress: Float) {
        val xTrans = progress * xTranslationMax - xTranslationMax
        views.forEach {
            (view, direction, pred) -> if (pred()) {
                view.setTranslationX(xTrans * direction)
            }
        }
    }

    private fun findViews() {
        parent?.let { p ->
            views = ids.mapNotNull {
                (id, direction, pred) -> p.findViewById<View>(id)?.let {
                    Triple(it, direction, pred)
                }
            }
        }
    }
}
