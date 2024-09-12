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

import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.deviceentry.ui.binder.UdfpsAccessibilityOverlayBinder
import com.android.systemui.deviceentry.ui.view.UdfpsAccessibilityOverlay
import com.android.systemui.deviceentry.ui.viewmodel.AlternateBouncerUdfpsAccessibilityOverlayViewModel
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerDependencies
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerUdfpsIconViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerWindowViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scrim.ScrimView
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * When necessary, adds the alternate bouncer window above most other windows (including the
 * notification shade, system UI dialogs) but below the UDFPS touch overlay and SideFPS indicator.
 * Also binds the alternate bouncer view to its view-model.
 *
 * For devices that support UDFPS, this view includes a UDFPS view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AlternateBouncerViewBinder
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val alternateBouncerWindowViewModel: Lazy<AlternateBouncerWindowViewModel>,
    private val alternateBouncerDependencies: Lazy<AlternateBouncerDependencies>,
    private val windowManager: Lazy<WindowManager>,
    private val layoutInflater: Lazy<LayoutInflater>,
) : CoreStartable {
    private val layoutParams: WindowManager.LayoutParams
        get() =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "AlternateBouncerView"
                    fitInsetsTypes = 0 // overrides default, avoiding status bars during layout
                    gravity = Gravity.TOP or Gravity.LEFT
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    privateFlags =
                        WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                            WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
                }
    private var alternateBouncerView: ConstraintLayout? = null

    override fun start() {
        if (!DeviceEntryUdfpsRefactor.isEnabled) {
            return
        }
        applicationScope.launch("$TAG#alternateBouncerWindowViewModel") {
            alternateBouncerWindowViewModel.get().alternateBouncerWindowRequired.collect {
                addAlternateBouncerWindowView ->
                Log.d(TAG, "alternateBouncerWindowRequired=$addAlternateBouncerWindowView")
                if (addAlternateBouncerWindowView) {
                    addViewToWindowManager()
                    val scrim: ScrimView =
                        alternateBouncerView!!.requireViewById(R.id.alternate_bouncer_scrim)
                    scrim.viewAlpha = 0f
                    bind(alternateBouncerView!!, alternateBouncerDependencies.get())
                } else {
                    removeViewFromWindowManager()
                    alternateBouncerDependencies.get().viewModel.hideAlternateBouncer()
                }
            }
        }
    }

    private fun removeViewFromWindowManager() {
        if (alternateBouncerView == null || !alternateBouncerView!!.isAttachedToWindow) {
            return
        }

        windowManager.get().removeView(alternateBouncerView)
        alternateBouncerView!!.removeOnAttachStateChangeListener(onAttachAddBackGestureHandler)
        alternateBouncerView = null
    }

    private val onAttachAddBackGestureHandler =
        object : View.OnAttachStateChangeListener {
            private val onBackInvokedCallback: OnBackInvokedCallback = OnBackInvokedCallback {
                onBackRequested()
            }

            override fun onViewAttachedToWindow(view: View) {
                view
                    .findOnBackInvokedDispatcher()
                    ?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        onBackInvokedCallback,
                    )
            }

            override fun onViewDetachedFromWindow(view: View) {
                view
                    .findOnBackInvokedDispatcher()
                    ?.unregisterOnBackInvokedCallback(onBackInvokedCallback)
            }

            fun onBackRequested() {
                alternateBouncerDependencies.get().viewModel.hideAlternateBouncer()
            }
        }

    private fun addViewToWindowManager() {
        if (alternateBouncerView?.isAttachedToWindow == true) {
            return
        }

        alternateBouncerView =
            layoutInflater.get().inflate(R.layout.alternate_bouncer, null, false)
                as ConstraintLayout

        windowManager.get().addView(alternateBouncerView, layoutParams)
        alternateBouncerView!!.addOnAttachStateChangeListener(onAttachAddBackGestureHandler)
    }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
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
        val tapGestureDetector = alternateBouncerDependencies.tapGestureDetector
        view.repeatWhenAttached { alternateBouncerViewContainer ->
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch("$TAG#viewModel.registerForDismissGestures") {
                        viewModel.registerForDismissGestures.collect { registerForDismissGestures ->
                            if (registerForDismissGestures) {
                                swipeUpAnywhereGestureHandler.addOnGestureDetectedCallback(
                                    swipeTag
                                ) { _ ->
                                    alternateBouncerDependencies.powerInteractor.onUserTouch()
                                    viewModel.showPrimaryBouncer()
                                }
                                tapGestureDetector.addOnGestureDetectedCallback(tapTag) { _ ->
                                    alternateBouncerDependencies.powerInteractor.onUserTouch()
                                    viewModel.showPrimaryBouncer()
                                }
                            } else {
                                swipeUpAnywhereGestureHandler.removeOnGestureDetectedCallback(
                                    swipeTag
                                )
                                tapGestureDetector.removeOnGestureDetectedCallback(tapTag)
                            }
                        }
                    }
                    .invokeOnCompletion {
                        swipeUpAnywhereGestureHandler.removeOnGestureDetectedCallback(swipeTag)
                        tapGestureDetector.removeOnGestureDetectedCallback(tapTag)
                    }

                launch("$TAG#viewModel.scrimAlpha") {
                    viewModel.scrimAlpha.collect { scrim.viewAlpha = it }
                }

                launch("$TAG#viewModel.scrimColor") {
                    viewModel.scrimColor.collect { scrim.tint = it }
                }
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
                launch("$TAG#udfpsIconViewModel.iconLocation") {
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
    companion object {
        private const val TAG = "AlternateBouncerViewBinder"
        private const val swipeTag = "AlternateBouncer-SWIPE"
        private const val tapTag = "AlternateBouncer-TAP"
    }
}
