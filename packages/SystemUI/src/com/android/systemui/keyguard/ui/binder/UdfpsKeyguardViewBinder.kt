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

package com.android.systemui.keyguard.ui.binder

import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.biometrics.UdfpsKeyguardView
import com.android.systemui.biometrics.ui.binder.UdfpsKeyguardInternalViewBinder
import com.android.systemui.keyguard.ui.viewmodel.BackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.FingerprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.UdfpsAodViewModel
import com.android.systemui.keyguard.ui.viewmodel.UdfpsKeyguardInternalViewModel
import com.android.systemui.keyguard.ui.viewmodel.UdfpsKeyguardViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object UdfpsKeyguardViewBinder {
    /**
     * Drives UI for the keyguard UDFPS view. Inflates child views on a background thread. For view
     * binders for its child views, see [UdfpsFingerprintViewBinder], [UdfpsBackgroundViewBinder] &
     * [UdfpsAodFingerprintViewBinder].
     */
    @JvmStatic
    fun bind(
        view: UdfpsKeyguardView,
        viewModel: UdfpsKeyguardViewModel,
        udfpsKeyguardInternalViewModel: UdfpsKeyguardInternalViewModel,
        aodViewModel: UdfpsAodViewModel,
        fingerprintViewModel: FingerprintViewModel,
        backgroundViewModel: BackgroundViewModel,
    ) {
        val layoutInflaterFinishListener =
            AsyncLayoutInflater.OnInflateFinishedListener { inflatedInternalView, _, parent ->
                UdfpsKeyguardInternalViewBinder.bind(
                    inflatedInternalView,
                    udfpsKeyguardInternalViewModel,
                    aodViewModel,
                    fingerprintViewModel,
                    backgroundViewModel,
                )
                val lp = inflatedInternalView.layoutParams as FrameLayout.LayoutParams
                lp.width = viewModel.sensorBounds.width()
                lp.height = viewModel.sensorBounds.height()
                val relativeToView =
                    getBoundsRelativeToView(
                        inflatedInternalView,
                        RectF(viewModel.sensorBounds),
                    )
                lp.setMarginsRelative(
                    relativeToView.left.toInt(),
                    relativeToView.top.toInt(),
                    relativeToView.right.toInt(),
                    relativeToView.bottom.toInt(),
                )
                parent!!.addView(inflatedInternalView, lp)
            }
        val inflater = AsyncLayoutInflater(view.context)
        inflater.inflate(R.layout.udfps_keyguard_view_internal, view, layoutInflaterFinishListener)

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    combine(aodViewModel.isVisible, fingerprintViewModel.visible) {
                            isAodVisible,
                            isFingerprintVisible ->
                            isAodVisible || isFingerprintVisible
                        }
                        .collect { view.setVisible(it) }
                }
            }
        }
    }

    /**
     * Converts coordinates of RectF relative to the screen to coordinates relative to this view.
     *
     * @param bounds RectF based off screen coordinates in current orientation
     */
    private fun getBoundsRelativeToView(view: View, bounds: RectF): RectF {
        val pos: IntArray = view.locationOnScreen
        return RectF(
            bounds.left - pos[0],
            bounds.top - pos[1],
            bounds.right - pos[0],
            bounds.bottom - pos[1]
        )
    }
}
