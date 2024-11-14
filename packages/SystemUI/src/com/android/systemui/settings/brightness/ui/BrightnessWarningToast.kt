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
package com.android.systemui.settings.brightness.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.StringRes
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.toast.ToastFactory
import javax.inject.Inject

@SysUISingleton
class BrightnessWarningToast
@Inject
constructor(
    private val toastFactory: ToastFactory,
    private val windowManager: WindowManager,
) {
    private var toastView: View? = null

    fun show(viewContext: Context, @StringRes resId: Int) {
        if (isToastActive()) {
            return
        }
        val res = viewContext.resources
        // Show the brightness warning toast with passing the toast inflation required context,
        // userId and resId from SystemUI package.
        val systemUIToast = toastFactory.createToast(
            viewContext, viewContext,
            res.getString(resId), viewContext.packageName, viewContext.getUserId(),
            res.configuration.orientation
        )
        if (systemUIToast == null) {
            return
        }

        toastView = systemUIToast.view

        val params = WindowManager.LayoutParams()
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.format = PixelFormat.TRANSLUCENT
        params.title = "Brightness warning toast"
        params.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
        params.flags = (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        params.y = systemUIToast.yOffset

        val absGravity = Gravity.getAbsoluteGravity(
            systemUIToast.gravity,
            res.configuration.layoutDirection
        )
        params.gravity = absGravity
        if ((absGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
            params.horizontalWeight = TOAST_PARAMS_HORIZONTAL_WEIGHT
        }
        if ((absGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
            params.verticalWeight = TOAST_PARAMS_VERTICAL_WEIGHT
        }

        windowManager.addView(toastView, params)

        val inAnimator = systemUIToast.inAnimation
        inAnimator?.start()

        toastView?.postDelayed({
            val outAnimator = systemUIToast.outAnimation
            if (outAnimator != null) {
                outAnimator.start()
                outAnimator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) {
                        if (isToastActive()) {
                            windowManager.removeViewImmediate(toastView)
                        }
                        toastView = null
                    }
                })
            }
        }, TOAST_DURATION_MS)
    }

    fun isToastActive(): Boolean {
        return toastView?.isAttachedToWindow == true
    }

    companion object {
        private const val TOAST_PARAMS_HORIZONTAL_WEIGHT = 1.0f
        private const val TOAST_PARAMS_VERTICAL_WEIGHT = 1.0f
        private const val TOAST_DURATION_MS: Long = 3000
    }
}
