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

package com.android.systemui.keyguard.ui.view.layout

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import javax.inject.Inject

/**
 * Positions elements of the lockscreen to the default position.
 *
 * This will be the most common use case for phones in portrait mode.
 */
@SysUISingleton
class DefaultLockscreenLayout
@Inject
constructor(
    private val authController: AuthController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val windowManager: WindowManager,
    private val context: Context,
) : LockscreenLayout {
    override val id: String = DEFAULT

    override fun layoutIndicationArea(rootView: KeyguardRootView) {
        val indicationArea = rootView.findViewById<View>(R.id.keyguard_indication_area) ?: return

        rootView.getConstraintSet().apply {
            constrainWidth(indicationArea.id, MATCH_PARENT)
            constrainHeight(indicationArea.id, WRAP_CONTENT)
            connect(
                indicationArea.id,
                BOTTOM,
                PARENT_ID,
                BOTTOM,
                R.dimen.keyguard_indication_margin_bottom.dp()
            )
            connect(indicationArea.id, START, PARENT_ID, START)
            connect(indicationArea.id, END, PARENT_ID, END)
            applyTo(rootView)
        }
    }

    override fun layoutLockIcon(rootView: KeyguardRootView) {
        val isUdfpsSupported = keyguardUpdateMonitor.isUdfpsSupported
        val scaleFactor: Float = authController.scaleFactor
        val mBottomPaddingPx = R.dimen.lock_icon_margin_bottom.dp()
        val mDefaultPaddingPx = R.dimen.lock_icon_padding.dp()
        val scaledPadding: Int = (mDefaultPaddingPx * scaleFactor).toInt()
        val bounds = windowManager.currentWindowMetrics.bounds
        val widthPixels = bounds.right.toFloat()
        val heightPixels = bounds.bottom.toFloat()
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        if (isUdfpsSupported) {
            authController.udfpsLocation?.let { udfpsLocation ->
                centerLockIcon(udfpsLocation, authController.udfpsRadius, scaledPadding, rootView)
            }
        } else {
            centerLockIcon(
                Point(
                    (widthPixels / 2).toInt(),
                    (heightPixels - ((mBottomPaddingPx + lockIconRadiusPx) * scaleFactor)).toInt()
                ),
                lockIconRadiusPx * scaleFactor,
                scaledPadding,
                rootView
            )
        }
    }

    @VisibleForTesting
    internal fun centerLockIcon(
        center: Point,
        radius: Float,
        drawablePadding: Int,
        rootView: KeyguardRootView,
    ) {
        val lockIconView = rootView.findViewById<View>(R.id.lock_icon_view) ?: return
        val lockIcon = lockIconView.findViewById<View>(R.id.lock_icon) ?: return
        lockIcon.setPadding(drawablePadding, drawablePadding, drawablePadding, drawablePadding)

        val sensorRect =
            Rect().apply {
                set(
                    center.x - radius.toInt(),
                    center.y - radius.toInt(),
                    center.x + radius.toInt(),
                    center.y + radius.toInt(),
                )
            }

        rootView.getConstraintSet().apply {
            constrainWidth(lockIconView.id, sensorRect.right - sensorRect.left)
            constrainHeight(lockIconView.id, sensorRect.bottom - sensorRect.top)
            connect(lockIconView.id, TOP, PARENT_ID, TOP, sensorRect.top)
            connect(lockIconView.id, START, PARENT_ID, START, sensorRect.left)
            applyTo(rootView)
        }
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }

    private fun ConstraintLayout.getConstraintSet(): ConstraintSet {
        val cs = ConstraintSet()
        cs.clone(this)
        return cs
    }

    companion object {
        const val DEFAULT = "default"
    }
}
