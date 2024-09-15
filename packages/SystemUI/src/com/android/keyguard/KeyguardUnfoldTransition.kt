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
import com.android.systemui.keyguard.MigrateClocksToBlueprint
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.END
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.Direction.START
import com.android.systemui.shared.animation.UnfoldConstantTranslateAnimator.ViewIdToTranslate
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.dagger.NaturalRotation
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
    private val keyguardRootView: KeyguardRootView,
    private val shadeWindowView: NotificationShadeWindowView,
    statusBarStateController: StatusBarStateController,
    @NaturalRotation unfoldProgressProvider: UnfoldTransitionProgressProvider,
) {

    /** Certain views only need to move if they are not currently centered */
    var statusViewCentered = false

    private val filterKeyguardAndSplitShadeOnly: () -> Boolean = {
        statusBarStateController.getState() == KEYGUARD && !statusViewCentered }
    private val filterKeyguard: () -> Boolean = { statusBarStateController.getState() == KEYGUARD }

    private val translateAnimator by lazy {
        val smartSpaceViews = if (MigrateClocksToBlueprint.isEnabled) {
            // Use scrollX instead of translationX as translation is already set by [AodBurnInLayer]
            val scrollXTranslation = { view: View, translation: Float ->
                view.scrollX = -translation.toInt()
            }

            setOf(
                ViewIdToTranslate(
                    viewId = sharedR.id.date_smartspace_view,
                    direction = START,
                    shouldBeAnimated = filterKeyguard,
                    translateFunc = scrollXTranslation,
                ),
                ViewIdToTranslate(
                    viewId = sharedR.id.bc_smartspace_view,
                    direction = START,
                    shouldBeAnimated = filterKeyguard,
                    translateFunc = scrollXTranslation,
                ),
                ViewIdToTranslate(
                    viewId = sharedR.id.weather_smartspace_view,
                    direction = START,
                    shouldBeAnimated = filterKeyguard,
                    translateFunc = scrollXTranslation,
                )
            )
        } else {
            setOf(ViewIdToTranslate(
                viewId = R.id.keyguard_status_area,
                direction = START,
                shouldBeAnimated = filterKeyguard,
                translateFunc = { view, value ->
                    (view as? KeyguardStatusAreaView)?.translateXFromUnfold = value
                }
            ))
        }

        UnfoldConstantTranslateAnimator(
            viewsIdToTranslate =
                setOf(
                    ViewIdToTranslate(
                        viewId = R.id.lockscreen_clock_view_large,
                        direction = START,
                        shouldBeAnimated = filterKeyguardAndSplitShadeOnly
                    ),
                    ViewIdToTranslate(
                        viewId = R.id.lockscreen_clock_view,
                        direction = START,
                        shouldBeAnimated = filterKeyguard
                    ),
                    ViewIdToTranslate(
                        viewId = R.id.notification_stack_scroller,
                        direction = END,
                        shouldBeAnimated = filterKeyguardAndSplitShadeOnly
                    )
                ) + smartSpaceViews,
            progressProvider = unfoldProgressProvider
        )
    }

    private val shortcutButtonsAnimator by lazy {
        UnfoldConstantTranslateAnimator(
            viewsIdToTranslate =
            setOf(
                ViewIdToTranslate(
                    viewId = R.id.start_button,
                    direction = START,
                    shouldBeAnimated = filterKeyguard
                ),
                ViewIdToTranslate(
                    viewId = R.id.end_button,
                    direction = END,
                    shouldBeAnimated = filterKeyguard
                )
            ),
            progressProvider = unfoldProgressProvider
        )
    }

    /** Initializes the keyguard fold/unfold transition */
    fun setup() {
        val translationMax =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_unfold_translation_x).toFloat()

        translateAnimator.init(shadeWindowView, translationMax)

        // Use keyguard root view as there is another instance of start/end buttons with the same ID
        // outside of the keyguard root view
        shortcutButtonsAnimator.init(keyguardRootView, translationMax)
    }
}
