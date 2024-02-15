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
 */

package com.android.systemui.keyguard.ui.binder

import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.classifier.Classifier
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.deviceentry.ui.binder.UdfpsAccessibilityOverlayBinder
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlay
import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.scrim.ScrimView
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/**
 * Binds the alternate bouncer view to its view-model.
 *
 * For devices that support UDFPS, this includes a UDFPS view.
 */
@ExperimentalCoroutinesApi
object AlternateBouncerViewBinder {

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ConstraintLayout,
        alternateBouncerDependencies: AlternateBouncerDependencies,
    ) {
        if (DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()) {
            return
        }
        optionallyAddUdfpsViews(
            view = view,
            udfpsIconViewModel = alternateBouncerDependencies.udfpsIconViewModel,
            udfpsA11yOverlayViewModel =
                alternateBouncerDependencies.udfpsAccessibilityOverlayViewModel,
        )

        AlternateBouncerMessageAreaViewBinder.bind(
            view = view.requireViewById(R.id.alternate_bouncer_message_area),
            viewModel = alternateBouncerDependencies.messageAreaViewModel,
        )

        val scrim = view.requireViewById(R.id.alternate_bouncer_scrim) as ScrimView
        val viewModel = alternateBouncerDependencies.viewModel
        val swipeUpAnywhereGestureHandler =
            alternateBouncerDependencies.swipeUpAnywhereGestureHandler
        val falsingManager = alternateBouncerDependencies.falsingManager
        val tapGestureDetector = alternateBouncerDependencies.tapGestureDetector
        view.repeatWhenAttached { alternateBouncerViewContainer ->
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scrim.viewAlpha = 0f

                launch {
                    viewModel.registerForDismissGestures.collect { registerForDismissGestures ->
                        if (registerForDismissGestures) {
                            swipeUpAnywhereGestureHandler.addOnGestureDetectedCallback(swipeTag) { _
                                ->
                                if (
                                    !falsingManager.isFalseTouch(Classifier.ALTERNATE_BOUNCER_SWIPE)
                                ) {
                                    viewModel.showPrimaryBouncer()
                                }
                            }
                            tapGestureDetector.addOnGestureDetectedCallback(tapTag) { _ ->
                                if (!falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                                    viewModel.showPrimaryBouncer()
                                }
                            }
                        } else {
                            swipeUpAnywhereGestureHandler.removeOnGestureDetectedCallback(swipeTag)
                            tapGestureDetector.removeOnGestureDetectedCallback(tapTag)
                        }
                    }
                }

                launch {
                    viewModel.scrimAlpha.collect {
                        alternateBouncerViewContainer.visibility =
                            if (it < .1f) View.INVISIBLE else View.VISIBLE
                        scrim.viewAlpha = it
                    }
                }

                launch { viewModel.scrimColor.collect { scrim.tint = it } }
            }
        }
    }

    private fun optionallyAddUdfpsViews(
        view: ConstraintLayout,
        udfpsIconViewModel: AlternateBouncerUdfpsIconViewModel,
        udfpsA11yOverlayViewModel: Lazy<AlternateBouncerUdfpsAccessibilityOverlayViewModel>,
    ) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    udfpsIconViewModel.iconLocation.collect { iconLocation ->
                        // add UDFPS a11y overlay
                        val udfpsA11yOverlayViewId =
                            R.id.alternate_bouncer_udfps_accessibility_overlay
                        var udfpsA11yOverlay = view.getViewById(udfpsA11yOverlayViewId)
                        if (udfpsA11yOverlay == null) {
                            udfpsA11yOverlay =
                                UdfpsAccessibilityOverlay(view.context).apply {
                                    id = udfpsA11yOverlayViewId
                                }
                            view.addView(udfpsA11yOverlay)
                            UdfpsAccessibilityOverlayBinder.bind(
                                udfpsA11yOverlay,
                                udfpsA11yOverlayViewModel.get(),
                            )
                        }

                        // add UDFPS icon view
                        val udfpsViewId = R.id.alternate_bouncer_udfps_icon_view
                        var udfpsView = view.getViewById(udfpsViewId)
                        if (udfpsView == null) {
                            udfpsView =
                                DeviceEntryIconView(view.context, null).apply {
                                    id = udfpsViewId
                                    contentDescription =
                                        context.resources.getString(
                                            R.string.accessibility_fingerprint_label
                                        )
                                }
                            view.addView(udfpsView)
                            AlternateBouncerUdfpsViewBinder.bind(
                                udfpsView,
                                udfpsIconViewModel,
                            )
                        }

                        val constraintSet = ConstraintSet().apply { clone(view) }
                        constraintSet.apply {
                            // udfpsView:
                            constrainWidth(udfpsViewId, iconLocation.width)
                            constrainHeight(udfpsViewId, iconLocation.height)
                            connect(
                                udfpsViewId,
                                ConstraintSet.TOP,
                                ConstraintSet.PARENT_ID,
                                ConstraintSet.TOP,
                                iconLocation.top,
                            )
                            connect(
                                udfpsViewId,
                                ConstraintSet.START,
                                ConstraintSet.PARENT_ID,
                                ConstraintSet.START,
                                iconLocation.left
                            )

                            // udfpsA11yOverlayView:
                            constrainWidth(
                                udfpsA11yOverlayViewId,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            constrainHeight(
                                udfpsA11yOverlayViewId,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        constraintSet.applyTo(view)
                    }
                }
            }
        }
    }
}

private const val swipeTag = "AlternateBouncer-SWIPE"
private const val tapTag = "AlternateBouncer-TAP"
