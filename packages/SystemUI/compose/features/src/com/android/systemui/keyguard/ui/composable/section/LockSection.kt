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

package com.android.systemui.keyguard.ui.composable.section

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.biometrics.AuthController
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import javax.inject.Inject

class LockSection
@Inject
constructor(
    private val windowManager: WindowManager,
    private val authController: AuthController,
    private val featureFlags: FeatureFlagsClassic,
) {
    @Composable
    fun SceneScope.LockIcon(modifier: Modifier = Modifier) {
        MovableElement(
            key = LockIconElementKey,
            modifier = modifier,
        ) {
            Box(
                modifier = Modifier.background(Color.Red),
            ) {
                Text(
                    text = "TODO(b/316211368): Lock",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    /**
     * Returns the bounds of the lock icon, in window view coordinates.
     *
     * On devices that support UDFPS (under-display fingerprint sensor), the bounds of the icon are
     * the same as the bounds of the sensor.
     */
    fun lockIconBounds(
        context: Context,
    ): Rect {
        val windowViewBounds = windowManager.currentWindowMetrics.bounds
        var widthPx = windowViewBounds.right.toFloat()
        if (featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE)) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            // Assumed to be initially neglected as there are no left or right insets in portrait.
            // However, on landscape, these insets need to included when calculating the midpoint.
            @Suppress("DEPRECATION")
            widthPx -= (insets.systemWindowInsetLeft + insets.systemWindowInsetRight).toFloat()
        }
        val defaultDensity =
            DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                DisplayMetrics.DENSITY_DEFAULT.toFloat()
        val lockIconRadiusPx = (defaultDensity * 36).toInt()

        val udfpsLocation = authController.udfpsLocation
        return if (authController.isUdfpsSupported && udfpsLocation != null) {
            centerLockIcon(udfpsLocation, authController.udfpsRadius)
        } else {
            val scaleFactor = authController.scaleFactor
            val bottomPaddingPx =
                context.resources.getDimensionPixelSize(R.dimen.lock_icon_margin_bottom)
            val heightPx = windowViewBounds.bottom.toFloat()

            centerLockIcon(
                Point(
                    (widthPx / 2).toInt(),
                    (heightPx - ((bottomPaddingPx + lockIconRadiusPx) * scaleFactor)).toInt()
                ),
                lockIconRadiusPx * scaleFactor
            )
        }
    }

    private fun centerLockIcon(
        center: Point,
        radius: Float,
    ): Rect {
        return Rect().apply {
            set(
                center.x - radius.toInt(),
                center.y - radius.toInt(),
                center.x + radius.toInt(),
                center.y + radius.toInt(),
            )
        }
    }
}

private val LockIconElementKey = ElementKey("LockIcon")
