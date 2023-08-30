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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.LockIconView
import com.android.keyguard.LockIconViewController
import com.android.systemui.R
import com.android.systemui.biometrics.AuthController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.shade.NotificationPanelView
import javax.inject.Inject

class DefaultLockIconSection
@Inject
constructor(
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val authController: AuthController,
    private val windowManager: WindowManager,
    private val context: Context,
    private val notificationPanelView: NotificationPanelView,
    private val featureFlags: FeatureFlags,
    private val lockIconViewController: LockIconViewController,
) : KeyguardSection {
    private val lockIconViewId = R.id.lock_icon_view

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (featureFlags.isEnabled(Flags.MIGRATE_LOCK_ICON)) {
            notificationPanelView.findViewById<View>(R.id.lock_icon_view).let {
                notificationPanelView.removeView(it)
            }
            if (constraintLayout.findViewById<View>(R.id.lock_icon_view) == null) {
                val view = LockIconView(context, null).apply { id = R.id.lock_icon_view }
                constraintLayout.addView(view)
                lockIconViewController.setLockIconView(view)
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val isUdfpsSupported = keyguardUpdateMonitor.isUdfpsSupported
        val scaleFactor: Float = authController.scaleFactor
        val mBottomPaddingPx =
            context.resources.getDimensionPixelSize(R.dimen.lock_icon_margin_bottom)
        val bounds = windowManager.currentWindowMetrics.bounds
        val widthPixels = bounds.right.toFloat()
        val heightPixels = bounds.bottom.toFloat()
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        if (isUdfpsSupported) {
            authController.udfpsLocation?.let { udfpsLocation ->
                centerLockIcon(udfpsLocation, authController.udfpsRadius, constraintSet)
            }
        } else {
            centerLockIcon(
                Point(
                    (widthPixels / 2).toInt(),
                    (heightPixels - ((mBottomPaddingPx + lockIconRadiusPx) * scaleFactor)).toInt()
                ),
                lockIconRadiusPx * scaleFactor,
                constraintSet,
            )
        }
    }

    @VisibleForTesting
    internal fun centerLockIcon(center: Point, radius: Float, constraintSet: ConstraintSet) {
        val sensorRect =
            Rect().apply {
                set(
                    center.x - radius.toInt(),
                    center.y - radius.toInt(),
                    center.x + radius.toInt(),
                    center.y + radius.toInt(),
                )
            }

        constraintSet.apply {
            constrainWidth(lockIconViewId, sensorRect.right - sensorRect.left)
            constrainHeight(lockIconViewId, sensorRect.bottom - sensorRect.top)
            connect(
                lockIconViewId,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                sensorRect.top
            )
            connect(
                lockIconViewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                sensorRect.left
            )
        }
    }
}
